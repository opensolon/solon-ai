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
package org.noear.solon.ai.chat.session;

import org.noear.solon.Utils;
import org.noear.solon.ai.chat.ChatSession;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.SystemMessage;
import org.noear.solon.lang.Preview;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

/**
 * 内存聊天会话
 *
 * @author noear
 * @since 3.4
 */
@Preview("3.4")
public class InMemoryChatSession implements ChatSession {
    protected final String sessionId;
    protected final List<ChatMessage> messages;
    protected final int maxMessages;

    protected InMemoryChatSession(String sessionId, List<ChatMessage> messages, int maxMessages) {
        if (sessionId == null) {
            this.sessionId = Utils.guid();
        } else {
            this.sessionId = sessionId;
        }

        if (messages == null) {
            this.messages = new ArrayList<>();
        } else {
            this.messages = messages;
        }

        this.maxMessages = maxMessages;
    }


    /**
     * 获取会话id
     */
    @Override
    public String getSessionId() {
        return sessionId;
    }

    /**
     * 获取最大消息数
     */
    public int getMaxMessages() {
        return maxMessages;
    }

    /**
     * 获取所有消息
     */
    @Override
    public List<ChatMessage> getMessages() {
        return messages;
    }

    /**
     * 添加消息
     */
    @Override
    public void addMessage(Collection<? extends ChatMessage> messages) {
        if (Utils.isNotEmpty(messages)) {
            for (ChatMessage m : messages) {
                this.messages.add(m);

                if (this.messages.size() > maxMessages) {
                    //移除第一个非SystemMessage
                    removeNonSystemMessages();
                }
            }
        }
    }

    /**
     * 移除第一个非SystemMessage,保留SystemMessage
     */
    private void removeNonSystemMessages() {

        Iterator<ChatMessage> iterator = this.messages.iterator();
        boolean removed = false;
        while (iterator.hasNext() && !removed) {
            ChatMessage message = iterator.next();
            if (!(message instanceof SystemMessage)) {
                iterator.remove();
                removed = true;
            }
        }
    }

    /**
     * 清空消息
     */
    @Override
    public void clear() {
        this.messages.clear();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String sessionId;
        private List<ChatMessage> messages;
        private int maxMessages;

        /**
         * 会话 id
         */
        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        /**
         * 聊天消息
         */
        public Builder messages(List<ChatMessage> messages) {
            this.messages = messages;
            return this;
        }

        /**
         * 最大消息数
         */
        public Builder maxMessages(int maxMessages) {
            this.maxMessages = maxMessages;
            return this;
        }

        /**
         * 构建
         */
        public InMemoryChatSession build() {
            return new InMemoryChatSession(sessionId, messages, maxMessages);
        }
    }
}