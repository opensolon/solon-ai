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
package org.noear.solon.ai.agent.simple;

import org.noear.solon.ai.agent.AgentHandler;
import org.noear.solon.ai.agent.AgentProfile;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.interceptor.ChatInterceptor;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.core.util.RankEntity;

import java.util.*;
import java.util.function.Consumer;

/**
 * @author noear 2026/1/12 created
 */
public class SimpleAgentConfig {
    private String name;
    private String title;
    private String description;
    private AgentProfile profile;
    private SimpleSystemPrompt systemPrompt;
    private ChatModel chatModel;
    /**
     * 挂载的功能工具集
     */
    private final Map<String, FunctionTool> tools = new LinkedHashMap<>();
    /**
     * 推理阶段的特定 ChatOptions 配置（如温度、TopP 等）
     */
    private Consumer<ChatOptions> chatOptions;

    private AgentHandler handler;

    /**
     * 工具调用上下文
     */
    private final Map<String, Object> toolsContext = new LinkedHashMap<>();
    /**
     * 生命周期拦截器（监控 Thought, Action, Observation 等状态变化）
     */
    private final List<RankEntity<ChatInterceptor>> interceptors = new ArrayList<>();

    /**
     * 模型调用失败后的最大重试次数
     */
    private int maxRetries = 3;
    /**
     * 重试延迟时间（毫秒）
     */
    private long retryDelayMs = 1000L;
    /**
     * 历史消息窗口大小（从上下文中回溯并注入到当前执行过程的消息条数）
     */
    private int historyWindowSize = 5;

    /**
     * 结果输出 Key
     */
    private String outputKey;
    /**
     * 期望的输出 Schema（例如 JSON Schema 字符串或描述）
     */
    private String outputSchema;


    // --- Getter Methods (Public) ---

    public String getName() {
        return name;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public AgentProfile getProfile() {
        if (profile == null) {
            profile = new AgentProfile();
        }

        return profile;
    }

    public SimpleSystemPrompt getSystemPrompt() {
        return systemPrompt;
    }

    public ChatModel getChatModel() {
        return chatModel;
    }

    public Collection<FunctionTool> getTools() {
        return tools.values();
    }

    public Consumer<ChatOptions> getChatOptions() {
        return chatOptions;
    }

    public Map<String, Object> getToolsContext() {
        return toolsContext;
    }

    public List<RankEntity<ChatInterceptor>> getInterceptors() {
        return interceptors;
    }

    public AgentHandler getHandler() {
        return handler;
    }


    public int getMaxRetries() {
        return maxRetries;
    }

    public long getRetryDelayMs() {
        return retryDelayMs;
    }

    public int getHistoryWindowSize() {
        return historyWindowSize;
    }


    public String getOutputKey() {
        return outputKey;
    }

    public String getOutputSchema() {
        return outputSchema;
    }


    // --- Setter Methods (Protected) ---

    protected void setName(String name) {
        this.name = name;
    }

    protected void setTitle(String title) {
        this.title = title;
    }

    protected void setDescription(String description) {
        this.description = description;
    }

    protected void setProfile(AgentProfile profile) {
        this.profile = profile;
    }

    protected void setSystemPrompt(SimpleSystemPrompt systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    protected void setChatModel(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    protected void setChatOptions(Consumer<ChatOptions> chatOptions) {
        this.chatOptions = chatOptions;
    }


    protected void setHandler(AgentHandler handler) {
        this.handler = handler;
    }

    /**
     * 添加单个功能工具
     */
    protected void addTool(FunctionTool... tools) {
        for (FunctionTool tool : tools) {
            this.tools.put(tool.name(), tool);
        }
    }

    /**
     * 批量添加功能工具
     */
    protected void addTool(Collection<FunctionTool> tools) {
        for (FunctionTool tool : tools) {
            addTool(tool);
        }
    }

    /**
     * 通过 ToolProvider 注入工具集
     */
    protected void addTool(ToolProvider toolProvider) {
        addTool(toolProvider.getTools());
    }

    public void addInterceptor(ChatInterceptor interceptor, int index) {
        interceptors.add(new RankEntity<>(interceptor, index));

        if (interceptors.size() > 0) {
            Collections.sort(interceptors);
        }
    }

    /**
     * 配置重试策略
     *
     * @param maxRetries   最大重试次数
     * @param retryDelayMs 重试延迟时间（毫秒）
     */
    protected void setRetryConfig(int maxRetries, long retryDelayMs) {
        this.maxRetries = Math.max(1, maxRetries);
        this.retryDelayMs = Math.max(500, retryDelayMs); // 最小 500ms
    }

    /**
     * 设置历史消息窗口大小
     *
     * @param historyWindowSize 回溯的消息条数（建议设置为奇数以保持对话轮次完整）
     */
    protected void setHistoryWindowSize(int historyWindowSize) {
        this.historyWindowSize = Math.max(0, historyWindowSize);
    }


    protected void setOutputKey(String val) {
        this.outputKey = val;
    }

    protected void setOutputSchema(String val) {
        this.outputSchema = val;
    }
}