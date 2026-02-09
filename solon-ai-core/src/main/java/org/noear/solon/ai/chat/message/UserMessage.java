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
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;

import java.util.ArrayList;
import java.util.List;

/**
 * 聊天用户消息
 *
 * @author noear
 * @since 3.1
 */
@Preview("3.1")
public class UserMessage extends ChatMessageBase<UserMessage> {
    private final ChatRole role = ChatRole.USER;
    private final List<ContentBlock> blocks = new ArrayList<>();
    private String content;

    public UserMessage() {
        //用于序列化
    }

    public UserMessage(String content) {
        this(content, null);
    }

    public UserMessage(String content, List<ContentBlock> blocks) {
        this.content = content;

        if (Utils.isNotEmpty(content)) {
            this.blocks.add(TextBlock.of(false, content));
        }

        if (Utils.isNotEmpty(blocks)) {
            this.blocks.addAll(blocks);
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

        buf.append("}");

        return buf.toString();
    }
}