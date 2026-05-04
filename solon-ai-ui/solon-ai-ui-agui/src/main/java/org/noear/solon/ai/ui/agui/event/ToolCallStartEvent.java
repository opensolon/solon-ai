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
 * AG-UI 工具调用开始事件，表示 Agent 正在调用工具并建立唯一调用标识
 *
 * @author shaoerkuai
 * @since 3.10.5
 * @see <a href="https://docs.ag-ui.com/concepts/events#toolcallstart">AG-UI ToolCallStart</a>
 */
public class ToolCallStartEvent extends Event {
    /** 工具调用标识 */
    private String toolCallId;
    /** 工具调用名称 */
    private String toolCallName;
    /** 父消息标识 */
    private String parentMessageId;

    public ToolCallStartEvent() {
        super(EventType.TOOL_CALL_START);
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String toolCallId) {
        this.toolCallId = toolCallId;
    }

    public String getToolCallName() {
        return toolCallName;
    }

    public void setToolCallName(String toolCallName) {
        this.toolCallName = toolCallName;
    }

    public String getParentMessageId() {
        return parentMessageId;
    }

    public void setParentMessageId(String parentMessageId) {
        this.parentMessageId = parentMessageId;
    }
}
