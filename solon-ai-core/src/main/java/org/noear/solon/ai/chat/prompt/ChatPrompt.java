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
     *
     * @since 3.8.4
     */
    Map<String, Object> attrs();

    /**
     * 获取元信息
     *
     * @since 3.8.4
     */
    default Object attr(String key) {
        return attrs().get(key);
    }
    /**
     * 获取元信息
     *
     * @since 3.8.4
     */
    default <T> T attrAs(String key) {
        return (T) attrs().get(key);
    }

    /**
     * 获取元信息
     *
     * @since 3.8.4
     */
    default <T> T attrOrDefault(String key, T def) {
        return (T) attrs().getOrDefault(key, def);
    }

    /**
     * 获取消息
     */
    List<ChatMessage> getMessages();

    /**
     * 获取首条消息
     *
     * @since 3.8.4
     */
    ChatMessage getFirstMessage();

    /**
     * 获取最后消息
     *
     * @since 3.8.4
     */
    ChatMessage getLastMessage();

    /**
     * 获取用户消息内容
     *
     * @since 3.8.4
     */
    String getUserContent();

    /**
     * 获取系统消息内容
     *
     * @since 3.8.4
     */
    String getSystemContent();

    /**
     * 是否为空
     *
     * @since 3.8.4
     */
    boolean isEmpty();

    /**
     * 是否为空
     */
    static boolean isEmpty(ChatPrompt prompt) {
        return prompt == null || prompt.isEmpty();
    }
}