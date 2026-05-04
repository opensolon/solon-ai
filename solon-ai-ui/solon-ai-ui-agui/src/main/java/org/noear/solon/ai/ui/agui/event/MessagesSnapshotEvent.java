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
import org.noear.solon.ai.ui.agui.message.Message;

import java.util.List;

/**
 * AG-UI 消息快照事件，提供当前对话中所有消息的完整历史
 *
 * @author shaoerkuai
 * @since 3.10.5
 * @see <a href="https://docs.ag-ui.com/concepts/events#messagessnapshot">AG-UI MessagesSnapshot</a>
 */
public class MessagesSnapshotEvent extends Event {
    /** 消息列表 */
    private List<Message> messages;

    public MessagesSnapshotEvent() {
        super(EventType.MESSAGES_SNAPSHOT);
    }

    public List<Message> getMessages() {
        return messages;
    }

    public void setMessages(List<Message> messages) {
        this.messages = messages;
    }
}
