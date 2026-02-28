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
import org.noear.solon.ai.chat.skill.Skill;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.RankEntity;

import java.util.*;

/**
 * 聊天模型配置
 *
 * @author noear
 * @since 3.1
 */
public class ChatConfig extends AiConfig {
    //用于 r1 思考补位（手动构建 AssistantMessage 时用）
    private String reasoningFieldName;

    //用于配置注入
    private Boolean defaultAutoToolCall;
    @Deprecated
    private Map<String, Object> defaultToolsContext;
    private Map<String, Object> defaultToolContext;
    private Map<String, Object> defaultOptions;

    //作为构建载体（方便转换）
    private transient ChatOptions modelOptions;

    public ChatOptions getModelOptions() {
        if (modelOptions == null) {
            modelOptions = new ChatOptions();

            if (defaultToolsContext != null) {
                //已弃用
                modelOptions.toolContextPut(defaultToolsContext);
                defaultToolsContext = null;
            }

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

    public void setReasoningFieldName(String reasoningFieldName) {
        this.reasoningFieldName = reasoningFieldName;
    }

    public String getReasoningFieldName() {
        if (reasoningFieldName == null) {
            if (getModel().toLowerCase().contains("deepseek")) {
                reasoningFieldName = "reasoning_content";
            } else {
                reasoningFieldName = "";
            }
        }

        return reasoningFieldName;
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


    public void addDefaultSkill(int index, Skill skill) {
        getModelOptions().skillAdd(index, skill);
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


    /**
     * 添加默认工具（即每次请求都会带上）
     *
     * @deprecated 3.8.4 {@link #addDefaultTool(FunctionTool...)}
     */
    @Deprecated
    public void addDefaultTools(FunctionTool tool) {
        addDefaultTool(tool);
    }

    /**
     * 添加默认工具（即每次请求都会带上）
     *
     * @deprecated 3.8.4 {@link #addDefaultTool(Collection<FunctionTool>)}
     */
    @Deprecated
    public void addDefaultTools(Collection<FunctionTool> items) {
        addDefaultTool(items);
    }

    /**
     * 添加默认工具上下文
     *
     * @deprecated 3.8.4 {@link #addDefaultToolContext(String, Object)}
     */
    @Deprecated
    public void addDefaultToolsContext(String key, Object value) {
        addDefaultToolContext(key, value);
    }

    /**
     * 添加默认工具上下文
     *
     * @deprecated 3.8.4 {@link #addDefaultToolContext(Map<String, Object>)}
     */
    @Deprecated
    public void addDefaultToolsContext(Map<String, Object> map) {
        addDefaultToolContext(map);
    }

    //============


    /**
     * 是否自动扩行工具调用（默认为 true）
     *
     * @deprecated 3.8.4 {@link #getModelOptions()}
     */
    @Deprecated
    public boolean isDefaultAutoToolCall() {
        return getModelOptions().isAutoToolCall();
    }

    /**
     * 获取所有默认拦截器
     *
     * @deprecated 3.8.4 {@link #getModelOptions()}
     */
    @Deprecated
    public Collection<RankEntity<ChatInterceptor>> getDefaultInterceptors() {
        return getModelOptions().interceptors();
    }

    /**
     * 获取默认工具上下文
     *
     * @deprecated 3.8.4 {@link #getModelOptions()}
     */
    @Deprecated
    public Map<String, Object> getDefaultToolContext() {
        return getModelOptions().toolContext();
    }

    /**
     * 获取默认的技能
     *
     * @deprecated 3.8.4 {@link #getModelOptions()}
     */
    @Deprecated
    public Collection<RankEntity<Skill>> getDefaultSkills() {
        return getModelOptions().skills();
    }

    /**
     * 获取所有默认选项
     *
     * @deprecated 3.8.4 {@link #getModelOptions()}
     */
    @Deprecated
    public Map<String, Object> getDefaultOptions() {
        return getModelOptions().options();
    }

    /**
     * 获取单个默认工具（即每次请求都会带上）
     *
     * @deprecated 3.8.4 {@link #getModelOptions()}
     */
    @Deprecated
    public FunctionTool getDefaultTool(String name) {
        return getModelOptions().tool(name);
    }

    /**
     * 获取所有默认工具（即每次请求都会带上）
     *
     * @deprecated 3.8.4 {@link #getModelOptions()}
     */
    @Deprecated
    public Collection<FunctionTool> getDefaultTools() {
        return getModelOptions().tools();
    }


    /**
     * 获取默认工具上下文
     *
     * @deprecated 3.8.4 {@link #getModelOptions()}
     */
    @Deprecated
    public Map<String, Object> getDefaultToolsContext() {
        return getModelOptions().toolContext();
    }
}