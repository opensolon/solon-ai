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
import org.noear.solon.ai.media.Text;
import org.noear.solon.core.util.Assert;
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
    private String content;
    private List<AiMedia> medias = new ArrayList<>();

    public UserMessage() {
        //用于序列化
    }

    public UserMessage(String content) {
        this(content, null);
    }

    public UserMessage(String content, List<AiMedia> medias) {
        this.content = content;

        if (Utils.isNotEmpty(content)) {
            this.medias.add(Text.of(false, content));
        }

        if (Utils.isNotEmpty(medias)) {
            this.medias.addAll(medias);
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
     * 内容
     */
    @Override
    public String getContent() {
        return content;
    }

    /**
     * 媒体集合
     */
    @Nullable
    public List<AiMedia> getMedias() {
        return medias;
    }

    public boolean hasMedias() {
        if (Assert.isEmpty(content)) {
            return medias.size() > 0;
        } else {
            return medias.size() > 1;
        }
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("{");

        buf.append("role=").append(getRole().name().toLowerCase());

        if (content != null) {
            buf.append(", content='").append(content).append('\'');
        }

        if (Utils.isNotEmpty(metadata)) {
            buf.append(", metadata=").append(metadata);
        }

        if (Utils.isNotEmpty(medias)) {
            buf.append(", medias=").append(medias);
        }

        buf.append("}");

        return buf.toString();
    }
}