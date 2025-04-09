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

import org.noear.solon.ai.AiModel;
import org.noear.solon.ai.chat.annotation.ToolMapping;
import org.noear.solon.ai.chat.dialect.ChatDialect;
import org.noear.solon.ai.chat.dialect.ChatDialectManager;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.ai.chat.tool.MethodFunctionTool;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.lang.Preview;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
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

    public ChatModel(ChatConfig config) {
        this.dialect = ChatDialectManager.select(config);
        this.config = config;
    }

    /**
     * 提示语
     */
    public ChatRequest prompt(ChatSession session) {
        return prompt(session.getMessages());
    }

    /**
     * 提示语
     */
    public ChatRequest prompt(List<ChatMessage> messages) {
        return new ChatRequestDefault(config, dialect, messages);
    }

    /**
     * 提示语
     */
    public ChatRequest prompt(ChatMessage... messages) {
        return prompt(new ArrayList<>(Arrays.asList(messages)));
    }

    /**
     * 提示语
     */
    public ChatRequest prompt(String content) {
        return prompt(ChatMessage.ofUser(content));
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
         * @param toolColl 工具集合
         */
        public Builder defaultToolsAdd(Collection<FunctionTool> toolColl) {
            for (FunctionTool f : toolColl) {
                config.addDefaultTool(f);
            }

            return this;
        }

        /**
         * 默认工具添加（即每次请求都会带上）
         *
         * @param toolObj 工具对象
         */
        public Builder defaultToolAdd(Object toolObj) {
            return defaultToolAdd(toolObj.getClass(), toolObj);
        }

        /**
         * 默认工具添加（即每次请求都会带上）
         *
         * @param toolClz 工具类（如果工具对象为代理时，必须传入原始类）
         * @param toolObj 工具对象
         */
        public Builder defaultToolAdd(Class<?> toolClz, Object toolObj) {
            if (toolObj instanceof FunctionTool) {
                FunctionTool func = (FunctionTool) toolObj;
                config.addDefaultTool(func);
            } else {
                int count = 0;
                for (Method method : toolClz.getMethods()) {
                    if (method.isAnnotationPresent(ToolMapping.class)) {
                        MethodFunctionTool func = new MethodFunctionTool(toolObj, method);
                        config.addDefaultTool(func);
                        count++;
                    }
                }

                if (count == 0) {
                    throw new IllegalArgumentException("This functionBean is not ChatFunction");
                }
            }

            return this;
        }

        /**
         * 默认工具添加（即每次请求都会带上）
         *
         * @param name        名字
         * @param toolBuilder 工具构建器
         */
        public Builder defaultToolAdd(String name, Consumer<FunctionToolDesc> toolBuilder) {
            FunctionToolDesc decl = new FunctionToolDesc(name);
            toolBuilder.accept(decl);
            defaultToolAdd(decl);
            return this;
        }

        /**
         * 超时
         */
        public Builder timeout(Duration timeout) {
            config.setTimeout(timeout);

            return this;
        }

        public ChatModel build() {
            return new ChatModel(config);
        }
    }
}