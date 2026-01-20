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
import org.noear.solon.ai.AiModel;
import org.noear.solon.ai.chat.dialect.ChatDialect;
import org.noear.solon.ai.chat.dialect.ChatDialectManager;
import org.noear.solon.ai.chat.interceptor.ChatInterceptor;
import org.noear.solon.ai.chat.prompt.ChatPrompt;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.Skill;
import org.noear.solon.ai.chat.tool.*;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.core.Props;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Preview;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

/**
 * 聊天模型
 *
 * @author noear
 * @since 3.1
 */
@Preview("3.1")
public class ChatModel implements AiModel {
    private final ChatConfig config;
    private final ChatDialect dialect;

    public ChatModel(Properties properties) {
        //支持直接注入
        this(Props.from(properties).bindTo(new ChatConfig()));
    }

    public ChatModel(ChatConfig config) {
        Assert.notNull(config, "The config is required");
        Assert.notNull(config.getApiUrl(), "The config.apiUrl is required");
        Assert.notNull(config.getModel(), "The config.model is required");

        this.config = config;
        this.dialect = ChatDialectManager.select(config);

        Assert.notNull(dialect, "The dialect(provider) no matched, check config or dependencies");

    }

    /**
     * 提示语
     *
     * @deprecated 3.8.4 {@link ChatRequestDesc#session(ChatSession)}
     */
    @Deprecated
    public ChatRequestDesc prompt(ChatSession session) {
        return new ChatRequestDescDefault(config, dialect, session, null);
    }

    /**
     * 提示语
     */
    public ChatRequestDesc prompt(ChatPrompt prompt) {
        return new ChatRequestDescDefault(config, dialect, null, prompt);
    }

    /**
     * 提示语
     */
    public ChatRequestDesc prompt(List<ChatMessage> messages) {
        return prompt(Prompt.of(messages));
    }

    /**
     * 提示语
     */
    public ChatRequestDesc prompt(ChatMessage... messages) {
        return prompt(Utils.asList(messages));
    }

    /**
     * 提示语
     */
    public ChatRequestDesc prompt(String content) {
        return prompt(ChatMessage.ofUser(content));
    }


    @Override
    public String toString() {
        return "ChatModel{" +
                "config=" + config +
                ", dialect=" + dialect.getClass().getName() +
                '}';
    }


    /// /////////////////////////////////

    /**
     * 构建
     */
    public static Builder of(ChatConfig config) {
        return new Builder(config);
    }

    /**
     * 开始构建
     */
    public static Builder of(String apiUrl) {
        return new Builder(apiUrl);
    }

    /// //////////////////

    /**
     * 聊天模型构建器实现
     *
     * @author noear
     * @since 3.1
     */
    public static class Builder {
        private final ChatConfig config;

        /**
         * @param apiUrl 接口地址
         */
        public Builder(String apiUrl) {
            this.config = new ChatConfig();
            this.config.setApiUrl(apiUrl);
        }

        /**
         * @param config 配置
         */
        public Builder(ChatConfig config) {
            this.config = config;
        }

        /**
         * 接口密钥
         */
        public Builder apiKey(String apiKey) {
            config.setApiKey(apiKey);
            return this;
        }

        /**
         * 服务提供者
         */
        public Builder provider(String provider) {
            config.setProvider(provider);
            return this;
        }

        /**
         * 使用模型
         */
        public Builder model(String model) {
            config.setModel(model);
            return this;
        }

        /**
         * 头信息添加
         */
        public Builder headerSet(String key, String value) {
            config.setHeader(key, value);
            return this;
        }

        /**
         * 默认工具添加（即每次请求都会带上）
         *
         * @param tool 工具对象
         */
        public Builder defaultToolsAdd(FunctionTool tool) {
            config.addDefaultTools(tool);
            return this;
        }

        /**
         * 默认工具添加（即每次请求都会带上）
         *
         * @param toolColl 工具集合
         */
        public Builder defaultToolsAdd(Iterable<FunctionTool> toolColl) {
            for (FunctionTool f : toolColl) {
                config.addDefaultTools(f);
            }

            return this;
        }

        /**
         * 默认工具添加（即每次请求都会带上）
         *
         * @param toolProvider 工具提供者
         */
        public Builder defaultToolsAdd(ToolProvider toolProvider) {
            return defaultToolsAdd(toolProvider.getTools());
        }

        /**
         * 默认工具添加（即每次请求都会带上）
         *
         * @param toolObj 工具对象
         */
        public Builder defaultToolsAdd(Object toolObj) {
            return defaultToolsAdd(new MethodToolProvider(toolObj));
        }

        /**
         * 默认工具添加（即每次请求都会带上）
         *
         * @param name        名字
         * @param toolBuilder 工具构建器
         */
        public Builder defaultToolsAdd(String name, Consumer<FunctionToolDesc> toolBuilder) {
            FunctionToolDesc decl = new FunctionToolDesc(name);
            toolBuilder.accept(decl);
            config.addDefaultTools(decl);
            return this;
        }

        /**
         * 默认工具上下文添加
         *
         * @deprecated  3.8.4 {@link #defaultToolsContextPut(String, Object)}
         */
        @Deprecated
        public Builder defaultToolsContextAdd(String key, Object value) {
            config.addDefaultToolsContext(key, value);
            return this;
        }

        /**
         * 默认工具上下文添加
         *
         * @deprecated  3.8.4 {@link #defaultToolsContextPut(Map)}
         */
        @Deprecated
        public Builder defaultToolsContextAdd(Map<String, Object> toolsContext) {
            config.addDefaultToolsContext(toolsContext);
            return this;
        }

        /**
         * 默认工具上下文添加
         *
         * @since 3.8.4
         */
        public Builder defaultToolsContextPut(String key, Object value) {
            config.addDefaultToolsContext(key, value);
            return this;
        }

        /**
         * 默认工具上下文添加
         *
         * @since 3.8.4
         */
        public Builder defaultToolsContextPut(Map<String, Object> toolsContext) {
            config.addDefaultToolsContext(toolsContext);
            return this;
        }

        /**
         * 默认技能添加
         *
         * @since 3.8.4
         */
        public Builder defaultSkillAdd(Skill skill) {
           return defaultSkillAdd(0, skill);
        }

        /**
         * 默认技能添加
         *
         * @since 3.8.4
         */
        public Builder defaultSkillAdd(int index, Skill skill) {
            config.addDefaultSkill(index, skill);
            return this;
        }

        /**
         * 添加默认拦截器
         *
         * @param interceptor 拦截器
         */
        public Builder defaultInterceptorAdd(ChatInterceptor interceptor) {
            return defaultInterceptorAdd(0, interceptor);
        }

        /**
         * 添加默认拦截器
         *
         * @param index       顺序位
         * @param interceptor 拦截器
         */
        public Builder defaultInterceptorAdd(int index, ChatInterceptor interceptor) {
            config.addDefaultInterceptor(index, interceptor);
            return this;
        }

        /**
         * 添加默认选项
         */
        public Builder defaultOptionAdd(String key, Object val) {
            config.addDefaultOption(key, val);
            return this;
        }

        /**
         * 网络超时
         */
        public Builder timeout(Duration timeout) {
            config.setTimeout(timeout);

            return this;
        }

        /**
         * 网络代理
         */
        public Builder proxy(Proxy proxy) {
            config.setProxy(proxy);

            return this;
        }

        /**
         * 网络代理
         */
        public Builder proxy(String host, int port) {
            return proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port)));
        }

        /**
         * 构建
         */
        public ChatModel build() {
            return new ChatModel(config);
        }
    }
}