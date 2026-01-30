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

import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.core.util.Assert;

import java.io.Serializable;
import java.util.*;

/**
 * 提示语实现
 *
 * @author noear
 * @since 3.2
 */
public class PromptImpl implements Prompt, Serializable {
    private final Map<String, Object> attrs = new LinkedHashMap<>();
    private final List<ChatMessage> messages = new ArrayList<>();

    private transient List<ChatMessage> messagesView;
    private transient String systemContent;
    private transient String userContent;

    @Override
    public Map<String, Object> attrs() {
        return attrs;
    }


    @Override
    public List<ChatMessage> getMessages() {
        if (messagesView == null) {
            messagesView = Collections.unmodifiableList(messages);
        }

        return messagesView;
    }

    @Override
    public ChatMessage getFirstMessage() {
        return messages.isEmpty() ? null : messages.get(0);
    }

    @Override
    public ChatMessage getLastMessage() {
        return messages.isEmpty() ? null : messages.get(messages.size() - 1);
    }

    @Override
    public AssistantMessage getLastAssistantMessage() {
        List<ChatMessage> currentMessages = this.messages;
        for (int i = currentMessages.size() - 1; i >= 0; i--) {
            try {
                ChatMessage msg = currentMessages.get(i);
                if (msg instanceof AssistantMessage) {
                    return (AssistantMessage) msg;
                }
            } catch (IndexOutOfBoundsException e) {
                return null;
            }
        }
        return null;
    }

    @Override
    public String getUserContent() {
        if (userContent == null) {
            // 从后往前找，取用户最新的意图
            for (int i = messages.size() - 1; i >= 0; i--) {
                ChatMessage m = messages.get(i);
                if (m.getRole() == ChatRole.USER && Assert.isNotEmpty(m.getContent())) {
                    userContent = m.getContent();
                    break;
                }
            }
        }

        return userContent;
    }

    @Override
    public String getSystemContent() {
        if (systemContent == null) {
            //从前往后找
            for (ChatMessage m : messages) {
                if (m.getRole() == ChatRole.SYSTEM) {
                    systemContent = m.getContent();
                    break;
                }
            }
        }

        return systemContent;
    }

    @Override
    public Prompt addMessage(String msg) {
        if (Assert.isNotEmpty(msg)) {
            this.messages.add(ChatMessage.ofUser(msg));
            this.userContent = null;
        }
        return this;
    }

    @Override
    public Prompt addMessage(ChatMessage... msgs) {
        for (ChatMessage msg : msgs) {
            if (msg != null) {
                this.messages.add(msg);
            }
        }

        return this;
    }

    @Override
    public Prompt addMessage(Collection<ChatMessage> msgs) {
        if (Assert.isNotEmpty(msgs)) {
            this.messages.addAll(msgs);
            this.userContent = null;
        }

        return this;
    }

    @Override
    public Prompt replaceMessages(Collection<ChatMessage> messages) {
        this.messages.clear();
        this.userContent = null;
        this.systemContent = null;

        if (messages != null) {
            this.messages.addAll(messages);
        }

        return this;
    }

    @Override
    public boolean isEmpty() {
        return messages.isEmpty();
    }

    @Override
    public int size() {
        return messages.size();
    }

    @Override
    public void clear() {
        messages.clear();
    }

    @Override
    public String toString() {
        return "Prompt{" +
                "attrs=" + attrs +
                "messages=" + messages +
                '}';
    }
}