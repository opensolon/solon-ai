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
 * 团队协作指挥任务 (Supervisor Task)
 * <p>作为 AI 团队的决策大脑（Decision Center），负责根据协作轨迹、执行状态及预设协议，动态决定任务流向。</p>
 *
 * <p><b>核心生命周期：</b></p>
 * <ul>
 * <li>1. <b>状态感知</b>：提取当前 {@link TeamTrace} 执行痕迹并检查死循环风险。</li>
 * <li>2. <b>协议预检</b>：允许 {@code TeamProtocol} 优先接管逻辑（实现固定逻辑分发）。</li>
 * <li>3. <b>推理调度</b>：封装历史上下文，通过大模型（LLM）驱动语义决策。</li>
 * <li>4. <b>多级路由解析</b>：从原始回复中精准提取目标 Agent 或结束标识。</li>
 * <li>5. <b>异常熔断</b>：在决策失败或系统异常时，实现“安全着陆（Safe Landing）”。</li>
 * </ul>
 *
 *
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public class SupervisorTask implements NamedTaskComponent {
    private static final Logger LOG = LoggerFactory.getLogger(SupervisorTask.class);

    /** 关联的团队配置 */
    private final TeamConfig config;

    public SupervisorTask(TeamConfig config) {
        this.config = config;
    }

    /** 标识为系统主管节点 */
    @Override
    public String name() {
        return Agent.ID_SUPERVISOR;
    }

    /**
     * 执行决策分发逻辑
     * <p>由 Solon Flow 引擎调用，驱动团队向下一个状态演进。</p>
     */
    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        if (LOG.isDebugEnabled()) {
            LOG.debug("TeamAgent [{}] supervisor starting...", config.getName());
        }

        try {
            // 获取当前协作流的唯一追踪实例
            String traceKey = context.getAs(Agent.KEY_CURRENT_TRACE_KEY);
            TeamTrace trace = context.getAs(traceKey);

            if (trace == null) {
                LOG.error("TeamAgent [{}] supervisor: Team trace not found", config.getName());
                return;
            }

            // 智能死循环检测：若检测到 A-B-A 镜像死锁或单点复读，触发紧急避险
            if (trace.isLooping()) {
                LOG.warn("TeamAgent [{}] loop detected! Emergency stop.", config.getName());
                trace.setRoute(Agent.ID_END);
                trace.setFinalAnswer(trace.getLastAgentContent());
                return;
            }

            // 边界检查：迭代上限熔断
            if (Agent.ID_END.equals(trace.getRoute()) ||
                    trace.getIterationsCount() >= config.getMaxTotalIterations()) {
                trace.setRoute(Agent.ID_END);
                return;
            }

            // 协议挂钩：某些协议（如流水线模式）在此处直接确定路由，无需消耗 LLM Token
            if (config.getProtocol().shouldSupervisorExecute(context, trace)) {
                return;
            }

            // 核心驱动：进入基于推理的调度阶段
            dispatch(context, trace);

        } catch (Exception e) {
            handleError(context, e);
        }
    }

    /**
     * 构造提示词并驱动大模型进行“下一步决策”
     */
    private void dispatch(FlowContext context, TeamTrace trace) throws Exception {
        StringBuilder protocolExt = new StringBuilder();
        // 允许协议注入额外的运行时规则（如：必须先检查 A，再调用 B）
        config.getProtocol().prepareSupervisorInstruction(context, trace, protocolExt);

        String basePrompt = config.getPromptProvider().getSystemPrompt(trace);

        // 组装最终系统指令：基础指令 + 协议动态扩展
        String finalSystemPrompt = (protocolExt.length() > 0)
                ? basePrompt + "\n\n### Additional Protocol Rules\n" + protocolExt
                : basePrompt;

        // 构建上下文：注入结构化协作历史、迭代深度标识
        List<ChatMessage> messages = Arrays.asList(
                ChatMessage.ofSystem(finalSystemPrompt),
                ChatMessage.ofUser("## Collaboration History\n" + trace.getFormattedHistory() +
                        "\n\n---\nCurrent Iteration: " + trace.nextIterations() +
                        "\nPlease decide the next agent or 'finish':")
        );

        // 调用推理引擎，支持指数退避重试
        String decision = callWithRetry(trace, messages);
        trace.setLastDecision(decision);

        // 提交路由指令，转换语义决策为物理流节点
        commitRoute(trace, decision, context);
    }

    /**
     * 带韧性机制（Resilience）的大模型调用
     * <p>应对网络抖动或模型临时不可用的场景，确保团队调度的稳定性。</p>
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
                    throw new RuntimeException("Supervisor dispatch failed after max retries", e);
                }

                // 指数退避策略 (Retrying with Exponential Backoff)
                try {
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
     * 多级解析引擎：解析 LLM 原始决策文本并执行路由重定向
     * <p>按优先级顺序：协议定制路由 > 拦截器干预 > Agent 名称模糊匹配 > 终结符识别。</p>
     */
    private void commitRoute(TeamTrace trace, String decision, FlowContext context) {
        if (Assert.isEmpty(decision)) {
            trace.setRoute(Agent.ID_END);
            return;
        }

        // 优先级 1：协议特定格式解析（如自定义 XML 标签或 JSON 路径）
        String protoRoute = config.getProtocol().resolveSupervisorRoute(context, trace, decision);
        if (Assert.isNotEmpty(protoRoute)) {
            routeTo(context, trace, protoRoute);
            return;
        }

        // 优先级 2：协议通用拦截
        if (config.getProtocol().shouldSupervisorRoute(context, trace, decision)) {
            return;
        }

        // 优先级 3：基于当前团队成员名录进行名称解析
        if (matchAgentRoute(context, trace, decision)) {
            return;
        }

        // 优先级 4：识别任务终结标识（Finish Marker）
        String finishMarker = config.getFinishMarker();
        String finishRegex = "(?i).*?(\\Q" + finishMarker + "\\E)(.*)";
        Pattern pattern = Pattern.compile(finishRegex, Pattern.DOTALL);
        Matcher matcher = pattern.matcher(decision);

        if (matcher.find()) {
            trace.setRoute(Agent.ID_END);
            String finalAnswer = matcher.group(2).trim();
            // 提取终结符后的文本作为“谢幕词”或最终结论
            trace.setFinalAnswer(finalAnswer.isEmpty() ? "Task completed." : finalAnswer);
            return;
        }

        // 兜底：无法识别的指令直接终止，防止无效循环消耗 Token
        trace.setRoute(Agent.ID_END);
        LOG.warn("TeamAgent [{}] could not resolve route from decision: {}", config.getName(), decision);
    }

    /**
     * 智能语义匹配：识别决策内容中提到的 Agent 身份
     * <p>采用降序贪婪匹配策略，优先匹配长名称（如 SeniorCoder），防止短名（Coder）误触发。</p>
     */
    private boolean matchAgentRoute(FlowContext context, TeamTrace trace, String text) {
        // 全量精准匹配
        Agent agent = config.getAgentMap().get(text);
        if (agent != null) {
            routeTo(context, trace, agent.name());
            return true;
        }

        // 降序模糊匹配：利用单词边界识别 Agent 名称
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
     * 执行路由分发并通知协议，完成切换入场
     */
    private void routeTo(FlowContext context, TeamTrace trace, String targetName) {
        trace.setRoute(targetName);
        config.getProtocol().onSupervisorRouting(context, trace, targetName);
        if (LOG.isDebugEnabled()) {
            LOG.debug("TeamAgent [{}] supervisor routed to: [{}]", config.getName(), targetName);
        }
    }

    /**
     * 异常收敛：确保任何非预期错误不会导致流引擎挂死
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