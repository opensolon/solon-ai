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
 * 团队协作调解任务（Team Supervisor / 决策中心）
 * <p>
 * 核心职责：
 * 1. 协调：作为团队的“大脑”，根据协作历史决定下一个执行者。
 * 2. 路由：解析 LLM 决策文本，将其转换为具体的 Flow 节点跳转指令。
 * 3. 拦截：支持通过 {@link org.noear.solon.ai.agent.team.TeamProtocol} 实现自定义的执行与路由拦截逻辑。
 * </p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public class SupervisorTask implements NamedTaskComponent {
    /**
     * 日志对象
     */
    private static final Logger LOG = LoggerFactory.getLogger(SupervisorTask.class);

    /**
     * 团队配置引用
     */
    private final TeamConfig config;

    /**
     * 构造函数
     *
     * @param config 团队配置信息
     */
    public SupervisorTask(TeamConfig config) {
        this.config = config;
    }

    /**
     * 组件名称（对应 ID_SUPERVISOR）
     */
    @Override
    public String name() {
        return Agent.ID_SUPERVISOR;
    }

    /**
     * 核心运行逻辑（Flow 节点入口）
     */
    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        if (LOG.isDebugEnabled()) {
            LOG.debug("TeamAgent [{}] supervisor starting...", config.getName());
        }

        try {
            // 1. 获取协作轨迹
            String traceKey = context.getAs(Agent.KEY_CURRENT_TRACE_KEY);
            TeamTrace trace = context.getAs(traceKey);

            if (trace == null) {
                LOG.error("TeamAgent [{}] supervisor: Team trace not found", config.getName());
                return;
            }

            // 2. 预检：判断是否已达到最大迭代次数或已明确结束
            if (Agent.ID_END.equals(trace.getRoute()) ||
                    trace.getIterationsCount() >= config.getMaxTotalIterations()) {
                trace.setRoute(Agent.ID_END);
                return;
            }

            // 3. [协议生命周期 - 执行拦截] 询问协议是否接管执行逻辑（如顺序模式可能不通过 LLM 决策）
            if (config.getProtocol().shouldSupervisorExecute(context, trace)) {
                return;
            }

            // 4. 进入基于 LLM 调度
            dispatch(context, trace);

        } catch (Exception e) {
            handleError(context, e);
        }
    }

    /**
     * 驱动大语言模型进行调度
     */
    private void dispatch(FlowContext context, TeamTrace trace) throws Exception {
        // 1. [协议生命周期 - 指令准备] 获取协议动态注入的指令（如：黑板数据、约束规则等）
        StringBuilder protocolExt = new StringBuilder();
        config.getProtocol().prepareSupervisorInstruction(context, trace, protocolExt);

        // 2. 组装提示词（包含系统 Prompt + 协作历史）
        String basePrompt = config.getPromptProvider().getSystemPrompt(trace);
        String enhancedPrompt = (protocolExt.length() > 0)
                ? basePrompt + "\n\n=== Protocol Extensions ===\n" + protocolExt
                : basePrompt;

        List<ChatMessage> messages = Arrays.asList(
                ChatMessage.ofSystem(enhancedPrompt),
                ChatMessage.ofUser("Collaboration History:\n" + trace.getFormattedHistory() +
                        "\n\nCurrent iteration: " + trace.getIterationsCount() +
                        "\nPlease decide the next action:")
        );

        // 3. 调用 LLM 并获取决策建议
        String decision = callWithRetry(trace, messages);

        // 4. 清理决策文本（防止 LLM 复读历史信息）
        if (decision.contains("Collaboration History:")) {
            decision = decision.split("Collaboration History:")[0].trim();
        }

        // 5. 将原始决策记录到轨迹中，供后续工具或协议分析
        trace.setLastDecision(decision);

        if (LOG.isDebugEnabled()) {
            LOG.debug("TeamAgent [{}] supervisor decision: {}", config.getName(), decision);
        }

        // 6. 进行决策解析与路由派发
        commitRoute(trace, decision, context);
        trace.nextIterations();
    }

    /**
     * 具备重试机制的 LLM 调用
     *
     * @param trace    协作轨迹
     * @param messages 待发送的消息列表
     * @return LLM 返回的决策文本
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
                    // 采用指数退避策略进行重试延迟
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
     * 解析 LLM 决策文本并确定路由路径
     *
     * @param trace    协作轨迹
     * @param decision 原始决策文本
     * @param context  执行上下文
     */
    private void commitRoute(TeamTrace trace, String decision, FlowContext context) {
        if (Assert.isEmpty(decision)) {
            trace.setRoute(Agent.ID_END);
            return;
        }

        // 1. [一级路由：协议解析] 协议根据业务逻辑（如 ToolCall、XML、特定信号）精准解析目标
        String protoRoute = config.getProtocol().resolveSupervisorRoute(context, trace, decision);
        if (Assert.isNotEmpty(protoRoute)) {
            routeTo(context, trace, protoRoute);
            return;
        }

        // 2. [二级路由：拦截器干预] 兼容旧逻辑，用于处理不改变路由但需要处理副作用的情况
        if (config.getProtocol().shouldSupervisorRoute(context, trace, decision)) {
            return;
        }

        // 3. [三级路由：模糊匹配] 默认的 Agent 名称匹配逻辑
        if (matchAgentRoute(context, trace, decision)) {
            return;
        }

        // 4. [四级路由：结束判断] 匹配 Finish Marker
        String finishMarker = config.getFinishMarker();
        String finishRegex = "(?i).*?(\\Q" + finishMarker + "\\E)(.*)";
        Pattern pattern = Pattern.compile(finishRegex, Pattern.DOTALL);
        Matcher matcher = pattern.matcher(decision);

        if (matcher.find()) {
            trace.setRoute(Agent.ID_END);
            String finalAnswer = matcher.group(2).trim();
            // 提取 Marker 后的内容作为最终总结
            if (!finalAnswer.isEmpty()) {
                trace.setFinalAnswer(finalAnswer);
            } else {
                trace.setFinalAnswer("Task completed by " + config.getName());
            }
            return;
        }

        // 4. 兜底处理：若既无法识别 Agent 也未结束，则强行终止防止循环死机
        trace.setRoute(Agent.ID_END);
        LOG.warn("TeamAgent [{}] supervisor could not resolve next agent: {}", config.getName(), decision);
    }

    /**
     * 智能识别决策文本中的智能体身份
     *
     * @return 是否成功识别并设置了路由
     */
    private boolean matchAgentRoute(FlowContext context, TeamTrace trace, String text) {
        // 1. 尝试全词精准匹配
        Agent agent = config.getAgentMap().get(text);
        if (agent != null) {
            routeTo(context, trace, agent.name());
            return true;
        }

        // 2. 模糊匹配：按名称长度降序排列，防止短名 Agent 误触发（如防止 SeniorCoder 匹配到 Coder）
        List<String> sortedNames = config.getAgentMap().keySet().stream()
                .sorted((a, b) -> b.length() - a.length())
                .collect(Collectors.toList());

        for (String name : sortedNames) {
            // 使用单词边界匹配，提高识别精度
            Pattern p = Pattern.compile("\\b" + Pattern.quote(name) + "\\b", Pattern.CASE_INSENSITIVE);
            if (p.matcher(text).find()) {
                routeTo(context, trace, name);
                return true;
            }
        }
        return false;
    }

    /**
     * 统一的路由派发方法
     *
     * @param targetName 目标智能体 ID
     */
    private void routeTo(FlowContext context, TeamTrace trace, String targetName) {
        trace.setRoute(targetName);
        // [协议生命周期 - 路由确认通知] 告知协议路由已确定，可进行入场前置处理
        config.getProtocol().onSupervisorRouting(context, trace, targetName);
        if (LOG.isDebugEnabled()) {
            LOG.debug("TeamAgent [{}] supervisor routed to: [{}]", config.getName(), targetName);
        }
    }

    /**
     * 统一的异常拦截与状态恢复
     */
    private void handleError(FlowContext context, Exception e) {
        LOG.error("TeamAgent [{}] supervisor task failed", config.getName(), e);

        String traceKey = context.getAs(Agent.KEY_CURRENT_TRACE_KEY);
        TeamTrace trace = context.getAs(traceKey);
        if (trace != null) {
            trace.setRoute(Agent.ID_END); // 发生故障时安全着陆
            trace.addStep(Agent.ID_SUPERVISOR, "Error: " + e.getMessage(), 0);
        }
    }
}