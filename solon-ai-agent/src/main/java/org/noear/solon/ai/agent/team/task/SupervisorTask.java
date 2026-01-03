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
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.core.util.Assert;
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
 * 团队协作调解任务（决策中心）
 * * 核心职责：
 * 1. 协调：作为团队的“大脑”，根据历史上下文决定下一个执行者。
 * 2. 路由：解析 LLM 决策文本，将其转换为具体的节点跳转指令。
 * 3. 干预：允许通过 TeamProtocol 在决策前后插入自定义控制逻辑（如顺序、招标等）。
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

            // [逻辑流转控制] 完全由路由状态驱动。不再扫描历史步骤内容，避免子团队信号干扰父团队判定。
            if (Agent.ID_END.equals(trace.getRoute()) ||
                    trace.getIterationsCount() >= config.getMaxTotalIterations()) {
                trace.setRoute(Agent.ID_END);
                return;
            }

            // [协议生命周期 - 执行拦截] 询问协议是否接管执行逻辑。若返回 true，则协议已完成路由跳转，不再进行 LLM 决策。
            if (config.getProtocol().interceptExecute(context, trace)) {
                return;
            }

            // 流程未被拦截，进入智能决策流程
            runIntelligent(context, trace);

        } catch (Exception e) {
            handleError(context, e);
        }
    }

    private void runIntelligent(FlowContext context, TeamTrace trace) throws Exception {
        // [协议生命周期 - 指令准备] 获取协议提供的运行时动态补充指令（如黑板数据、招标书摘要等）
        StringBuilder protocolExt = new StringBuilder();
        config.getProtocol().prepareInstruction(context, trace, protocolExt);

        String basePrompt = config.getSystemPrompt(trace);
        // 确保基础指令与协议动态指令之间有清晰的视觉隔离
        String enhancedPrompt = (protocolExt.length() > 0)
                ? basePrompt + "\n\n=== Protocol Extensions ===\n" + protocolExt
                : basePrompt;

        List<ChatMessage> messages = Arrays.asList(
                ChatMessage.ofSystem(enhancedPrompt),
                ChatMessage.ofUser("Collaboration History:\n" + trace.getFormattedHistory() +
                        "\n\nCurrent iteration: " + trace.getIterationsCount() +
                        "\nPlease decide the next action:")
        );

        String decision = callWithRetry(trace, messages);

        // 过滤掉 LLM 可能复读的历史部分，确保决策内容的纯净
        if (decision.contains("Collaboration History:")) {
            decision = decision.split("Collaboration History:")[0].trim();
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("TeamAgent [{}] supervisor decision: {}", config.getName(), decision);
        }

        // 进入决策解析与路由派发
        parseAndRoute(trace, decision, context);
        trace.nextIterations();
    }

    /**
     * 重试调用逻辑（带退避策略）
     */
    private String callWithRetry(TeamTrace trace, List<ChatMessage> messages) {
        int maxRetries = config.getMaxRetries();
        for (int i = 0; i < maxRetries; i++) {
            try {
                return config.getChatModel().prompt(messages).options(o -> {
                    if (config.getChatOptions() != null) {
                        config.getChatOptions().accept(o);
                    }
                }).call().getResultContent().trim();
            } catch (Exception e) {
                if (i == maxRetries - 1) {
                    LOG.error("TeamAgent [{}] supervisor failed after {} retries", config.getName(), maxRetries, e);
                    throw new RuntimeException("Failed after " + maxRetries + " retries", e);
                }

                if (LOG.isDebugEnabled()) {
                    LOG.debug("TeamAgent [{}] supervisor call failed (retry: {}): {}", config.getName(), i, e.getMessage());
                }

                try {
                    // 指数退避重试延迟
                    Thread.sleep(config.getRetryDelayMs() * (i + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during retry", ie);
                }
            }
        }

        throw new RuntimeException("Should not reach here");
    }

    /**
     * 核心路由解析器：将 LLM 的自然语言决策转化为具体的节点跳转指令
     */
    private void parseAndRoute(TeamTrace trace, String decision, FlowContext context) {
        if (Assert.isEmpty(decision)) {
            trace.setRoute(Agent.ID_END);
            return;
        }

        // [协议生命周期 - 路由拦截] 允许协议根据 LLm 决策内容先行拦截（如处理特殊信号词，或修正幻听的 Agent 名）
        if (config.getProtocol().interceptRouting(context, trace, decision)) {
            return;
        }

        // [第一优先级] 匹配具体的 Agent 成员名（优先尝试精准派发）
        if (matchAgentRoute(context, trace, decision)) {
            return;
        }

        // [第二优先级] 匹配任务完成标识符（Finish Marker）
        // 使用正则提取 Marker 之后的所有内容作为最终答案反馈
        String finishMarker = config.getFinishMarker();
        String finishRegex = "(?i).*?(\\Q" + finishMarker + "\\E)(.*)";
        Pattern pattern = Pattern.compile(finishRegex, Pattern.DOTALL);
        Matcher matcher = pattern.matcher(decision);

        if (matcher.find()) {
            trace.setRoute(Agent.ID_END);
            String finalAnswer = matcher.group(2).trim();
            if (!finalAnswer.isEmpty()) {
                trace.setFinalAnswer(finalAnswer);
            } else {
                trace.setFinalAnswer("Task completed by " + config.getName());
            }
            return;
        }

        // [兜底处理] 无法解析目标时强行终止任务，防止系统进入无法识别的死循环
        trace.setRoute(Agent.ID_END);
        LOG.warn("TeamAgent [{}] supervisor could not resolve next agent: {}", config.getName(), decision);
    }

    /**
     * 智能体身份识别逻辑
     */
    private boolean matchAgentRoute(FlowContext context, TeamTrace trace, String text) {
        // 1. 直接全词匹配 (O(1) 效率优先)
        Agent agent = config.getAgentMap().get(text);
        if (agent != null) {
            routeTo(context, trace, agent.name());
            return true;
        }

        // 2. 模糊正则匹配
        // 排序逻辑：名字长的 Agent 优先匹配（例如防止 "SeniorCoder" 被误匹配为 "Coder"）
        List<String> sortedNames = config.getAgentMap().keySet().stream()
                .sorted((a, b) -> b.length() - a.length())
                .collect(Collectors.toList());

        for (String name : sortedNames) {
            // 使用 \b 单词边界匹配，提高在复杂句子中的提取精度，防止单词中间字符误读
            Pattern p = Pattern.compile("\\b" + Pattern.quote(name) + "\\b", Pattern.CASE_INSENSITIVE);
            if (p.matcher(text).find()) {
                routeTo(context, trace, name);
                return true;
            }
        }
        return false;
    }

    /**
     * 统一路由派发入口，确保协议钩子被正确触发
     */
    private void routeTo(FlowContext context, TeamTrace trace, String targetName) {
        trace.setRoute(targetName);
        // [协议生命周期 - 路由确认通知]
        config.getProtocol().onRouting(context, trace, targetName);
        if (LOG.isDebugEnabled()) {
            LOG.debug("TeamAgent [{}] supervisor routed to: [{}]", config.getName(), targetName);
        }
    }

    /**
     * 异常状态管理：发生故障时强行终止并记录错误上下文
     */
    private void handleError(FlowContext context, Exception e) {
        LOG.error("TeamAgent [{}] supervisor task failed", config.getName(), e);

        String traceKey = context.getAs(Agent.KEY_CURRENT_TRACE_KEY);
        TeamTrace trace = context.getAs(traceKey);
        if (trace != null) {
            trace.setRoute(Agent.ID_END);
            trace.addStep(Agent.ID_SUPERVISOR, "Error: " + e.getMessage(), 0);
        }
    }
}