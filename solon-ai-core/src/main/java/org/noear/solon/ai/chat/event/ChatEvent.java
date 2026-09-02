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
package org.noear.solon.ai.chat.event;

import org.noear.snack4.ONode;
import org.noear.solon.ai.AiEvent;
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.chat.ChatException;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.NonSerializable;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;

import java.util.Map;

/**
 * 聊天事件
 *
 * <p>一次模型响应过程中的一个语义事件。<b>不可变</b>，可安全缓存与跨线程传递。</p>
 *
 * <p>典型订阅方式（switch 在分组上以保持向前兼容）：</p>
 * <pre>{@code
 * chatModel.prompt("hello").stream().subscribe(e -> {
 *     switch (e.getGroup()) {
 *         case TEXT:      ui.appendText(e.getText());     break;
 *         case THINKING:  ui.appendThinking(e.getText()); break;
 *         case TOOL_CALL: ui.onToolCall(e);               break;
 *         case LIFECYCLE:
 *             if (e.is(ChatEventType.RESPONSE_END)) {
 *                 done(e.getResponse());
 *             }
 *             break;
 *         default: log.debug("{} {}", e.getRawType(), e.getRaw()); break;
 *     }
 * });
 * }</pre>
 *
 * <p>只关心某一类时直接用 Reactor 操作符，无需专用 API：</p>
 * <pre>{@code
 * chatModel.prompt("hello").stream()
 *          .filter(ChatEvent::isDelta)
 *          .map(ChatEvent::getText)
 *          .subscribe(System.out::print);
 * }</pre>
 *
 * @author noear
 * @since 4.1
 */
@Preview("4.1")
public interface ChatEvent extends AiEvent, NonSerializable {
    /**
     * 事件类型
     */
    ChatEventType getType();

    /**
     * 事件分组（等价于 {@code getType().getGroup()}）
     */
    ChatEventGroup getGroup();

    /**
     * 事件阶段（等价于 {@code getType().getPhase()}）
     */
    ChatEventPhase getPhase();

    /**
     * 方言原始事件名（如 {@code response.output_text.delta}、{@code content_block_delta}）
     */
    @Nullable
    String getRawType();

    /**
     * 子类型。服务端工具的类别（{@code web_search} / {@code code_interpreter} / {@code mcp_call}
     * / {@code google_search}），或其它需要细分而不宜膨胀枚举的场景
     */
    @Nullable
    String getSubType();

    /**
     * 一次模型响应的标识（同一次 {@code stream()} 内所有事件相同）
     */
    @Nullable
    String getResponseId();

    /**
     * 供应商响应标识（如 {@code resp_xxx} / {@code msg_xxx}）
     *
     * <p>由方言从响应帧中提取（如 {@code response.id}、{@code message_start.message.id}），
     * 服务未提供则为 null。用于关联供应商侧日志排障。</p>
     */
    @Nullable
    String getProviderResponseId();

    /**
     * 步序号（自动工具调用时每轮模型调用递增，从 0 开始）
     */
    int getStep();

    /**
     * 输出项 / 内容块标识（如 reasoning item id、content block id）
     */
    @Nullable
    String getItemId();

    /**
     * 工具调用标识
     */
    @Nullable
    String getToolCallId();

    /**
     * 内容块 / 输出项序号，未知为 -1
     */
    int getIndex();

    /**
     * 文本负载。按事件类型分别表示：正文增量、思考增量、签名值、拒答增量等
     */
    @Nullable
    String getText();

    /**
     * 是否携带非空文本负载（正文 / 思考 / 签名 / 拒答增量等）
     */
    default boolean hasText() {
        return Assert.isNotEmpty(getText());
    }

    /**
     * 工具调用负载
     */
    @Nullable
    ToolCall getToolCall();

    /**
     * 内容块负载（媒体、引用）
     */
    @Nullable
    ContentBlock getBlock();

    /**
     * 用量负载
     */
    @Nullable
    AiUsage getUsage();

    /**
     * 错误负载
     */
    @Nullable
    ChatException getError();

    /**
     * 聚合响应。仅 {@link ChatEventType#RESPONSE_END}（全流终态）与
     * {@link ChatEventType#STEP_END}（分步终态）携带
     */
    @Nullable
    ChatResponse getResponse();

    /**
     * 原始 JSON 兜底。任何未建模字段都可从此取得（可能为空节点，不为 null）
     */
    ONode getRaw();

    /**
     * 附加属性（只读）
     */
    Map<String, Object> getAttrs();

    /**
     * 取附加属性
     */
    @Nullable
    <T> T attrAs(String name);

    /// //////////////////////////

    /**
     * 是否为指定类型之一
     */
    default boolean is(ChatEventType... types) {
        for (ChatEventType type : types) {
            if (getType() == type) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否属于指定分组之一
     */
    default boolean isGroup(ChatEventGroup... groups) {
        for (ChatEventGroup group : groups) {
            if (getGroup() == group) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否为增量事件
     */
    default boolean isDelta() {
        return getType().isDelta();
    }

    /**
     * 是否为终止事件
     */
    default boolean isTerminal() {
        return getType().isTerminal();
    }
}
