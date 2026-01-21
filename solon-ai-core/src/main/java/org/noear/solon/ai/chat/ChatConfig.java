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
import org.noear.solon.core.util.RankEntity;

import java.util.*;

/**
 * 聊天模型配置
 *
 * @author noear
 * @since 3.1
 */
public class ChatConfig extends AiConfig {
    private boolean defaultAutoToolCall = true;
    private final Map<String, FunctionTool> defaultTools = new LinkedHashMap<>();
    private final Map<String, Object> defaultToolContext = new LinkedHashMap<>();
    private final List<RankEntity<Skill>> defaultSkills = new ArrayList<>();
    private final List<RankEntity<ChatInterceptor>> defaultInterceptors = new ArrayList<>();
    private final Map<String, Object> defaultOptions = new LinkedHashMap<>();

    /**
     * @deprecated 请使用 {@link #defaultToolContext}
     */
    @Deprecated
    private Map<String, Object> defaultToolsContext;


    public void setDefaultAutoToolCall(boolean defaultAutoToolCall) {
        this.defaultAutoToolCall = defaultAutoToolCall;
    }

    /**
     * 是否自动扩行工具调用（默认为 true）
     *
     */
    public boolean isDefaultAutoToolCall() {
        return defaultAutoToolCall;
    }

    /**
     * 设置默认工具（用于属性提示）
     */
    public void setDefaultTools(Map<String, FunctionTool> tools) {
        if (tools != null) {
            defaultTools.putAll(tools);
        }
    }

    /**
     * 添加默认工具（即每次请求都会带上）
     */
    public void addDefaultTool(FunctionTool tool) {
        defaultTools.put(tool.name(), tool);
    }

    /**
     * 添加默认工具（即每次请求都会带上）
     */
    public void addDefaultTool(Collection<FunctionTool> toolColl) {
        for (FunctionTool f : toolColl) {
            defaultTools.put(f.name(), f);
        }
    }

    /**
     * 获取单个默认工具（即每次请求都会带上）
     *
     * @param name 名字
     */
    public FunctionTool getDefaultTool(String name) {
        return defaultTools.get(name);
    }

    /**
     * 获取所有默认工具（即每次请求都会带上）
     */
    public Collection<FunctionTool> getDefaultTools() {
        return defaultTools.values();
    }


    /**
     * 添加默认工具上下文
     */
    public void addDefaultToolContext(String key, Object value) {
        defaultToolContext.put(key, value);
    }

    /**
     * 添加默认工具上下文
     */
    public void addDefaultToolContext(Map<String, Object> toolsContext) {
        defaultToolContext.putAll(toolsContext);
    }


    /**
     * 获取默认工具上下文
     */
    public Map<String, Object> getDefaultToolContext() {
        if (defaultToolsContext != null) {
            //变名的兼容处理
            defaultToolContext.putAll(defaultToolsContext);
            defaultToolsContext = null;
        }

        return defaultToolContext;
    }


    public void addDefaultSkill(int index, Skill skill) {
        if (skill != null) {
            defaultSkills.add(new RankEntity<>(skill, index));
        }
    }

    public List<RankEntity<Skill>> getDefaultSkills() {
        return defaultSkills;
    }

    /**
     * 添加默认拦截器
     */
    public void addDefaultInterceptor(int index, ChatInterceptor interceptor) {
        defaultInterceptors.add(new RankEntity<>(interceptor, index));
    }

    /**
     * 获取所有默认拦截器
     */
    public List<RankEntity<ChatInterceptor>> getDefaultInterceptors() {
        return defaultInterceptors;
    }

    /**
     * 添加默认选项
     */
    public void addDefaultOption(String key, Object value) {
        defaultOptions.put(key, value);
    }

    /**
     * 获取所有默认选项
     */
    public Map<String, Object> getDefaultOptions() {
        return defaultOptions;
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
                ", defaultTools=" + defaultTools +
                '}';
    }


    /**
     * 添加默认工具（即每次请求都会带上）
     *
     * @deprecated 3.8.4 {@link #addDefaultTool(FunctionTool)}
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
    public void addDefaultTools(Collection<FunctionTool> toolColl) {
        addDefaultTool(toolColl);
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
    public void addDefaultToolsContext(Map<String, Object> toolsContext) {
        addDefaultToolContext(toolsContext);
    }


    /**
     * 获取默认工具上下文
     *
     * @deprecated 3.8.4 {@link #getDefaultToolContext()}
     */
    @Deprecated
    public Map<String, Object> getDefaultToolsContext() {
        return getDefaultToolContext();
    }
}