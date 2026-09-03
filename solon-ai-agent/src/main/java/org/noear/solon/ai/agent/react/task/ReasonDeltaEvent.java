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
package org.noear.solon.ai.agent.react.task;

import org.noear.solon.ai.agent.AbsAgentEvent;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.event.ChatEvent;
import org.noear.solon.ai.chat.event.ChatEventDefault;
import org.noear.solon.ai.chat.event.ChatEventGroup;
import org.noear.solon.ai.chat.event.ChatEventType;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;

import java.util.List;

/**
 * ReAct 思考流块
 *
 * <p>4.1 移除了 {@code isFinished()} 与 {@code isError()}：增量帧本质上永远不是终态，而 4.1 后
 * 本事件不再携带 {@link ChatResponse}，两个方法会恒返回 true（即每个增量都自称“已完成、已出错”）。
 * 请改用：完成信号取 {@code RunEndEvent}，异常判定取 {@code ReActTrace#isAbnormal()}。</p>
 *
 * @author noear
 * @since 3.9.1
 * @since 4.0.4
 */
@Preview("4.0.4")
public class ReasonDeltaEvent extends AbsAgentEvent {
    private final transient ReActTrace trace;
    private final transient ChatEvent chatEvent;
    private final String reasonId;

    public ReasonDeltaEvent(ReActTrace trace, AssistantMessage message) {
        super(trace.getRunId(), trace.getAgentName(), trace.getSession());
        this.trace = trace;
        this.chatEvent = ChatEventDefault.of(ChatEventType.TEXT_DELTA).text(message.getContent()).build();
        this.reasonId = trace.getCurrentReasonId();
    }

    public ReasonDeltaEvent(ReActTrace trace, ChatEvent event) {
        super(trace.getRunId(), trace.getAgentName(), trace.getSession());
        this.trace = trace;
        this.chatEvent = event;
        this.reasonId = trace.getCurrentReasonId();
    }

    public ReActTrace getTrace() {
        return trace;
    }

    public @Nullable ChatEvent getChatEvent() {
        return chatEvent;
    }

    public String getReasonId() {
        return reasonId;
    }

    /**
     * 是否为思考
     */
    public boolean isThinking() {
        return chatEvent.isGroup(ChatEventGroup.THINKING);
    }

    /**
     * 获取文本
     */
    @Override
    public String getText() {
        return chatEvent.getText();
    }
}