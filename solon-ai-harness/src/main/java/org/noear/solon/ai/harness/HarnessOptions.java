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
package org.noear.solon.ai.harness;

import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.agent.react.intercept.HITLInterceptor;
import org.noear.solon.ai.agent.react.intercept.ContextCompressionInterceptor;
import org.noear.solon.ai.chat.CacheControl;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.permission.ToolPermission;
import org.noear.solon.ai.mcp.client.McpServerParameters;
import org.noear.solon.ai.talents.cli.SkillProvider;
import org.noear.solon.ai.talents.lsp.LspServerParameters;
import org.noear.solon.ai.talents.memory.MemorySolutionProvider;
import org.noear.solon.ai.talents.mount.MountManager;
import org.noear.solon.ai.talents.gateway.openapi.ApiSource;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Preview;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 马具运行时配置（内部使用）
 *
 * @author noear
 * @since 4.0.0
 */
@Preview("4.0")
class HarnessOptions implements Serializable {

    // ========== 基础路径 ==========
    private final String harnessHome;
    private volatile String workspace = "work";

    // ========== 提示词 ==========
    private volatile String systemPrompt;
    private volatile String userAgent;

    // ========== 主代理工具权限 ==========
    private Set<String> tools = new CopyOnWriteArraySet<>();

    // 禁用工具（全局）
    private Set<String> disallowedTools = new CopyOnWriteArraySet<>();

    // ========== 执行控制 ==========
    private volatile int maxTurns = 20;
    private volatile boolean autoRethink = true;

    // ========== 会话与压缩 ==========
    private volatile int sessionWindowSize = 8;
    private volatile int compressionMaxMessages = 30;
    private volatile int compressionMaxTokens = 30_000;
    private volatile String compressionModel; //压缩大模型

    // ========== 记忆 ==========
    private volatile boolean memoryEnabled = true;

    // ========== 安全与模式 ==========
    private volatile boolean sandboxEnabled = true;
    private volatile boolean sandboxAllowUserHome = true;
    private volatile boolean sandboxSystemRestrict = true;

    private volatile boolean hitlEnabled = false;
    private volatile boolean subagentEnabled = true;
    private volatile boolean bashAsyncEnabled = false;

    // ========== 重试配置 ==========
    private volatile int apiRetries = 3;
    private volatile int mcpRetries = 3;
    private volatile int modelRetries = 3;

    // ========== 缓存控制 ==========
    private volatile CacheControl cacheControl;

    // ========== 集合类配置 ==========
    private final MountManager mountManager;
    private volatile String defaultModel;
    private final Map<String, ChatConfig> models = new ConcurrentHashMap<>();
    private final Map<String, McpServerParameters> mcpServers = new ConcurrentHashMap<>();
    private final Map<String, ApiSource> apiServers = new ConcurrentHashMap<>();
    private final Map<String, LspServerParameters> lspServers = new ConcurrentHashMap<>();
    private final List<HarnessExtension> extensions = new CopyOnWriteArrayList<>();

    // ========== 服务注入 ==========
    private AgentSessionProvider sessionProvider;
    private ContextCompressionInterceptor compressionInterceptor;
    private HITLInterceptor hitlInterceptor;
    private MemorySolutionProvider memoryProvider;
    private SkillProvider skillProvider;

    HarnessOptions(String workspace, String harnessHome) {
        if (Assert.isEmpty(harnessHome)) {
            harnessHome = ".solon/";
        } else if (!harnessHome.endsWith("/")) {
            harnessHome = harnessHome + "/";
        }

        this.workspace = workspace;
        this.harnessHome = harnessHome;
        this.mountManager = new MountManager(workspace);
    }

    // ========== 派生路径属性 ==========

    String getHarnessHome() {
        return harnessHome;
    }

    String getHarnessSessions() {
        return harnessHome + "sessions/";
    }

    String getHarnessSkills() {
        return harnessHome + "skills/";
    }

    String getHarnessAgents() {
        return harnessHome + "agents/";
    }

    String getHarnessCommands() {
        return harnessHome + "commands/";
    }

    String getHarnessMemory() {
        return harnessHome + "memory/";
    }

    String getHarnessDownload() {
        return harnessHome + "download/";
    }

    String getHarnessChannels() {
        return harnessHome + "channels/";
    }

    // ========== getter / setter ==========

    String getWorkspace() {
        return workspace;
    }

    String getSystemPrompt() {
        return systemPrompt;
    }

    void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    String getUserAgent() {
        return userAgent;
    }

    void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    Set<String> getTools() {
        return tools;
    }

    Set<String> getDisallowedTools() {
        return disallowedTools;
    }

    int getMaxTurns() {
        return maxTurns;
    }

    void setMaxTurns(Integer maxTurns) {
        if (maxTurns != null) {
            this.maxTurns = maxTurns;
        }
    }

    boolean isAutoRethink() {
        return autoRethink;
    }

    void setAutoRethink(Boolean autoRethink) {
        if (autoRethink != null) {
            this.autoRethink = autoRethink;
        }
    }

    int getSessionWindowSize() {
        return sessionWindowSize;
    }

    void setSessionWindowSize(Integer sessionWindowSize) {
        if (sessionWindowSize != null) {
            this.sessionWindowSize = sessionWindowSize;
        }
    }

    int getCompressionMaxMessages() {
        return compressionMaxMessages;
    }

    void setCompressionMaxMessages(Integer compressionMaxMessages) {
        if (compressionMaxMessages != null) {
            this.compressionMaxMessages = compressionMaxMessages;
        }
    }

    int getCompressionMaxTokens() {
        return compressionMaxTokens;
    }

    void setCompressionMaxTokens(Integer compressionMaxTokens) {
        if (compressionMaxTokens != null) {
            this.compressionMaxTokens = compressionMaxTokens;
        }
    }

    String getCompressionModel() {
        return compressionModel;
    }

    void setCompressionModel(String compressionModel) {
        this.compressionModel = compressionModel;
    }

    boolean isMemoryEnabled() {
        return memoryEnabled;
    }

    void setMemoryEnabled(Boolean memoryEnabled) {
        if (memoryEnabled != null) {
            this.memoryEnabled = memoryEnabled;
        }
    }

    boolean isSandboxEnabled() {
        return sandboxEnabled;
    }

    void setSandboxEnabled(Boolean sandboxEnabled) {
        if (sandboxEnabled != null) {
            this.sandboxEnabled = sandboxEnabled;
        }
    }

    boolean isSandboxAllowUserHome() {
        return sandboxAllowUserHome;
    }

    void setSandboxAllowUserHome(Boolean sandboxAllowUserHome) {
        if (sandboxAllowUserHome != null) {
            this.sandboxAllowUserHome = sandboxAllowUserHome;
        }
    }

    boolean isSandboxSystemRestrict() {
        return sandboxSystemRestrict;
    }

    void setSandboxSystemRestrict(Boolean sandboxSystemRestrict) {
        if (sandboxSystemRestrict != null) {
            this.sandboxSystemRestrict = sandboxSystemRestrict;
        }
    }

    boolean isHitlEnabled() {
        return hitlEnabled;
    }

    void setHitlEnabled(Boolean hitlEnabled) {
        if (hitlEnabled != null) {
            this.hitlEnabled = hitlEnabled;
        }
    }

    boolean isSubagentEnabled() {
        return subagentEnabled;
    }

    void setSubagentEnabled(Boolean subagentEnabled) {
        if (subagentEnabled != null) {
            this.subagentEnabled = subagentEnabled;
        }
    }

    boolean isBashAsyncEnabled() {
        return bashAsyncEnabled;
    }

    void setBashAsyncEnabled(Boolean bashAsyncEnabled) {
        if (bashAsyncEnabled != null) {
            this.bashAsyncEnabled = bashAsyncEnabled;
        }
    }

    int getApiRetries() {
        return apiRetries;
    }

    void setApiRetries(Integer apiRetries) {
        if (apiRetries != null) {
            this.apiRetries = apiRetries;
        }
    }

    int getMcpRetries() {
        return mcpRetries;
    }

    void setMcpRetries(Integer mcpRetries) {
        if (mcpRetries != null) {
            this.mcpRetries = mcpRetries;
        }
    }

    int getModelRetries() {
        return modelRetries;
    }

    void setModelRetries(Integer modelRetries) {
        if (modelRetries != null) {
            this.modelRetries = modelRetries;
        }
    }

    // ========== 缓存控制 ==========

    CacheControl getCacheControl() {
        return cacheControl;
    }

    void setCacheControl(CacheControl cacheControl) {
        this.cacheControl = cacheControl;
    }

    List<HarnessExtension> getExtensions() {
        return extensions;
    }

    Map<String, ChatConfig> getModels() {
        return models;
    }

    String getDefaultModel() {
        return defaultModel;
    }

    void setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel;
    }

    public MountManager getMountManager() {
        return mountManager;
    }

    Map<String, McpServerParameters> getMcpServers() {
        return mcpServers;
    }

    Map<String, ApiSource> getApiServers() {
        return apiServers;
    }

    Map<String, LspServerParameters> getLspServers() {
        return lspServers;
    }

    // ========== 集合操作方法 ==========

    void addTools(ToolPermission... toolPermissions) {
        for (ToolPermission p1 : toolPermissions) {
            tools.add(p1.getName());
        }
    }

    void addDisallowedTools(ToolPermission... toolPermissions) {
        for (ToolPermission p1 : toolPermissions) {
            disallowedTools.add(p1.getName());
        }
    }

    void addModel(ChatConfig chatConfig) {
        if (Assert.isEmpty(chatConfig.getUserAgent())) {
            chatConfig.setUserAgent(this.userAgent);
        }

        models.put(chatConfig.getNameOrModel(), chatConfig);
    }

    void removeModel(String modelName) {
        models.remove(modelName);
    }

    boolean hasModel(String modelName) {
        return models.containsKey(modelName);
    }

    ChatConfig getModelOrNil(String modelName) {
        if (models.isEmpty()) {
            return null;
        }

        if (Assert.isEmpty(modelName)) {
            return getDefaultModelConfig();
        }

        ChatConfig c = models.get(modelName);
        if (c != null && c.isEnabled()) {
            return c;
        }

        return null;
    }

    ChatConfig getModelOrDef(String modelName) {
        if (models.isEmpty()) {
            return null;
        }

        if (Assert.isEmpty(modelName)) {
            return getDefaultModelConfig();
        }

        ChatConfig c = models.get(modelName);
        if (c != null && c.isEnabled()) {
            return c;
        }

        return getDefaultModelConfig();
    }

    /**
     * 获取默认模型配置：优先 defaultModel 指定的，否则取 Map 中的第一个值
     */
    private ChatConfig getDefaultModelConfig() {
        if (Assert.isNotEmpty(defaultModel)) {
            ChatConfig c = models.get(defaultModel);
            if (c != null) {
                return c;
            }
        }

        // fallback 到第一个
        return models.values().iterator().next();
    }

    // ========== 服务注入 getter / setter ==========

    AgentSessionProvider getSessionProvider() {
        return sessionProvider;
    }

    void setSessionProvider(AgentSessionProvider sessionProvider) {
        this.sessionProvider = sessionProvider;
    }

    ContextCompressionInterceptor getCompressionInterceptor() {
        return compressionInterceptor;
    }

    void setCompressionInterceptor(ContextCompressionInterceptor compressionInterceptor) {
        this.compressionInterceptor = compressionInterceptor;
    }

    HITLInterceptor getHitlInterceptor() {
        return hitlInterceptor;
    }

    void setHitlInterceptor(HITLInterceptor hitlInterceptor) {
        this.hitlInterceptor = hitlInterceptor;
    }

    MemorySolutionProvider getMemoryProvider() {
        return memoryProvider;
    }

    void setMemoryProvider(MemorySolutionProvider memoryProvider) {
        this.memoryProvider = memoryProvider;
    }

    SkillProvider getSkillProvider() {
        return skillProvider;
    }

    void setSkillProvider(SkillProvider skillProvider) {
        this.skillProvider = skillProvider;
    }
}