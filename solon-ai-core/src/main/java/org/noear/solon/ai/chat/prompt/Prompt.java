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
    private final Map<String, Object> attrs = new HashMap<>();
    private final List<ChatMessage> messages = new ArrayList<>();

    @Override
    public Map<String, Object> attrs() {
        return attrs;
    }

    /**
     * 设置元信息
     */
    public Prompt attrPut(String name, Object value) {
        attrs.put(name, value);
        return this;
    }

    /**
     * 设置元信息
     */
    public Prompt attrPut(Map<String,Object> map) {
        if (Assert.isNotEmpty(map)) {
            attrs.putAll(map);
        }

        return this;
    }


    @Override
    public List<ChatMessage> getMessages() {
        return messages;
    }

    @Override
    public int getMessagesSize() {
        return messages.size();
    }

    @Override
    public ChatMessage getFirstMessage() {
        return messages.isEmpty() ? null : messages.get(0);
    }

    @Override
    public ChatMessage getLastMessage() {
        return messages.isEmpty() ? null : messages.get(messages.size() - 1);
    }

    public Prompt addMessage(String... msgs) {
        for (String m : msgs) {
            if (Assert.isNotEmpty(m)) {
                this.messages.add(ChatMessage.ofUser(m));
            }
        }
        return this;
    }

    public Prompt addMessage(ChatMessage... msgs) {
        for (ChatMessage m : msgs) {
            this.messages.add(m);
        }
        return this;
    }

    public Prompt addMessage(Collection<ChatMessage> msgs) {
        this.messages.addAll(msgs);
        return this;
    }

    private String userContent;

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

    private String systemContent;

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