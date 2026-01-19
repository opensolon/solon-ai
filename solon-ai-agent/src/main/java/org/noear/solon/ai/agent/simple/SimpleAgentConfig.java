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
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.core.util.RankEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Consumer;

/**
 * 简单智能体配置类
 *
 * @author noear
 * @since 3.8.1
 */
public class SimpleAgentConfig {
    private static final Logger log = LoggerFactory.getLogger(SimpleAgentConfig.class);

    /** 唯一标识名 */
    private String name = "simple_agent";
    /** 显示标题 */
    private String title;
    /** 功能描述 */
    private String description;
    /** 智能体画像（能力、模态支持等） */
    private AgentProfile profile;
    /** 系统提示词模板（支持动态注入上下文） */
    private SimpleSystemPrompt systemPrompt;
    /** 绑定的物理聊天模型 */
    private ChatModel chatModel;
    /** 挂载的本地/远程功能工具集 */
    private final Map<String, FunctionTool> tools = new LinkedHashMap<>();
    /** 推理阶段的特定 ChatOptions 配置（如温度、TopP 等） */
    private Consumer<ChatOptions> chatOptions;
    /** 自定义处理器（与 chatModel 二选一） */
    private AgentHandler handler;
    /** 工具调用的共享上下文数据 */
    private final Map<String, Object> toolsContext = new LinkedHashMap<>();
    /** 生命周期拦截器队列（支持监控、审计、Thought 记录等） */
    private final List<RankEntity<SimpleInterceptor>> interceptors = new ArrayList<>();

    /** 模型调用失败后的最大重试次数 */
    private int maxRetries = 3;
    /** 指数退避重试的延迟基础时间（毫秒） */
    private long retryDelayMs = 1000L;
    /** 历史消息回溯窗口（注入到当前 Prompt 的对话轮数） */
    private int historyWindowSize = 5;

    /** 响应结果回填到 FlowContext 的键名 */
    private String outputKey;
    /** 期望的输出 JSON Schema 或格式协议描述 */
    private String outputSchema;

    // --- Setter & Add Methods (Protected/Public) ---

    protected void setName(String name) { this.name = name; }
    protected void setTitle(String title) { this.title = title; }
    protected void setDescription(String description) { this.description = description; }
    protected void setProfile(AgentProfile profile) { this.profile = profile; }
    protected void setSystemPrompt(SimpleSystemPrompt systemPrompt) {
        this.systemPrompt = systemPrompt;

        String role = systemPrompt.getRole();
        if (role != null && description == null) {
            description = role;
        }
    }

    protected void setChatModel(ChatModel chatModel) { this.chatModel = chatModel; }
    protected void setChatOptions(Consumer<ChatOptions> chatOptions) { this.chatOptions = chatOptions; }
    protected void setHandler(AgentHandler handler) { this.handler = handler; }

    /** 注册功能工具 */
    protected void addTool(FunctionTool... tools) {
        for (FunctionTool tool : tools) {
            if (log.isDebugEnabled()) log.debug("Agent [{}] linked tool: {}", name, tool.name());
            this.tools.put(tool.name(), tool);
        }
    }

    protected void addTool(Collection<FunctionTool> tools) {
        for (FunctionTool tool : tools) addTool(tool);
    }

    protected void addTool(ToolProvider toolProvider) {
        addTool(toolProvider.getTools());
    }

    /** 注册并重排拦截器 */
    public void addInterceptor(SimpleInterceptor interceptor, int index) {
        interceptors.add(new RankEntity<>(interceptor, index));
        Collections.sort(interceptors);
    }

    /** 配置容错策略 */
    protected void setRetryConfig(int maxRetries, long retryDelayMs) {
        this.maxRetries = Math.max(1, maxRetries);
        this.retryDelayMs = Math.max(500, retryDelayMs);
    }

    /** 设置短期记忆回溯深度 */
    protected void setHistoryWindowSize(int historyWindowSize) {
        this.historyWindowSize = Math.max(0, historyWindowSize);
    }

    protected void setOutputKey(String val) { this.outputKey = val; }
    protected void setOutputSchema(String val) { this.outputSchema = val; }

    // --- Getter Methods (Public) ---

    public String getName() { return name; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public AgentProfile getProfile() {
        if (profile == null) profile = new AgentProfile();
        return profile;
    }
    public SimpleSystemPrompt getSystemPrompt() { return systemPrompt; }
    public ChatModel getChatModel() { return chatModel; }
    public Collection<FunctionTool> getTools() { return tools.values(); }
    public Consumer<ChatOptions> getChatOptions() { return chatOptions; }
    public Map<String, Object> getToolsContext() { return toolsContext; }
    public List<RankEntity<SimpleInterceptor>> getInterceptors() { return interceptors; }
    public AgentHandler getHandler() { return handler; }
    public int getMaxRetries() { return maxRetries; }
    public long getRetryDelayMs() { return retryDelayMs; }
    public int getHistoryWindowSize() { return historyWindowSize; }
    public String getOutputKey() { return outputKey; }
    public String getOutputSchema() { return outputSchema; }
}