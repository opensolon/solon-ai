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
import org.noear.solon.ai.chat.message.*;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.lang.Preview;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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

    private final transient Map<String, Object> attrs = new ConcurrentHashMap<>();

    public InMemoryChatSession(String sessionId) {
        this(sessionId, 50);
    }

    public InMemoryChatSession(String sessionId, int maxMessages) {
        if (sessionId == null) {
            this.sessionId = Utils.guid();
        } else {
            this.sessionId = sessionId;
        }

        this.maxMessages = maxMessages;
    }

    /**
     * @deprecated 3.9.1 不建议在会话里放系统消息
     *
     */
    @Deprecated
    public InMemoryChatSession(String sessionId, List<SystemMessage> systemMessages, List<ChatMessage> messages, int maxMessages) {
        this(sessionId, maxMessages);

        if (systemMessages != null) {
            this.messages.addAll(systemMessages);
        }

        if (messages != null) {
            this.messages.addAll(messages);
        }
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
     * 获取最近消息（带安全截断）
     *
     * <p>从全量消息列表中截取最近 windowSize 条消息，并保证截断后的起始位置是合法的：</p>
     * <ul>
     *   <li>优先以 UserMessage 作为起始点，保证对话轮次的完整性</li>
     *   <li>不会以 ToolMessage 作为起始点（ToolMessage 必须跟在带 ToolCall 的 AssistantMessage 之后）</li>
     *   <li>如果起始点落在带 ToolCall 的 AssistantMessage 之前，会回退到包含该 AssistantMessage</li>
     * </ul>
     *
     * @param windowSize 窗口大小
     */
    @Override
    public List<ChatMessage> getLatestMessages(int windowSize) {
        List<ChatMessage> all = getMessages();
        if (all == null || all.isEmpty()) return Collections.emptyList();

        int size = all.size();
        if (size <= windowSize || windowSize <= 0) return all;

        // 1. 初步截断点
        int start = size - windowSize;

        // 2. 向前寻找最近的一个 UserMessage 作为安全的起始点
        // 这样能保证对话逻辑的完整性，且绝对不会出现 Tool/Assistant 错位
        for (int i = start; i >= 0; i--) {
            if (all.get(i) instanceof UserMessage) {
                start = i;
                break;
            }
        }

        // 3. 兜底：如果往前找了一圈没找到 User（比如全是 Tool/Assistant）
        // 那就只能向后找第一个 User
        if (!(all.get(start) instanceof UserMessage)) {
            for (int i = start; i < size; i++) {
                if (all.get(i) instanceof UserMessage) {
                    start = i;
                    break;
                }
            }
        }

        // 4. 跳过起始位置的 ToolMessage（ToolMessage 不能独立出现）
        while (start < size && (all.get(start) instanceof ToolMessage)) {
            start++;
        }

        // 5. 回溯检查：如果 start 前面是带 ToolCall 的 AssistantMessage，
        //    需要把该 AssistantMessage 也包含进来，以保证 ToolCall 链完整
        //    场景：[Assistant(toolCalls), ToolMessage, ToolMessage, start=UserMessage, ...]
        //    -> 回退后：[Assistant(toolCalls), ToolMessage, ToolMessage, UserMessage, ...]
        while (start > 0) {
            ChatMessage prev = all.get(start - 1);
            if (prev instanceof ToolMessage) {
                // 向前跳过连续的 ToolMessage，找到对应的带 ToolCall 的 AssistantMessage
                start--;
            } else if (prev instanceof AssistantMessage) {
                AssistantMessage am = (AssistantMessage) prev;
                if (am.isToolCalls()) {
                    // 找到了带 ToolCall 的 AssistantMessage，把它包含进来
                    start--;
                }
                break;
            } else {
                break;
            }
        }

        return all.subList(start, size);
    }


    /**
     * 移除最近消息（带安全删除）
     *
     * <p>从消息列表末尾向前删除 windowSize 条消息，并保证删除后消息链路的完整性：</p>
     * <ul>
     *   <li>如果删除的是带 ToolCall 的 AssistantMessage，会顺带删除其后续对应的 ToolMessage</li>
     *   <li>如果删除的是 ToolMessage，会检查前面是否有孤立的带 ToolCall 的 AssistantMessage 也一并删除</li>
     * </ul>
     *
     * @param windowSize 要删除的消息数量
     */
    @Override
    public void removeLatestMessage(int windowSize) {
        if (windowSize <= 0 || messages.isEmpty()) return;

        for (int i = 0; i < windowSize && !messages.isEmpty(); i++) {
            int lastIndex = messages.size() - 1;
            ChatMessage last = messages.get(lastIndex);

            if (last instanceof AssistantMessage) {
                AssistantMessage am = (AssistantMessage) last;
                if (am.isToolCalls()) {
                    // 删除带 toolCalls 的 AssistantMessage
                    messages.remove(lastIndex);
                    // 顺带删除其后续紧邻的 ToolMessage
                    while (!messages.isEmpty() && messages.get(messages.size() - 1) instanceof ToolMessage) {
                        messages.remove(messages.size() - 1);
                    }
                } else {
                    // 普通 AssistantMessage，直接删除
                    messages.remove(lastIndex);
                }
            } else if (last instanceof ToolMessage) {
                // 删除当前 ToolMessage
                messages.remove(lastIndex);
                // 检查前面是否还有属于同一组 ToolCall 的 ToolMessage
                while (!messages.isEmpty() && messages.get(messages.size() - 1) instanceof ToolMessage) {
                    messages.remove(messages.size() - 1);
                }
                // 再检查前面是否有对应的带 toolCalls 的 AssistantMessage
                if (!messages.isEmpty() && messages.get(messages.size() - 1) instanceof AssistantMessage) {
                    AssistantMessage am = (AssistantMessage) messages.get(messages.size() - 1);
                    if (am.isToolCalls()) {
                        messages.remove(messages.size() - 1);
                    }
                }
            } else {
                // UserMessage / SystemMessage 等直接删除
                messages.remove(lastIndex);
            }
        }
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
    protected void removeNonSystemMessages(int size) {
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

    /// /////////////////

    @Override
    public Map<String, Object> attrs() {
        return attrs;
    }

    /// /////////////////

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
         *
         * @deprecated 3.9.1 不建议在会话里放系统消息
         */
        @Deprecated
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
            InMemoryChatSession session = new InMemoryChatSession(sessionId, maxMessages);
            session.addMessage(systemMessages);
            session.addMessage(messages);
            return session;
        }
    }
}