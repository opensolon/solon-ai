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
import org.noear.solon.ai.agent.AgentSystemPrompt;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.core.util.SnelUtil;
import org.noear.solon.flow.FlowContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * 简单智能体配置类
 *
 * @author noear
 * @since 3.8.1
 */
public class SimpleAgentConfig {
    private static final Logger log = LoggerFactory.getLogger(SimpleAgentConfig.class);

    /**
     * 唯一标识名
     */
    private String name = "simple_agent";
    /** 链路追踪 Key (用于在 FlowContext 中存储 Trace 状态) */
    private volatile String traceKey;
    /**
     * 功能描述
     */
    private String role;
    /**
     * 智能体画像（能力、模态支持等）
     */
    private AgentProfile profile;
    /**
     * 系统提示词模板（支持动态注入上下文）
     */
    private AgentSystemPrompt<SimpleTrace> systemPrompt = SimpleSystemPrompt.getDefault();
    /**
     * 绑定的物理聊天模型
     */
    private ChatModel chatModel;
    /**
     * 模型选项
     */
    private SimpleOptions defaultOptions = new SimpleOptions();
    /**
     * 自定义处理器（与 chatModel 二选一）
     */
    private AgentHandler handler;

    /**
     * 模型调用失败后的最大重试次数
     */
    private int maxRetries = 3;
    /**
     * 指数退避重试的延迟基础时间（毫秒）
     */
    private long retryDelayMs = 1000L;
    /**
     * 会话回溯窗口大小
     */
    private int sessionWindowSize = 5;

    /**
     * 响应结果回填到 FlowContext 的键名
     */
    private String outputKey;
    /**
     * 期望的输出 JSON Schema 或格式协议描述
     */
    private String outputSchema;

    // --- Setter & Add Methods (Protected/Public) ---

    protected void setName(String name) {
        this.name = name;
    }

    protected void setRole(String role) {
        this.role = role;
    }

    protected void setProfile(AgentProfile profile) {
        this.profile = profile;
    }

    protected void setSystemPrompt(AgentSystemPrompt<SimpleTrace> systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    protected void setChatModel(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    protected void setHandler(AgentHandler handler) {
        this.handler = handler;
    }

    /**
     * 配置容错策略
     */
    protected void setRetryConfig(int maxRetries, long retryDelayMs) {
        this.maxRetries = Math.max(1, maxRetries);
        this.retryDelayMs = Math.max(500, retryDelayMs);
    }

    /** 设置短期记忆回溯深度 */
    protected void setSessionWindowSize(int sessionWindowSize) {
        this.sessionWindowSize = Math.max(0, sessionWindowSize);
    }

    protected void setOutputKey(String val) {
        this.outputKey = val;
    }

    protected void setOutputSchema(String val) {
        this.outputSchema = val;
    }

    // --- Getter Methods (Public) ---

    public String getName() { return name; }

    public String getTraceKey() {
        if (traceKey == null) {
            traceKey = "__" + this.name;
        }
        return traceKey;
    }

    public String getRole() {
        return role;
    }

    public AgentProfile getProfile() {
        if (profile == null) profile = new AgentProfile();
        return profile;
    }

    public Locale getLocale() {
        return Locale.CHINESE;
    }

    public String getSystemPromptFor(SimpleTrace trace,FlowContext context) {
        String raw = systemPrompt.getSystemPrompt(trace);
        if (context == null || raw == null) {
            return raw;
        }

        // 动态渲染模板（如解析 #{user_name}）
        String rendered = SnelUtil.render(raw, context.vars());

        return rendered;
    }

    public ChatModel getChatModel() {
        return chatModel;
    }

    public SimpleOptions getDefaultOptions() {
        return defaultOptions;
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

    public int getSessionWindowSize() {
        return sessionWindowSize;
    }

    public String getOutputKey() {
        return outputKey;
    }

    public String getOutputSchema() {
        return outputSchema;
    }
}