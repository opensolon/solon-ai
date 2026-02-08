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
package org.noear.solon.ai.chat.message;

import org.noear.solon.Utils;
import org.noear.solon.ai.AiMedia;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;

import java.util.List;

/**
 * 聊天工具消息
 * 
 * @author noear
 * @since 3.1
 */
@Preview("3.1")
public class ToolMessage extends ChatMessageBase<ToolMessage> {
    private final ChatRole role = ChatRole.TOOL;
    private ToolResult toolResult;
    private String name;
    private String toolCallId;
    private transient boolean returnDirect;

    public ToolMessage() {
        //用于序列化
    }

    public ToolMessage(ToolResult toolResult, String name, String toolCallId, boolean returnDirect) {
        this.toolResult = toolResult;
        this.name = name;
        this.toolCallId = toolCallId;
        this.returnDirect = returnDirect;
    }

    /**
     * 角色
     */
    @Override
    public ChatRole getRole() {
        return role;
    }

    @Override
    public String getContent() {
        return toolResult.getContent();
    }

    @Nullable
    public List<AiMedia> getMedias() {
        return toolResult.getMedias();
    }

    public boolean hasMedias(){
        return toolResult.hasMedias();
    }

    /**
     * 函数名
     */
    public String getName() {
        return name;
    }

    /**
     * 工具调用标识
     */
    public String getToolCallId() {
        return toolCallId;
    }

    /**
     * 是否直接返回给调用者
     */
    public boolean isReturnDirect() {
        return returnDirect;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("{");

        buf.append("role=").append(getRole().name().toLowerCase());

        if (Utils.isNotEmpty(toolResult.getContent())) {
            buf.append(", content='").append(toolResult.getContent()).append('\'');
        }

        if (Utils.isNotEmpty(metadata)) {
            buf.append(", metadata=").append(metadata);
        }

        if (Utils.isNotEmpty(toolResult.getMedias())) {
            buf.append(", medias=").append(toolResult.getMedias());
        }

        if (name != null) {
            buf.append(", name='").append(name).append('\'');
        }

        if (toolCallId != null) {
            buf.append(", tool_call_id=").append(toolCallId);
        }

        buf.append("}");

        return buf.toString();
    }
}
