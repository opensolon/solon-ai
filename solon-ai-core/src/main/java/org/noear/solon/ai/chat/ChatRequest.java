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


import org.noear.solon.Utils;
import org.noear.solon.ai.chat.dialect.ChatDialect;
import org.noear.solon.ai.chat.interceptor.ChatInterceptor;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.session.InMemoryChatSession;
import org.noear.solon.ai.chat.skill.Skill;
import org.noear.solon.core.util.RankEntity;
import org.noear.solon.lang.NonSerializable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
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
    private final List<RankEntity<ChatInterceptor>> interceptorList;
    private List<ChatMessage> messages;

    public ChatRequest(ChatConfig config, ChatDialect dialect, ChatOptions options, ChatSession session0, List<ChatMessage> prompt, boolean stream) {
        if (session0 == null) {
            session0 = InMemoryChatSession.builder().build();
        }


        this.session = session0;


        //收集拦截器
        this.interceptorList = new ArrayList<>();
        interceptorList.addAll(config.getDefaultInterceptors());
        interceptorList.addAll(options.interceptors());
        if (interceptorList.size() > 1) {
            Collections.sort(interceptorList);
        }

        List<Skill> activeSkills = options.skills().stream()
                .map(s -> s.target)
                .filter(s -> {
                    try {
                        return s.isSupported(session);
                    } catch (Throwable e) {
                        LOG.error("Skill support check failed: {}", s.getClass().getName(), e);
                        return false;
                    }
                }) // 1. 过滤
                .collect(Collectors.toList());

        for (Skill skill : activeSkills) {
            try {
                // 3. 挂载
                skill.onAttach(session);
            } catch (Throwable e) {
                LOG.error("Skill active failed: {}", skill.getClass().getName(), e);
                throw e;
            }

            // 4. 收集指令
            String ins = skill.getInstruction(session);
            if (Utils.isNotEmpty(ins)) {
                session.addMessage(ChatMessage.ofSystem(ins));
            }

            // 5. 收集工具
            options.toolsAdd(skill.getTools());
        }



        if (prompt != null) {
            this.session.addMessage(prompt);
        }


        this.config = config;
        this.configReadonly = new ChatConfigReadonly(config);
        this.dialect = dialect;
        this.options = options;
        this.stream = stream;
        this.messages = Collections.unmodifiableList(session.getMessages());
    }

    protected List<RankEntity<ChatInterceptor>> getInterceptorList() {
        return interceptorList;
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