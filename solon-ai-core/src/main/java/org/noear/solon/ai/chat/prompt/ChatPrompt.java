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
import org.noear.solon.lang.Preview;

import java.util.List;
import java.util.Map;

/**
 * 聊天提示语
 *
 * @author noear
 * @since 3.2
 */
@Preview("3.2")
public interface ChatPrompt {
    /**
     * 获取元信息
     */
    Map<String, Object> meta();

    /**
     * 获取元信息
     */
    default Object meta(String key) {
        return meta().get(key);
    }
    /**
     * 获取元信息
     */
    default <T> T metaAs(String key) {
        return (T) meta().get(key);
    }

    /**
     * 获取元信息
     */
    default <T> T metaOrDefault(String key, T def) {
        return (T) meta().getOrDefault(key, def);
    }

    /**
     * 获取消息
     */
    List<ChatMessage> getMessages();

    /**
     * 获取首条消息
     */
    ChatMessage getFirstMessage();

    /**
     * 获取最后消息
     */
    ChatMessage getLastMessage();

    /**
     * 获取用户消息内容
     */
    String getUserMessageContent();

    /**
     * 获取系统消息内容
     */
    String getSystemMessageContent();

    /**
     * 获取消息数量
     */
    int getMessagesSize();

    /**
     * 是否为空
     */
    static boolean isEmpty(ChatPrompt prompt) {
        return prompt == null || prompt.getMessagesSize() == 0;
    }
}