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
package org.noear.solon.ai.agent.react;

import org.noear.solon.ai.agent.AgentInterceptor;
import org.noear.solon.ai.agent.react.task.ToolExchanger;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.interceptor.ChatInterceptor;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;

import java.util.Collection;
import java.util.List;

/**
 * ReAct 智能体拦截器
 * <p>提供对智能体起止、模型推理、工具执行等全生命周期的监控与干预能力</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public interface ReActInterceptor extends AgentInterceptor, ChatInterceptor {

    /**
     * 智能体生命周期：开始执行前
     */
    default void onAgentStart(ReActTrace trace) {
    }

    /**
     * 推理节点：Reason 阶段开始前（在 systemPrompt 构建和消息组装之前触发）
     * <p>适合做上下文压缩、工作记忆窗口管理等预处理操作</p>
     */
    default void onReasonStart(ReActTrace trace, StringBuilder systemPromptBuf) {
    }

    /**
     * 推理节点：接收 LLM 返回的原始推理消息
     */
    default void onReasonEnd(ReActTrace trace, ChatResponse resp, AssistantMessage message, long durationMs) {
    }


    /**
     * 计划节点：接收 LLM 返回的原始推理消息
     */
    default void onPlan(ReActTrace trace, AssistantMessage message) {

    }

    /**
     * 思考节点：Reason 阶段完成后触发
     * <p>无论是否解析出有效的 thoughtContent，此方法都会被调用</p>
     *
     * @param trace            ReAct 追踪上下文
     * @param thoughtContent   提取后的思考内容（可能为空字符串）
     * @param assistantMessage 原始 LLM 响应消息（含 toolCalls、content、reasoning 等完整信息）
     */
    default void onThought(ReActTrace trace, String thoughtContent, AssistantMessage assistantMessage) {
    }

    /**
     * @since 4.0.4
     */
    default void onActionStart(ReActTrace trace, Collection<ToolExchanger> toolCalls) {

    }

    /**
     * 动作节点：调用功能工具 (Action) 前触发
     * <p>可用于权限控制、参数合法性预检</p>
     */
    default void onToolCallStart(ReActTrace trace, ToolExchanger toolExchanger) {
    }

    /**
     * 观察节点：工具执行完成后触发（100% 强闭环，放在 finally 块中）
     * <p>无论成功、失败、挂起、中断，此方法保证被调用</p>
     *
     * @param trace         ReAct 追踪上下文
     * @param toolExchanger 工具交换器（含 toolName、args、result）
     * @param observation   观察结果消息（成功时为工具输出，失败时为错误描述；挂起/中断时为空消息）
     * @param durationMs    工具执行耗时（毫秒）
     * @param error         执行异常（成功时为 null）
     */
    default void onToolCallEnd(ReActTrace trace, ToolExchanger toolExchanger,
                               @Nullable ChatMessage observation,
                               @Nullable Throwable error,
                               long durationMs) {
    }

    /**
     * @since 4.0.4
     */
    default void onActionEnd(ReActTrace trace) {

    }

    /**
     * 智能体生命周期：任务结束（成功或异常中止）时触发
     */
    default void onAgentEnd(ReActTrace trace) {
    }

    //-------------------

    /**
     * 动作节点：调用功能工具 (Action) 前触发
     * <p>可用于权限控制、参数合法性预检</p>
     *
     * @deprecated 4.0.4 {@link #onToolCallStart(ReActTrace, ToolExchanger)}
     */
    @Deprecated
    default void onAction(ReActTrace trace, ToolExchanger toolExchanger) {
    }

    /**
     * 观察节点：工具执行完成后触发（100% 强闭环，放在 finally 块中）
     * <p>无论成功、失败、挂起、中断，此方法保证被调用</p>
     *
     * @param trace         ReAct 追踪上下文
     * @param toolExchanger 工具交换器（含 toolName、args、result）
     * @param observation   观察结果消息（成功时为工具输出，失败时为错误描述；挂起/中断时为空消息）
     * @param durationMs    工具执行耗时（毫秒）
     * @param error         执行异常（成功时为 null）
     * @deprecated 4.0.4 {@link #onToolCallEnd(ReActTrace, ToolExchanger, ChatMessage, Throwable, long)}
     */
    @Deprecated
    default void onObservation(ReActTrace trace, ToolExchanger toolExchanger,
                               @Nullable ChatMessage observation,
                               @Nullable Throwable error,
                               long durationMs) {
    }
}