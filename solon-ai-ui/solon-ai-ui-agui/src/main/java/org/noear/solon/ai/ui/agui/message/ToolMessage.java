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
package org.noear.solon.ai.ui.agui.message;

import org.noear.solon.ai.ui.agui.Role;

/**
 * AG-UI 工具消息，携带工具执行的结果返回给 Agent
 *
 * @author shaoerkuai
 * @since 3.10.5
 * @see <a href="https://docs.ag-ui.com/concepts/messages#tool-message">AG-UI ToolMessage</a>
 */
public class ToolMessage extends Message {
    /** 关联的工具调用标识 */
    private String toolCallId;
    /** 错误信息（如果工具执行失败） */
    private String error;

    public ToolMessage() {
        super();
    }

    public ToolMessage(String id, String content, String name) {
        super(id, content, name);
    }

    @Override
    public Role getRole() {
        return Role.TOOL;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String toolCallId) {
        this.toolCallId = toolCallId;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}
