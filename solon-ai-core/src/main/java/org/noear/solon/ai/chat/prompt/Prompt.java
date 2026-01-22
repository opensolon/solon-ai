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
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.core.util.Assert;

import java.io.Serializable;
import java.util.*;

/**
 * 提示语
 *
 * @author noear
 * @since 3.2
 */
public class Prompt implements ChatPrompt, Serializable {
    private final Map<String, Object> meta = new HashMap<>();
    private final List<ChatMessage> messageList = new ArrayList<>();

    @Override
    public Map<String, Object> meta() {
        return meta;
    }

    /**
     * 设置元信息
     */
    public Prompt metaPut(String name, Object value) {
        meta.put(name, value);
        return this;
    }

    /**
     * 设置元信息
     */
    public Prompt metaPut(Map<String,Object> map) {
        if (Assert.isNotEmpty(map)) {
            meta.putAll(map);
        }

        return this;
    }


    @Override
    public List<ChatMessage> getMessages() {
        return messageList;
    }

    @Override
    public int getMessagesSize() {
        return messageList.size();
    }

    @Override
    public ChatMessage getLastMessage() {
        return messageList.isEmpty() ? null : messageList.get(messageList.size() - 1);
    }

    public Prompt addMessage(String... messages) {
        for (String m : messages) {
            if (Assert.isNotEmpty(m)) {
                messageList.add(ChatMessage.ofUser(m));
            }
        }
        return this;
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

    private String userContent;

    @Override
    public String getUserMessageContent() {
        if (userContent == null) {
            for (ChatMessage m : messageList) {
                if (m.getRole() == ChatRole.USER) {
                    userContent = m.getContent();
                    break;
                }
            }
        }

        return userContent;
    }

    private String systemContent;

    @Override
    public String getSystemMessageContent() {
        if (systemContent == null) {
            for (ChatMessage m : messageList) {
                if (m.getRole() == ChatRole.SYSTEM) {
                    systemContent = m.getContent();
                    break;
                }
            }
        }

        return systemContent;
    }


    /**
     * 构建
     */
    public static Prompt of(Collection<ChatMessage> messages) {
        return new Prompt().addMessage(messages);
    }

    /**
     * 构建
     */
    public static Prompt of(ChatMessage... messages) {
        return new Prompt().addMessage(messages);
    }

    /**
     * 构建
     */
    public static Prompt of(String... messages) {
        return new Prompt().addMessage(messages);
    }
}