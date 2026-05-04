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
 * AG-UI 思考结束事件，标志 Agent 结束思考推理阶段
 *
 * @author shaoerkuai
 * @since 3.10.5
 * @deprecated 已被 Reasoning 事件取代
 */
@Deprecated
public class ThinkingEndEvent extends Event {
    /** 消息标识 */
    private String messageId;

    public ThinkingEndEvent() {
        super(EventType.THINKING_END);
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }
}
