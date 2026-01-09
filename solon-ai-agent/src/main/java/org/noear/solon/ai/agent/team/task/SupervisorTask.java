/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.agent.team.task;

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.team.TeamConfig;
import org.noear.solon.ai.agent.team.TeamInterceptor;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatRequestDesc;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.RankEntity;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.NamedTaskComponent;
import org.noear.solon.flow.Node;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 团队协作指挥任务 (Supervisor Task)
 * <p>
 * 作为 AI 团队的“决策大脑”，其核心职责是根据历史协作记录，决定下一步是由哪个成员执行，还是直接结束任务。
 * 该任务节点集成了协议控制、模型推理及多级拦截机制，确保协作流的有序与安全。
 * </p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public class SupervisorTask implements NamedTaskComponent {
    private static final Logger LOG = LoggerFactory.getLogger(SupervisorTask.class);

    private final TeamConfig config;

    public SupervisorTask(TeamConfig config) {
        this.config = config;
    }

    @Override
    public String name() {
        return Agent.ID_SUPERVISOR;
    }

    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        if (LOG.isDebugEnabled()) {
            LOG.debug("TeamAgent [{}] supervisor starting...", config.getName());
        }

        try {
            String traceKey = context.getAs(Agent.KEY_CURRENT_TRACE_KEY);
            TeamTrace trace = context.getAs(traceKey);

            if (trace == null) {
                LOG.error("TeamAgent [{}] supervisor: Team trace not found", config.getName());
                return;
            }

            // [逻辑 1：准入控制]
            // 通过 shouldSupervisorContinue 允许外部拦截决策（如：检查 Token 预算、人工干预或发现逻辑环）
            for (RankEntity<TeamInterceptor> item : config.getInterceptorList()) {
                if (item.target.shouldSupervisorContinue(trace) == false) {
                    trace.addStep(Agent.ID_SUPERVISOR,
                            "[Skipped] Supervisor decision was intercepted by " + item.target.getClass().getSimpleName(),
                            0);

                    if (Agent.ID_SUPERVISOR.equals(trace.getRoute())) {
                        trace.setRoute(Agent.ID_END);
                    }
                    return;
                }
            }

            // [逻辑 2：物理深度熔断]
            // 防止因模型决策异常导致的无限协作循环，当达到配置的最大迭代次数时硬性终止
            if (Agent.ID_END.equals(trace.getRoute()) ||
                    trace.getIterationsCount() >= config.getMaxTotalIterations()) {
                trace.setRoute(Agent.ID_END);
                trace.addStep(Agent.ID_SYSTEM,
                        "[Terminated] Maximum total iterations reached: " + config.getMaxTotalIterations(),
                        0);
                return;
            }

            // [逻辑 3：协议预接管]
            // 某些确定性逻辑（如 Pipeline 模式）可能无需 LLM 推理，直接由协议确定下一跳
            if (config.getProtocol().shouldSupervisorExecute(context, trace)) {
                return;
            }

            // [逻辑 4：核心调度驱动]
            dispatch(context, trace);

        } catch (Exception e) {
            handleError(context, e);
        }
    }

    /**
     * 执行基于推理的决策分发
     */
    private void dispatch(FlowContext context, TeamTrace trace) throws Exception {
        // 1. 组装系统指令（Base Prompt + 协议规则注入）
        StringBuilder protocolExt = new StringBuilder();
        config.getProtocol().prepareSupervisorInstruction(context, trace, protocolExt);

        String basePrompt = config.getPromptProvider().getSystemPrompt(trace);
        String finalSystemPrompt = (protocolExt.length() > 0)
                ? basePrompt + "\n\n### Additional Protocol Rules\n" + protocolExt
                : basePrompt;

        // 2. 构建上下文：向模型展示协作全景及当前迭代状态
        List<ChatMessage> messages = Arrays.asList(
                ChatMessage.ofSystem(finalSystemPrompt),
                ChatMessage.ofUser("## Collaboration History\n" + trace.getFormattedHistory() +
                        "\n\n---\nCurrent Iteration: " + trace.nextIterations() +
                        "\nPlease decide the next agent or 'finish':")
        );

        // 3. 执行模型推理（含重试机制与 ModelStart 拦截）
        ChatResponse response = callWithRetry(trace, messages);

        // 4. 触发 ModelEnd 拦截
        // 作用：可以拦截原始回复进行风控，或在发现模型连续重复调度同一 Agent 时抛出异常中断
        for(RankEntity<TeamInterceptor> item : config.getInterceptorList()){
            item.target.onModelEnd(trace, response);
        }

        String decision = response.getResultContent().trim();
        trace.setLastDecision(decision);

        // 5. 触发决策内容观测
        config.getInterceptorList().forEach(item -> item.target.onSupervisorDecision(trace, decision));

        // 6. 语义解析与路由提交
        commitRoute(trace, decision, context);
    }

    /**
     * 带韧性防护的模型调用封装
     */
    private ChatResponse callWithRetry(TeamTrace trace, List<ChatMessage> messages) {
        ChatRequestDesc req = config.getChatModel().prompt(messages).options(o -> {
            if (config.getChatOptions() != null) {
                config.getChatOptions().accept(o);
            }
        });

        // 触发推理开始拦截：可在此修改请求参数或记录日志
        for(RankEntity<TeamInterceptor> item : config.getInterceptorList()){
            item.target.onModelStart(trace, req);
        }

        int maxRetries = config.getMaxRetries();
        for (int i = 0; i < maxRetries; i++) {
            try {
                return req.call();
            } catch (Exception e) {
                if (i == maxRetries - 1) {
                    LOG.error("TeamAgent [{}] supervisor failed after {} retries", config.getName(), maxRetries, e);
                    throw new RuntimeException("Supervisor dispatch failed after max retries", e);
                }

                try {
                    // 退避重试机制
                    Thread.sleep(config.getRetryDelayMs() * (i + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Retry interrupted", ie);
                }
            }
        }
        throw new RuntimeException("Unreachable code");
    }

    /**
     * 路由提交引擎：根据语义决策文本确定物理节点
     */
    private void commitRoute(TeamTrace trace, String decision, FlowContext context) {
        if (Assert.isEmpty(decision)) {
            trace.setRoute(Agent.ID_END);
            return;
        }

        // 优先级 1：协议路由接管（允许协议定义特殊的路由语法）
        String protoRoute = config.getProtocol().resolveSupervisorRoute(context, trace, decision);
        if (Assert.isNotEmpty(protoRoute)) {
            routeTo(context, trace, protoRoute);
            return;
        }

        // 优先级 2：协议路由干预
        if (config.getProtocol().shouldSupervisorRoute(context, trace, decision)) {
            return;
        }

        // 优先级 3：成员名称匹配（贪婪匹配名录中的 Agent 名称）
        if (matchAgentRoute(context, trace, decision)) {
            return;
        }

        // 优先级 4：任务结束标识解析
        String finishMarker = config.getFinishMarker();
        String finishRegex = "(?i).*?(\\Q" + finishMarker + "\\E)(.*)";
        Pattern pattern = Pattern.compile(finishRegex, Pattern.DOTALL);
        Matcher matcher = pattern.matcher(decision);

        if (matcher.find()) {
            trace.setRoute(Agent.ID_END);
            String finalAnswer = matcher.group(2).trim();
            trace.setFinalAnswer(finalAnswer.isEmpty() ? "Task completed." : finalAnswer);
            return;
        }

        // 优先级 5：兜底处理。无法解析时终止任务，避免无效迭代。
        trace.setRoute(Agent.ID_END);
        LOG.warn("TeamAgent [{}] could not resolve route from decision: {}", config.getName(), decision);
    }

    /**
     * 智能名称识别：识别决策中提及的目标 Agent 身份
     */
    private boolean matchAgentRoute(FlowContext context, TeamTrace trace, String text) {
        // 精准匹配
        Agent agent = config.getAgentMap().get(text);
        if (agent != null) {
            routeTo(context, trace, agent.name());
            return true;
        }

        // 降序模糊匹配（优先匹配长名，防止子字符串误伤）
        List<String> sortedNames = config.getAgentMap().keySet().stream()
                .sorted((a, b) -> b.length() - a.length())
                .collect(Collectors.toList());

        for (String name : sortedNames) {
            Pattern p = Pattern.compile("\\b" + Pattern.quote(name) + "\\b", Pattern.CASE_INSENSITIVE);
            if (p.matcher(text).find()) {
                routeTo(context, trace, name);
                return true;
            }
        }
        return false;
    }

    private void routeTo(FlowContext context, TeamTrace trace, String targetName) {
        trace.setRoute(targetName);
        config.getProtocol().onSupervisorRouting(context, trace, targetName);
        if (LOG.isDebugEnabled()) {
            LOG.debug("TeamAgent [{}] supervisor routed to: [{}]", config.getName(), targetName);
        }
    }

    private void handleError(FlowContext context, Exception e) {
        LOG.error("TeamAgent [{}] supervisor task error", config.getName(), e);

        String traceKey = context.getAs(Agent.KEY_CURRENT_TRACE_KEY);
        TeamTrace trace = context.getAs(traceKey);
        if (trace != null) {
            trace.setRoute(Agent.ID_END);
            trace.addStep(Agent.ID_SUPERVISOR, "Runtime Error: " + e.getMessage(), 0);
        }
    }
}