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

import org.noear.solon.ai.AiConfig;
import org.noear.solon.ai.chat.interceptor.ChatInterceptor;
import org.noear.solon.ai.chat.talent.Talent;
import org.noear.solon.ai.chat.tool.FunctionTool;

import java.util.*;
import java.util.function.Consumer;

/**
 * 聊天模型配置
 *
 * @author noear
 * @since 3.1
 */
public class ChatConfig extends AiConfig {
    //用于配置注入
    private Boolean defaultAutoToolCall;
    private Map<String, Object> defaultToolContext;
    private Map<String, Object> defaultOptions;

    //作为构建载体（方便转换）
    private transient ChatOptions modelOptions;


    public ChatConfig then(Consumer<ChatConfig> build){
        build.accept(this);
        return this;
    }

    public ChatOptions getModelOptions() {
        if (modelOptions == null) {
            modelOptions = new ChatOptions();

            if (defaultToolContext != null) {
                modelOptions.toolContextPut(defaultToolContext);
                defaultToolContext = null;
            }

            if (defaultOptions != null) {
                modelOptions.optionSet(defaultOptions);
                defaultOptions = null;
            }

            if (defaultAutoToolCall != null) {
                modelOptions.autoToolCall(defaultAutoToolCall);
                defaultAutoToolCall = null;
            }
        }

        return modelOptions;
    }

    public void setDefaultAutoToolCall(boolean defaultAutoToolCall) {
        getModelOptions().autoToolCall(defaultAutoToolCall);
    }


    /**
     * 设置默认工具（用于属性提示）
     */
    public void setDefaultTools(Map<String, FunctionTool> items) {
        getModelOptions().toolAdd(items);
    }

    /**
     * 添加默认工具（即每次请求都会带上）
     */
    public void addDefaultTool(FunctionTool... tools) {
        getModelOptions().toolAdd(tools);
    }

    /**
     * 添加默认工具（即每次请求都会带上）
     */
    public void addDefaultTool(Collection<FunctionTool> items) {
        getModelOptions().toolAdd(items);
    }

    /**
     * 添加默认工具上下文
     */
    public void addDefaultToolContext(String key, Object value) {
        getModelOptions().toolContextPut(key, value);
    }

    /**
     * 添加默认工具上下文
     */
    public void addDefaultToolContext(Map<String, Object> map) {
        getModelOptions().toolContextPut(map);
    }


    public void addDefaultTalent(int index, Talent talent) {
        getModelOptions().talentAdd(index, talent);
    }


    /**
     * 添加默认拦截器
     */
    public void addDefaultInterceptor(int index, ChatInterceptor interceptor) {
        getModelOptions().interceptorAdd(index, interceptor);
    }


    /**
     * 添加默认选项
     */
    public void addDefaultOption(String key, Object value) {
        getModelOptions().optionSet(key, value);
    }

    /**
     * 快速转为聊天模型
     */
    public ChatModel toChatModel() {
        return ChatModel.of(this).build();
    }

    @Override
    public String toString() {
        return "ChatConfig{" +
                "apiUrl='" + apiUrl + '\'' +
                ", apiKey='" + apiKey + '\'' +
                ", provider='" + provider + '\'' +
                ", model='" + model + '\'' +
                ", headers=" + headers +
                ", timeout=" + timeout +
                ", proxy=" + getProxy() +
                '}';
    }
}