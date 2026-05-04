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

import org.noear.solon.ai.ui.agui.EventType;

/**
 * AG-UI 文本消息分块事件，便捷事件可自动展开为 Start → Content → End 序列
 *
 * @author shaoerkuai
 * @since 3.10.5
 * @see <a href="https://docs.ag-ui.com/concepts/events#textmessagechunk">AG-UI TextMessageChunk</a>
 */
public class TextMessageChunkEvent extends Event {
    /** 消息标识 */
    private String messageId;
    /** 消息角色 */
    private String role;
    /** 增量文本内容 */
    private String delta;

    public TextMessageChunkEvent() {
        super(EventType.TEXT_MESSAGE_CHUNK);
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getDelta() {
        return delta;
    }

    public void setDelta(String delta) {
        this.delta = delta;
    }
}
