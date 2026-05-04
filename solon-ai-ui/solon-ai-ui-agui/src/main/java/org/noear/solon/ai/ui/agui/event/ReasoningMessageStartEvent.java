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
package org.noear.solon.ai.ui.agui.event;

import org.jspecify.annotations.NonNull;
import org.noear.solon.ai.ui.agui.EventType;

/**
 * AG-UI 推理消息开始事件，开始一个新的流式推理消息
 *
 * @author shaoerkuai
 * @since 3.10.5
 * @see <a href="https://docs.ag-ui.com/concepts/reasoning#reasoning-events">AG-UI ReasoningMessageStart</a>
 */
public class ReasoningMessageStartEvent extends Event {
    /** 消息标识，第一个事件必须有 */
    @NonNull
    private String messageId = "";
    /** 消息角色（固定为 "reasoning"） */
    private String role;

    public ReasoningMessageStartEvent(@NonNull String messageId) {
        super(EventType.REASONING_MESSAGE_START);
        this.messageId = messageId;
    }

    public @NonNull String getMessageId() {
        return messageId;
    }

    public void setMessageId(@NonNull String messageId) {
        this.messageId = messageId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }
}
