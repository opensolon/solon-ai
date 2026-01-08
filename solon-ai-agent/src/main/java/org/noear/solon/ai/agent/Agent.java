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
package org.noear.solon.ai.agent;

import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.NamedTaskComponent;
import org.noear.solon.flow.Node;
import org.noear.solon.lang.Preview;

/**
 * 智能体接口
 *
 * <p>定义了 AI 智能体的核心行为，包括身份标识、能力评估以及任务执行。
 * 支持作为独立组件调用，或集成到 Solon Flow 工作流中运行。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public interface Agent extends NamedTaskComponent {
    /**
     * 智能体名称
     */
    String name();

    /**
     * 获取智能体能力描述
     * <p>用于在多智能体协作（如 Supervisor 模式）中作为任务分配的参考依据。</p>
     */
    String description();

    /**
     * 针对当前任务进行初步评估或竞标
     * <p>用于合同网协议（CNP）或决策场景，根据任务内容返回该智能体的匹配度评估或执行方案。</p>
     *
     * @param session 当前会话
     * @param prompt  任务提示词
     * @return 评估结果（默认返回 {@link #description()}）
     */
    default String estimate(AgentSession session, Prompt prompt) {
        // 默认实现：如果 Agent 不支持评估，则返回静态描述
        return description();
    }

    /**
     * 执行任务（基于已有会话状态）
     *
     * @param session 当前会话
     * @return 执行后的响应消息
     */
    default AssistantMessage call(AgentSession session) throws Throwable {
        return call(null, session);
    }

    /**
     * 执行任务（显式指定提示词）
     *
     * @param prompt  任务提示词
     * @param session 当前会话
     * @return 执行后的响应消息
     */
    AssistantMessage call(Prompt prompt, AgentSession session) throws Throwable;

    /**
     * 作为 Solon Flow 任务节点运行
     * <p>实现与 Solon Flow 的无缝整合，支持自动上下文管理、协作痕迹（Trace）记录及历史注入。</p>
     *
     * @param context 流上下文
     * @param node    当前流程节点
     */
    default void run(FlowContext context, Node node) throws Throwable {
        AgentSession session = context.getAs(ID_SESSION);
        if (session == null) {
            session = new InMemoryAgentSession("tmp");
            context.put(ID_SESSION, session);
        }

        String traceKey = context.getAs(KEY_CURRENT_TRACE_KEY);
        TeamTrace trace = (traceKey != null) ? context.getAs(traceKey) : null;
        Prompt originalPrompt = (trace != null) ? trace.getPrompt() : null;

        Prompt effectivePrompt = originalPrompt;
        if (trace != null && trace.getStepCount() > 0) {
            String fullHistory = trace.getFormattedHistory();
            String newContent = "Current Task: " + originalPrompt.getUserContent() +
                    "\n\nCollaboration Progress so far:\n" + fullHistory +
                    "\n\nPlease continue based on the progress above.";
            effectivePrompt = Prompt.of(newContent);
        }

        long start = System.currentTimeMillis();
        AssistantMessage msg = call(effectivePrompt, session);
        long duration = System.currentTimeMillis() - start;

        String result = msg.getContent();
        if (result == null) {
            result = "";
        }

        if (trace != null) {
            String stepContent = result.trim().isEmpty() ?
                    "No valid output is produced" : result;
            trace.addStep(name(), stepContent, duration);
        }
    }

    static String KEY_CURRENT_TRACE_KEY = "_current_trace_key";

    static String ID_START = "start";
    static String ID_END = "end";
    static String ID_REASON = "reason";
    static String ID_ACTION = "action";

    static String ID_SYSTEM = "system";
    static String ID_SUPERVISOR = "supervisor";
    static String ID_BIDDING = "bidding";

    static String ID_SESSION = "SESSION";
}