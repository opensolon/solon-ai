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
package org.noear.solon.ai.chat;

import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.lang.NonSerializable;
import org.noear.solon.lang.Preview;

import java.io.*;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * 聊天会话接口
 *
 * <p>用于管理对话过程中的消息序列。设计上支持易持久化特性，
 * 是构建聊天机器人或 AI 交互应用的基础存储单元。</p>
 *
 * @author noear
 * @since 3.1
 */
@Preview("3.1")
public interface ChatSession extends NonSerializable {
    /**
     * 获取会话id
     */
    String getSessionId();

    /**
     * 获取消息
     */
    List<ChatMessage> getMessages();

    /**
     * 获取最近消息
     *
     * @param windowSize 窗口大小
     */
    default List<ChatMessage> getLatestMessages(int windowSize){
        List<ChatMessage> all = getMessages();
        if (all == null || all.isEmpty()) {
            return Collections.emptyList();
        }

        int size = all.size();
        if (size <= windowSize || windowSize <= 0) {
            return all;
        } else {
            // 返回最后 N 条
            return all.subList(size - windowSize, size);
        }
    }

    /**
     * 添加消息
     */
    default void addMessage(String userMessage) {
        addMessage(ChatMessage.ofUser(userMessage));
    }

    /**
     * 添加消息
     */
    default void addMessage(ChatMessage... messages) {
        addMessage(Arrays.asList(messages));
    }

    /**
     * 添加消息
     */
    default void addMessage(Prompt prompt) {
        addMessage(prompt.getMessages());
    }

    /**
     * 添加消息
     */
    void addMessage(Collection<? extends ChatMessage> messages);

    /**
     * 是否为空
     */
    boolean isEmpty();

    /**
     * 清空消息
     */
    void clear();


    /// //////////////////////////////////////

    /**
     * 转为 ndjson
     *
     * @deprecated 3.9.1 {@link ChatMessage#toNdjson(Collection)}
     */
    @Deprecated
    default String toNdjson() throws IOException {
        return ChatMessage.toNdjson(getMessages());
    }

    /**
     * 转为 ndjson
     *
     * @deprecated 3.9.1 {@link ChatMessage#toNdjson(Collection, OutputStream)}
     */
    @Deprecated
    default void toNdjson(OutputStream out) throws IOException {
        ChatMessage.toNdjson(getMessages(), out);
    }

    /**
     * 加载 ndjson
     *
     * @deprecated 3.9.1 {@link ChatMessage#fromNdjson(String, Consumer)}
     */
    @Deprecated
    default void loadNdjson(String ndjson) throws IOException {
        ChatMessage.fromNdjson(ndjson, this::addMessage);
    }

    /**
     * 加载 ndjson
     *
     * @deprecated 3.9.1 {@link ChatMessage#fromNdjson(InputStream, Consumer)}
     */
    @Deprecated
    default void loadNdjson(InputStream ins) throws IOException {
        ChatMessage.fromNdjson(ins, this::addMessage);
    }
}