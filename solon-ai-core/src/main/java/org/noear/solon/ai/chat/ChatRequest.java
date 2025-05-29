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


import org.noear.solon.ai.chat.dialect.ChatDialect;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.core.util.Assert;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 聊天请求持有者
 *
 * @author noear
 * @since 3.3
 */
public class ChatRequest {
    private final ChatConfig config;
    private final ChatDialect dialect;
    private final ChatOptions options;
    private final boolean stream;
    private List<ChatMessage> messages;

    public ChatRequest(ChatConfig config, ChatDialect dialect, ChatOptions options, boolean stream, List<ChatMessage> messages) {
        this.config = config;
        this.dialect = dialect;
        this.options = options;
        this.stream = stream;
        this.messages = messages;
    }

    /**
     * 获取提供者
     */
    public String getProvider() {
        return config.getProvider();
    }

    /**
     * 获取模型
     */
    public String getModel() {
        return config.getModel();
    }

    /**
     * 获取请求头
     */
    public Map<String, String> getHeaders() {
        return Collections.unmodifiableMap(config.getHeaders());
    }

    /**
     * 获取选项
     */
    public Map<String, Object> getOptions() {
        return options.options();
    }

    /**
     * 添加选项
     */
    public void addOption(String key, Object value) {
        options.optionAdd(key, value);
    }

    /**
     * 是否为流请求
     */
    public boolean isStream() {
        return stream;
    }

    /**
     * 设置消息
     */
    public void setMessages(List<ChatMessage> messages) {
        Assert.notEmpty(messages, "messages is empty");

        this.messages = messages;
    }

    /**
     * 获取消息
     */
    public List<ChatMessage> getMessages() {
        return Collections.unmodifiableList(messages);
    }

    /**
     * 转为请求数据
     */
    public String toRequestData() {
        String reqJson = dialect.buildRequestJson(config, options, messages, stream);
        return reqJson;
    }
}