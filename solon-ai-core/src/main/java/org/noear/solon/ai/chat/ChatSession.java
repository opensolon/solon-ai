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
import org.noear.solon.ai.chat.prompt.ChatPrompt;
import org.noear.solon.lang.NonSerializable;
import org.noear.solon.lang.Preview;

import java.io.*;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * 聊天会话（方便持久化）
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
     * 设置属性
     */
    default void attrSet(String name, Object value) {

    }

    /**
     * 获取属性
     */
    default <T> T attr(String name) {
        return null;
    }

    /**
     * 获取属性
     */
    default <T> T attrOrDefault(String name, T def) {
        T val = attr(name);
        return val == null ? def : val;
    }


    /**
     * 获取消息
     */
    List<ChatMessage> getMessages();

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
    default void addMessage(ChatPrompt prompt) {
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
     */
    default String toNdjson() throws IOException {
        return ChatMessage.toNdjson(getMessages());
    }

    /**
     * 转为 ndjson
     */
    default void toNdjson(OutputStream out) throws IOException {
        ChatMessage.toNdjson(getMessages(), out);
    }

    /**
     * 加载 ndjson
     */
    default void loadNdjson(String ndjson) throws IOException {
        ChatMessage.fromNdjson(ndjson, this::addMessage);
    }

    /**
     * 加载 ndjson
     */
    default void loadNdjson(InputStream ins) throws IOException {
        ChatMessage.fromNdjson(ins, this::addMessage);
    }
}