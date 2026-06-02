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
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.permission.ToolPermission;
import org.noear.solon.ai.mcp.client.McpServerParameters;
import org.noear.solon.ai.talents.lsp.LspServerParameters;
import org.noear.solon.ai.talents.memory.MemorySolution;
import org.noear.solon.ai.talents.mount.MountManager;
import org.noear.solon.ai.talents.openapi.ApiSource;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Preview;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

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
    private List<String> tools = new CopyOnWriteArrayList<>();

    // 禁用工具（全局）
    private List<String> disallowedTools = new CopyOnWriteArrayList<>();

    // ========== 执行控制 ==========
    private volatile int maxTurns = 30;
    private volatile boolean autoRethink = true;

    // ========== 会话与压缩 ==========
    private volatile int sessionWindowSize = 8;
    private volatile int compressionMaxMessages = 30;
    private volatile int compressionMaxTokens = 30_000;
    private volatile String compressionModel; //压缩大模型

    // ========== 记忆 ==========
    private volatile boolean memoryIsolation = false;
    private volatile boolean memoryEnabled = true;

    // ========== 安全与模式 ==========
    private volatile boolean sandboxMode = true;
    private volatile boolean hitlEnabled = false;
    private volatile boolean subagentEnabled = true;
    private volatile boolean bashAsyncEnabled = false;

    // ========== 重试配置 ==========
    private volatile int apiRetries = 3;
    private volatile int mcpRetries = 3;
    private volatile int modelRetries = 3;

    // ========== 集合类配置 ==========
    private final List<HarnessExtension> extensions = new CopyOnWriteArrayList<>();
    private final List<ChatConfig> models = new CopyOnWriteArrayList<>();
    private final MountManager mountManager;
    private final Map<String, McpServerParameters> mcpServers = new ConcurrentHashMap<>();
    private final Map<String, ApiSource> apiServers = new ConcurrentHashMap<>();
    private final Map<String, LspServerParameters> lspServers = new ConcurrentHashMap<>();

    // ========== 服务注入 ==========
    private AgentSessionProvider sessionProvider;
    private ContextCompressionInterceptor compressionInterceptor;
    private HITLInterceptor hitlInterceptor;
    private MemorySolution.Factory memorySolution;

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

    List<String> getTools() {
        return tools;
    }

    List<String> getDisallowedTools() {
        return disallowedTools;
    }

    int getMaxTurns() {
        return maxTurns;
    }

    void setMaxTurns(int maxTurns) {
        this.maxTurns = maxTurns;
    }

    boolean isAutoRethink() {
        return autoRethink;
    }

    void setAutoRethink(boolean autoRethink) {
        this.autoRethink = autoRethink;
    }

    int getSessionWindowSize() {
        return sessionWindowSize;
    }

    void setSessionWindowSize(int sessionWindowSize) {
        this.sessionWindowSize = sessionWindowSize;
    }

    int getCompressionMaxMessages() {
        return compressionMaxMessages;
    }

    void setCompressionMaxMessages(int compressionMaxMessages) {
        this.compressionMaxMessages = compressionMaxMessages;
    }

    int getCompressionMaxTokens() {
        return compressionMaxTokens;
    }

    void setCompressionMaxTokens(int compressionMaxTokens) {
        this.compressionMaxTokens = compressionMaxTokens;
    }

    String getCompressionModel() {
        return compressionModel;
    }

    void setCompressionModel(String compressionModel) {
        this.compressionModel = compressionModel;
    }

    boolean isMemoryIsolation() {
        return memoryIsolation;
    }

    void setMemoryIsolation(boolean memoryIsolation) {
        this.memoryIsolation = memoryIsolation;
    }

    boolean isMemoryEnabled() {
        return memoryEnabled;
    }

    void setMemoryEnabled(boolean memoryEnabled) {
        this.memoryEnabled = memoryEnabled;
    }

    boolean isSandboxMode() {
        return sandboxMode;
    }

    void setSandboxMode(boolean sandboxMode) {
        this.sandboxMode = sandboxMode;
    }

    boolean isHitlEnabled() {
        return hitlEnabled;
    }

    void setHitlEnabled(boolean hitlEnabled) {
        this.hitlEnabled = hitlEnabled;
    }

    boolean isSubagentEnabled() {
        return subagentEnabled;
    }

    void setSubagentEnabled(boolean subagentEnabled) {
        this.subagentEnabled = subagentEnabled;
    }

    boolean isBashAsyncEnabled() {
        return bashAsyncEnabled;
    }

    void setBashAsyncEnabled(boolean bashAsyncEnabled) {
        this.bashAsyncEnabled = bashAsyncEnabled;
    }

    int getApiRetries() {
        return apiRetries;
    }

    void setApiRetries(int apiRetries) {
        this.apiRetries = apiRetries;
    }

    int getMcpRetries() {
        return mcpRetries;
    }

    void setMcpRetries(int mcpRetries) {
        this.mcpRetries = mcpRetries;
    }

    int getModelRetries() {
        return modelRetries;
    }

    void setModelRetries(int modelRetries) {
        this.modelRetries = modelRetries;
    }

    List<HarnessExtension> getExtensions() {
        return extensions;
    }

    List<ChatConfig> getModels() {
        return models;
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

    void addExtension(HarnessExtension extension) {
        this.extensions.add(extension);
    }

    void addApiSource(String name, ApiSource apiSource) {
        apiServers.put(name, apiSource);
    }

    void addMcpServer(String name, McpServerParameters mcpParameters) {
        mcpServers.put(name, mcpParameters);
    }

    void addLspServer(String name, LspServerParameters lspParameters) {
        lspServers.put(name, lspParameters);
    }

    void addModel(ChatConfig chatConfig) {
        if (Assert.isEmpty(chatConfig.getUserAgent())) {
            chatConfig.setUserAgent(this.userAgent);
        }

        models.add(chatConfig);
    }

    void removeModel(String modelName) {
        models.removeIf(m -> m.getNameOrModel().equals(modelName));
    }

    boolean hasModel(String modelName) {
        return models.stream()
                .filter(m -> m.getNameOrModel().equals(modelName))
                .findAny()
                .isPresent();
    }

    ChatConfig getModelOrNil(String modelName) {
        if (models.isEmpty()) {
            return null;
        }

        if (Assert.isEmpty(modelName)) {
            return models.get(0);
        }

        for (ChatConfig c : models) {
            if (c.isEnabled()) {
                //只检查已启用的
                if (c.getNameOrModel().equals(modelName)) {
                    return c;
                }
            }
        }

        return null;
    }

    ChatConfig getModelOrDef(String modelName) {
        if (models.isEmpty()) {
            return null;
        }

        if (Assert.isEmpty(modelName)) {
            return models.get(0);
        }

        for (ChatConfig c : models) {
            if (c.isEnabled()) {
                //只检查已启用的
                if (c.getNameOrModel().equals(modelName)) {
                    return c;
                }
            }
        }

        return models.get(0);
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

    MemorySolution.Factory getMemorySolution() {
        return memorySolution;
    }

    void setMemorySolution(MemorySolution.Factory memorySolution) {
        this.memorySolution = memorySolution;
    }
}