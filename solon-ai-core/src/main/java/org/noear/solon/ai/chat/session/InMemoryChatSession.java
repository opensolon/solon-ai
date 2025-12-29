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
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.SystemMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.lang.Preview;

import java.util.*;

/**
 * 内存聊天会话
 *
 * @author noear
 * @since 3.4
 */
@Preview("3.4")
public class InMemoryChatSession implements ChatSession {
    protected final String sessionId;
    protected final List<ChatMessage> messages = new ArrayList<>();
    protected final int maxMessages;

    public InMemoryChatSession(String sessionId){
        this(sessionId, null, null, 0);
    }

    public InMemoryChatSession(String sessionId, int maxMessages){
        this(sessionId, null, null, maxMessages);
    }

    public InMemoryChatSession(String sessionId, List<SystemMessage> systemMessages, List<ChatMessage> messages, int maxMessages) {
        if (sessionId == null) {
            this.sessionId = Utils.guid();
        } else {
            this.sessionId = sessionId;
        }

        if (systemMessages != null) {
            this.messages.addAll(systemMessages);
        }

        if (messages != null) {
            this.messages.addAll(messages);
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
            this.messages.addAll(messages);

            //处理最大消息数
            if (maxMessages > 0 && this.messages.size() > maxMessages) {
                //移除非SystemMessage
                removeNonSystemMessages(messages.size());
            }
        }
    }

    /**
     * 移除size个非SystemMessage
     * 当删除调用 ToolCall 的 AssistantMessage 时，需要删除后续对应的 ToolMessage，可能会导致实际删除的 size 大于传入的 size.
     */
    private void removeNonSystemMessages(int size) {
        Iterator<ChatMessage> iterator = messages.iterator();
        int removeNums = 0;

        while (iterator.hasNext() && removeNums < size) {
            ChatMessage message = iterator.next();
            if (!(message instanceof SystemMessage)) {
                iterator.remove();
                removeNums++;
                if (message instanceof AssistantMessage) {
                    List<ToolCall> toolCalls = ((AssistantMessage) message).getToolCalls();
                    // 存在 toolCall 调用的 AssistantMessage，需要删除后续对应的ToolMessage
                    if (Utils.isNotEmpty(toolCalls)) {
                        while (iterator.hasNext() && iterator.next() instanceof ToolMessage) {
                            iterator.remove();
                            removeNums++;
                        }
                    }
                }
            }
        }
    }

    @Override
    public boolean isEmpty() {
        return messages.isEmpty();
    }

    /**
     * 清空消息
     */
    @Override
    public void clear() {
        messages.clear();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String sessionId;
        private List<ChatMessage> messages;
        private List<SystemMessage> systemMessages;
        private int maxMessages;

        /**
         * 会话 id
         */
        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        /**
         * 系统消息
         */
        public Builder systemMessages(SystemMessage... systemMessages) {
            this.systemMessages = Arrays.asList(systemMessages);
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
            return new InMemoryChatSession(sessionId, systemMessages, messages, maxMessages);
        }
    }
}
