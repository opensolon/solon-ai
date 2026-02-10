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
package org.noear.solon.ai.mcp.server.prompt;

import org.noear.solon.ai.chat.message.ChatMessage;

import java.util.Collection;
import java.util.List;
import java.util.ArrayList;

/**
 * 提示词获取结果
 *
 * @author noear
 * @since 3.9.2
 */
public class PromptResult {
    private final List<ChatMessage> messages = new ArrayList<>();

    public PromptResult() {
        //用于反序列化
    }

    public PromptResult(Collection<ChatMessage> messages) {
        this.messages.addAll(messages);
    }

    public int size() {
        return messages.size();
    }

    public List<ChatMessage> getMessages() {
        return messages;
    }

    @Override
    public String toString() {
        return messages.toString();
    }
}