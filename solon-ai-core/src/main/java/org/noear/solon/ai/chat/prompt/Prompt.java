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

import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Preview;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 聊天提示语
 *
 * @author noear
 * @since 3.2
 */
@Preview("3.2")
public interface Prompt extends Serializable {
    /**
     * 获取属性
     *
     * @since 3.8.4
     */
    Map<String, Object> attrs();

    /**
     * 获取属性
     *
     * @since 3.8.4
     */
    default Object attr(String key) {
        return attrs().get(key);
    }

    /**
     * 获取属性
     *
     * @since 3.8.4
     */
    default <T> T attrAs(String key) {
        return (T) attrs().get(key);
    }

    /**
     * 获取属性
     *
     * @since 3.8.4
     */
    default <T> T attrOrDefault(String key, T def) {
        return (T) attrs().getOrDefault(key, def);
    }

    /**
     * 设置属性
     */
    default Prompt attrPut(String name, Object value) {
        attrs().put(name, value);
        return this;
    }

    /**
     * 设置属性
     */
    default Prompt attrPut(Map<String, Object> map) {
        if (Assert.isNotEmpty(map)) {
            attrs().putAll(map);
        }

        return this;
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
     * 移除最后消息
     *
     * @since 3.9.1
     */
    void removeLastMessage();

    /**
     * 获取最后 Assistant 消息
     *
     * @since 3.9.0
     */
    AssistantMessage getLastAssistantMessage();

    /**
     * 移除最后 Assistant 消息
     *
     * @since 3.9.1
     */
    void removeLastAssistantMessage();

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

    /*
     * 添加消息
     */
    Prompt addMessage(String msg);

    /*
     * 添加消息
     */
    Prompt addMessage(ChatMessage... msgs);

    /*
     * 添加消息
     */
    Prompt addMessage(Collection<ChatMessage> msgs);

    /*
     * 替换消息
     */
    Prompt replaceMessages(Collection<ChatMessage> messages);

    /**
     * 是否为空
     *
     * @since 3.8.4
     */
    boolean isEmpty();

    /**
     * 获取大小
     */
    int size();

    /**
     * 清空
     */
    void clear();

    /**
     * 是否为空
     */
    static boolean isEmpty(Prompt prompt) {
        return prompt == null || prompt.isEmpty();
    }

    /**
     * 构建
     */
    static Prompt of(Collection<ChatMessage> messages) {
        return new PromptImpl().addMessage(messages);
    }

    /**
     * 构建
     */
    static Prompt of(String message) {
        return new PromptImpl().addMessage(message);
    }

    /**
     * 构建
     */
    static Prompt of(ChatMessage... messages) {
        return new PromptImpl().addMessage(messages);
    }
}