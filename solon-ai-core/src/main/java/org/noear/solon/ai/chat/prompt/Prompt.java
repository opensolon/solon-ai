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
package org.noear.solon.ai.chat.prompt;

import org.noear.solon.ai.chat.message.ChatMessage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 提示语
 *
 * @author noear
 * @since 3.2
 */
public class Prompt implements ChatPrompt {
    private final List<ChatMessage> messageList = new ArrayList<>();

    @Override
    public List<ChatMessage> getMessages() {
        return messageList;
    }

    public Prompt addMessage(ChatMessage... messages) {
        for (ChatMessage m : messages) {
            messageList.add(m);
        }
        return this;
    }

    public Prompt addMessage(Collection<ChatMessage> messages) {
        messageList.addAll(messages);
        return this;
    }
}