/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
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
 * 核心职责：
 * 1. 协作调度：基于团队历史记录，通过 LLM 决定下一步由哪个成员执行或结束任务。
 * 2. 协议约束：通过 Protocol 机制实现确定性路由逻辑或对 LLM 决策进行合规校验。
 * 3. 风险熔断：监控迭代深度，防止模型决策陷入死循环导致 Token 浪费。
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

            // [逻辑 1：生命周期拦截 - 准入控制]
            // 允许外部拦截器介入决策流（如：人工干预、Token 预算检查、动态权重调整）
            for (RankEntity<TeamInterceptor> item : config.getInterceptorList()) {
                if (item.target.shouldSupervisorContinue(trace) == false) {
                    trace.addStep(Agent.ID_SUPERVISOR,
                            "[Skipped] Supervisor decision was intercepted by " + item.target.getClass().getSimpleName(),
                            0);

                    // 若被拦截且未指定路由，则强制终止流程
                    if (Agent.ID_SUPERVISOR.equals(trace.getRoute())) {
                        trace.setRoute(Agent.ID_END);
                    }
                    return;
                }
            }

            // [逻辑 2：物理深度熔断]
            // 强制检查总迭代次数，防止模型在复杂任务中产生逻辑闭环导致的无限调用
            if (Agent.ID_END.equals(trace.getRoute()) ||
                    trace.getIterationsCount() >= config.getMaxTotalIterations()) {
                trace.setRoute(Agent.ID_END);
                trace.addStep(Agent.ID_SYSTEM,
                        "[Terminated] Maximum total iterations reached: " + config.getMaxTotalIterations(),
                        0);
                return;
            }

            // [逻辑 3：协议预处理 - 推理接管]
            // 检查当前状态是否符合协议定义的“确定性路由”（如 Pipeline 模式），符合则跳过模型推理
            if (config.getProtocol().shouldSupervisorExecute(context, trace) == false) {
                // 若协议拒绝执行推理，则视为任务提前终止
                if (Agent.ID_SUPERVISOR.equals(trace.getRoute())) {
                    trace.setRoute(Agent.ID_END);
                }

                LOG.info("TeamAgent [{}] supervisor: Execution stopped by protocol", config.getName());
                return;
            }

            // [逻辑 4：核心调度驱动]
            // 进入 LLM 推理环节进行智能分发
            dispatch(context, trace);

        } catch (Exception e) {
            handleError(context, e);
        }
    }

    /**
     * 执行基于推理的决策分发
     */
    private void dispatch(FlowContext context, TeamTrace trace) throws Exception {
        // 1. 系统提示词构建：融合基础指令与协议扩展指令
        StringBuilder protocolExt = new StringBuilder();
        config.getProtocol().prepareSupervisorInstruction(context, trace, protocolExt);

        String basePrompt = config.getPromptProvider().getSystemPrompt(trace);
        String finalSystemPrompt = (protocolExt.length() > 0)
                ? basePrompt + "\n\n### Additional Protocol Rules\n" + protocolExt
                : basePrompt;

        // 2. 上下文构建：展示格式化的协作历史与当前迭代深度
        List<ChatMessage> messages = Arrays.asList(
                ChatMessage.ofSystem(finalSystemPrompt),
                ChatMessage.ofUser("## Collaboration History\n" + trace.getFormattedHistory() +
                        "\n\n---\nCurrent Iteration: " + trace.nextIterations() +
                        "\nPlease decide the next agent or 'finish':")
        );

        // 3. 模型推理（含网络层重试机制）
        ChatResponse response = callWithRetry(trace, messages);

        // 4. 响应拦截：常用于敏感内容风控或 Token 消耗统计
        for(RankEntity<TeamInterceptor> item : config.getInterceptorList()){
            item.target.onModelEnd(trace, response);
        }

        String decision = response.getResultContent().trim();
        trace.setLastDecision(decision);

        // 5. 决策观测：通知拦截器当前的调度意图
        config.getInterceptorList().forEach(item -> item.target.onSupervisorDecision(trace, decision));

        // 6. 决策解析与物理路由提交
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

        // 触发推理发起拦截
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
                    // 指数退避或固定延迟重试
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
     * 路由分发引擎：解析 LLM 决策并将其映射为 Flow 节点
     */
    private void commitRoute(TeamTrace trace, String decision, FlowContext context) {
        if (Assert.isEmpty(decision)) {
            trace.setRoute(Agent.ID_END);
            return;
        }

        // 优先级 1：协议路由硬映射（如协议定义了特殊指令的跳转规则）
        String protoRoute = config.getProtocol().resolveSupervisorRoute(context, trace, decision);
        if (Assert.isNotEmpty(protoRoute)) {
            routeTo(context, trace, protoRoute);
            return;
        }

        // 优先级 2：协议路由干预（后置校验模型给出的决策是否合规）
        if (config.getProtocol().shouldSupervisorRoute(context, trace, decision) == false) {
            trace.setRoute(Agent.ID_END);
            trace.addStep(Agent.ID_SUPERVISOR, "[Terminated] Supervisor routing denied by protocol: " + decision, 0);

            LOG.warn("TeamAgent [{}] supervisor: Routing denied by protocol for decision: {}", config.getName(), decision);
            return;
        }

        // 优先级 3：精准或模糊名称匹配（从成员名录中匹配目标 Agent）
        if (matchAgentRoute(context, trace, decision)) {
            return;
        }

        // 优先级 4：结束标识提取
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

        // 优先级 5：语义识别兜底（无法识别则强制结束，防止流程挂起）
        trace.setRoute(Agent.ID_END);
        LOG.warn("TeamAgent [{}] could not resolve route from decision: {}", config.getName(), decision);
    }

    /**
     * 成员身份智能识别：识别文本中提及的 Agent 名称
     */
    private boolean matchAgentRoute(FlowContext context, TeamTrace trace, String text) {
        // 1. 精准匹配：完全相等
        Agent agent = config.getAgentMap().get(text);
        if (agent != null) {
            routeTo(context, trace, agent.name());
            return true;
        }

        // 2. 词界模糊匹配：按名称长度降序排列，优先识别长名称（防止 A 包含 B 时识别错误）
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

    /**
     * 提交物理路由并触发协议监听
     */
    private void routeTo(FlowContext context, TeamTrace trace, String targetName) {
        trace.setRoute(targetName);
        config.getProtocol().onSupervisorRouting(context, trace, targetName);
        if (LOG.isDebugEnabled()) {
            LOG.debug("TeamAgent [{}] supervisor routed to: [{}]", config.getName(), targetName);
        }
    }

    /**
     * 异常收敛处理：确保异常情况下流程能有序关闭
     */
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