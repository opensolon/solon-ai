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
package org.noear.solon.ai.chat;

import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.event.ChatEvent;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;

import java.util.List;

/**
 * 聊天响应（模型调用的结果，不可变）
 *
 * <p><b>取值只有一个入口</b>：{@link #getMessage()}。无论来自 {@code call()} 还是来自
 * {@code stream()} 的 {@code RESPONSE_END} / {@code STEP_END}，它给出的都是该响应（或该步）的
 * 完整终态；{@link #getText()} / {@link #getThinking()} / {@link #getToolCalls()} /
 * {@link #getBlocks()} 都是它的投影。中间帧（TEXT_DELTA 等）携带的则是当帧分片快照
 * （{@code getMessage()} 为该帧增量），非完整聚合。</p>
 *
 * <p>4.1 之前本类型同时承担三个角色——用户结果对象、方言的可变累积器与协议状态袋、
 * 请求上下文载体。4.1 起后两个角色拆到框架内部的 {@code ChatAccumulator}，本接口只保留
 * 结果语义；{@code getConfig} / {@code isStream} / {@code isFinished} / {@code getChoices} /
 * {@code getAggregationXxx} 等非结果方法已移除（流式过程请订阅 {@code stream()} 事件）。</p>
 *
 * <p><b>一次响应只有一个结果</b>：4.1 取消了多候选（{@code n>1}）维度，与 Anthropic Messages、
 * OpenAI Responses 对齐——两者的接口里都没有候选列表（OpenAI 在设计 Responses 时主动去掉了
 * {@code n} 参数），Google GenAI 虽保留 {@code candidates} 但所有便利访问器与多轮会话都只取第一个。
 * 多候选与工具调用/多轮递归本身不兼容（无法决定拿哪个候选继续）。</p>
 *
 * @author noear
 * @since 3.1
 */
@Preview("3.1")
public interface ChatResponse {
    /**
     * 获取原始响应数据
     */
    @Nullable
    String getFrameRaw();

    /**
     * 是否为终态
     */
    boolean isTerminal();

    /**
     * 获取模型
     */
    String getModel();

    /**
     * 获取错误
     */
    @Nullable
    ChatException getError();

    /**
     * 获取消息（终态；流式下即完整聚合结果）
     */
    @Nullable
    AssistantMessage getMessage();

    /**
     * 是否为空（没有内容，也没有工具调用）
     */
    boolean isEmpty();

    /**
     * 是否有消息内容
     */
    boolean hasContent();

    /**
     * 获取消息原始内容
     */
    String getContent();

    /**
     * 获取文本
     */
    String getText();

    /**
     * 获取思考
     */
    String getThinking();

    /**
     * 获取工具调用（没有时为空集合，不为 null）
     *
     * @since 4.1
     */
    List<ToolCall> getToolCalls();

    /**
     * 获取内容块（多模态；没有时为空集合，不为 null）
     *
     * @since 4.1
     */
    List<ContentBlock> getBlocks();

    /**
     * 获取完成原因（已归一化：工具调用为 {@code "tool"}、正常结束为 {@code "stop"}，
     * 其余如 {@code "length"} / {@code "content_filter"} 原样透传）
     *
     * @since 4.1
     */
    String getFinishReason();

    /**
     * 获取使用情况（完成时，才会有使用情况）
     */
    @Nullable
    AiUsage getUsage();

    /**
     * 本次响应的语义事件（仅非流式）
     *
     * <p>非流式 {@code call()} 没有事件流可供投递，但方言同样会解析出引用、服务端工具结果、
     * 思考签名、拒答等语义——这些信息并非流式独有，不能静默丢弃。因此非流式路径改为把事件
     * 收集到结果上。</p>
     *
     * <p>流式路径下事件由 {@code stream()} 直接投递，此处为空列表（不会为 null）。
     * 自动工具调用产生多轮时，与聚合消息一致——只携带末轮。</p>
     *
     * @since 4.1
     */
    List<ChatEvent> getEvents();
}
