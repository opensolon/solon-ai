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

import org.noear.solon.ai.chat.interceptor.ChatInterceptor;
import org.noear.solon.ai.chat.skill.Skill;
import org.noear.solon.ai.chat.tool.FunctionTool;

import java.net.Proxy;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 聊天模型配置只读的
 *
 * @author noear
 * @since 3.3
 */
public class ChatConfigReadonly {
    private final ChatConfig config;

    public ChatConfigReadonly(ChatConfig config) {
        this.config = config;
    }

    public String getApiKey() {
        return config.getApiKey();
    }

    public String getApiUrl() {
        return config.getApiUrl();
    }

    public String getProvider() {
        return config.getProvider();
    }

    public String getModel() {
        return config.getModel();
    }

    public Map<String, String> getHeaders() {
        return Collections.unmodifiableMap(config.getHeaders());
    }

    public Duration getTimeout() {
        return config.getTimeout();
    }

    public Proxy getProxy() {
        return config.getProxy();
    }

    /**
     * 获取所有默认工具
     */
    public Collection<FunctionTool> getDefaultTools() {
        return config.getModelOptions().tools();
    }

    /**
     * 获取所有默认技能
     */
    public Collection<Skill> getDefaultSkills() {
        return config.getModelOptions().skills()
                .stream().map(e -> e.target)
                .collect(Collectors.toList());
    }

    /**
     * 获取所有默认拦截器
     */
    public Collection<ChatInterceptor> getDefaultInterceptors() {
        return config.getModelOptions().interceptors()
                .stream()
                .map(e -> e.target)
                .collect(Collectors.toList());
    }
}