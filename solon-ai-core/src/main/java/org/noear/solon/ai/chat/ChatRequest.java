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
import org.noear.solon.ai.chat.prompt.ChatPrompt;
import org.noear.solon.ai.chat.session.InMemoryChatSession;
import org.noear.solon.ai.chat.skill.Skill;
import org.noear.solon.ai.chat.skill.SkillUtil;
import org.noear.solon.lang.NonSerializable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 聊天请求持有者
 *
 * @author noear
 * @since 3.3
 */
public class ChatRequest implements NonSerializable {
    private final static Logger LOG = LoggerFactory.getLogger(ChatRequest.class);

    private final ChatConfig config;
    private final ChatConfigReadonly configReadonly;
    private final ChatDialect dialect;
    private final ChatOptions options;
    private final ChatSession session;
    private final boolean stream;
    private List<ChatMessage> messages;

    public ChatRequest(ChatConfig config, ChatDialect dialect, ChatOptions options, ChatSession session, ChatPrompt prompt, boolean stream) {
        this.session = session;
        this.config = config;
        this.configReadonly = new ChatConfigReadonly(config);
        this.dialect = dialect;
        this.options = options;
        this.stream = stream;
        this.messages = Collections.unmodifiableList(session.getMessages());
    }

    /**
     * 获取配置
     */
    public ChatConfigReadonly getConfig() {
        return configReadonly;
    }

    /**
     * 获取选项
     */
    public ChatOptions getOptions() {
        return options;
    }

    /**
     * 获取会话
     */
    public ChatSession getSession() {
        return session;
    }

    /**
     * 是否为流请求
     */
    public boolean isStream() {
        return stream;
    }


    /**
     * 获取消息
     */
    public List<ChatMessage> getMessages() {
        return messages;
    }

    /**
     * 转为请求数据
     */
    public String toRequestData() {
        //留个变量方便调试
        String reqJson = dialect.buildRequestJson(config, options, messages, stream);
        return reqJson;
    }
}