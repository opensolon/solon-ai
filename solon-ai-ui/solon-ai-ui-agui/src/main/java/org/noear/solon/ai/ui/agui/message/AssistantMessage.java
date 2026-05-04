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
import org.noear.solon.ai.ui.agui.tool.ToolCall;

import java.util.ArrayList;
import java.util.List;

/**
 * AG-UI 助手消息，表示 Agent 生成的响应，可包含工具调用
 *
 * @author shaoerkuai
 * @since 3.10.5
 * @see <a href="https://docs.ag-ui.com/concepts/messages#assistant-message">AG-UI AssistantMessage</a>
 */
public class AssistantMessage extends Message {
    /** 工具调用列表 */
    private List<ToolCall> toolCalls = new ArrayList<>();

    public AssistantMessage() {
        super();
    }

    public AssistantMessage(String id, String content, String name) {
        super(id, content, name);
    }

    @Override
    public Role getRole() {
        return Role.ASSISTANT;
    }

    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(List<ToolCall> toolCalls) {
        this.toolCalls = toolCalls;
    }

    public void addToolCall(ToolCall toolCall) {
        this.toolCalls.add(toolCall);
    }
}
