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
import org.noear.solon.ai.chat.media.ContentBlock;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.media.TextBlock;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;

import java.util.ArrayList;
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
    private final List<ContentBlock> blocks = new ArrayList<>();
    private String content;
    private String name;
    private String toolCallId;
    private transient boolean returnDirect;

    public ToolMessage() {
        //用于序列化
    }

    public ToolMessage(ToolResult toolResult, String name, String toolCallId, boolean returnDirect) {
        this.name = name;
        this.toolCallId = toolCallId;
        this.returnDirect = returnDirect;

        if (toolResult != null) {
            this.blocks.addAll(toolResult.getBlocks());
            this.content = toolResult.getContent();

            if (Assert.isNotEmpty(toolResult.getMetadata())) {
                this.getMetadata().putAll(toolResult.getMetadata());
            }
        }
    }

    /**
     * 角色
     */
    @Override
    public ChatRole getRole() {
        return role;
    }

    /**
     * 内容（兼容单模态LLM）
     */
    @Override
    public String getContent() {
        return content;
    }

    /**
     * 内容块集合（兼容多模态LLM）
     */
    @Nullable
    public List<ContentBlock> getBlocks() {
        return blocks;
    }

    /**
     * 是否为多模态
     */
    public boolean isMultiModal() {
        int size = blocks.size();
        if (size > 1) {
            return true;
        }

        if (size == 1) {
            return !(blocks.get(0) instanceof TextBlock);
        }

        return false;
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

        if (Utils.isNotEmpty(content)) {
            buf.append(", content='").append(content).append('\'');
        }

        if (isMultiModal()) {
            buf.append(", blocks=").append(blocks);
        }

        if (Utils.isNotEmpty(metadata)) {
            buf.append(", metadata=").append(metadata);
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
