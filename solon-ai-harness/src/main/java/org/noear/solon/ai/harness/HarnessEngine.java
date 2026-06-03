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

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActRequest;
import org.noear.solon.ai.agent.react.intercept.HITLInterceptor;
import org.noear.solon.ai.agent.react.intercept.ContextCompressionInterceptor;
import org.noear.solon.ai.agent.react.intercept.CompressionStrategy;
import org.noear.solon.ai.agent.react.intercept.compress.CompositeCompressionStrategy;
import org.noear.solon.ai.agent.react.intercept.compress.HierarchicalCompressionStrategy;
import org.noear.solon.ai.agent.react.intercept.compress.KeyInfoExtractionStrategy;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.harness.agent.*;
import org.noear.solon.ai.harness.code.CodeTalent;
import org.noear.solon.ai.harness.command.CommandRegistry;
import org.noear.solon.ai.harness.hitl.HitlStrategy;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.ai.talents.mount.AgentMd;
import org.noear.solon.ai.talents.mount.MountDir;
import org.noear.solon.ai.harness.permission.ToolPermission;
import org.noear.solon.ai.talents.cli.*;
import org.noear.solon.ai.talents.lsp.LspManager;
import org.noear.solon.ai.talents.lsp.LspServerParameters;
import org.noear.solon.ai.talents.lsp.LspTalent;
import org.noear.solon.ai.mcp.client.McpServerParameters;
import org.noear.solon.ai.talents.memory.MemoryTalent;
import org.noear.solon.ai.talents.memory.MemorySolution;
import org.noear.solon.ai.talents.mount.SkillDir;
import org.noear.solon.ai.talents.openapi.ApiSource;
import org.noear.solon.ai.talents.openapi.ApiSourceClient;
import org.noear.solon.ai.talents.openapi.OpenApiTalent;
import org.noear.solon.ai.talents.toolgateway.McpGatewayTalent;
import org.noear.solon.ai.talents.web.CodeSearchTool;
import org.noear.solon.ai.talents.web.WebfetchTool;
import org.noear.solon.ai.talents.web.WebsearchTool;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;

import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 马具引擎
 *
 * @author noear
 * @since 4.0
 */
@Preview("3.10")
public class HarnessEngine {
    public final static String ATTR_CWD = "__cwd";

    private final ReentrantLock modelLock = new ReentrantLock();
    private final ReentrantLock agentLock = new ReentrantLock();

    private final HarnessOptions options;

    private final CodeTalent codeTalent;
    private final TodoTalent todoTalent;
    private final TaskTalent taskTalent;
    private final GenerateTool generateTool;

    private final WebfetchTool webfetchTool;
    private final WebsearchTool websearchTool;
    private final CodeSearchTool codeSearchTool;

    private final LspManager lspManager;
    private final LspTalent lspTalent;

    private final McpGatewayTalent mcpGatewayTalent;
    private final OpenApiTalent openApiTalent;

    private final MemoryTalent memoryTalent;

    private final TerminalTalent terminalTalent;
    private final SkillTalent skillTalent;

    private final CommandRegistry commandRegistry = new CommandRegistry();

    private final AgentManager agentManager;

    private volatile ChatModel mainModel; //允许运行时切换
    private volatile ReActAgent mainAgent; //允许运行时切换

    public String getName() {
        return getMainAgent().name();
    }

    public CommandRegistry getCommandRegistry() {
        return commandRegistry;
    }

    public AgentManager getAgentManager() {
        return agentManager;
    }

    public ContextCompressionInterceptor getCompressionInterceptor() {
        return options.getCompressionInterceptor();
    }

    public HITLInterceptor getHitlInterceptor() {
        return options.getHitlInterceptor();
    }

    public TerminalTalent getTerminalTalent() {
        return terminalTalent;
    }

    public SkillTalent getSkillTalent() {
        return skillTalent;
    }

    public TodoTalent getTodoTalent() {
        return todoTalent;
    }

    public TaskTalent getTaskTalent() {
        return taskTalent;
    }

    public CodeTalent getCodeTalent() {
        return codeTalent;
    }

    public MemoryTalent getMemoryTalent() {
        return memoryTalent;
    }

    public GenerateTool getGenerateTool() {
        return generateTool;
    }

    public CodeSearchTool getCodeSearchTool() {
        return codeSearchTool;
    }

    public LspTalent getLspTalent() {
        return lspTalent;
    }

    public WebsearchTool getWebsearchTool() {
        return websearchTool;
    }

    public WebfetchTool getWebfetchTool() {
        return webfetchTool;
    }

    public McpGatewayTalent getMcpGatewayTalent() {
        return mcpGatewayTalent;
    }

    public OpenApiTalent getOpenApiTalent() {
        return openApiTalent;
    }

    public AgentSessionProvider getSessionProvider() {
        return options.getSessionProvider();
    }

    public MemorySolution.Factory getMemorySolution() {
        return options.getMemorySolution();
    }

    // ========== 配置读取（代理到 options） ==========

    public String getHarnessHome() {
        return options.getHarnessHome();
    }

    public String getHarnessSessions() {
        return options.getHarnessSessions();
    }

    public String getHarnessSkills() {
        return options.getHarnessSkills();
    }

    public String getHarnessAgents() {
        return options.getHarnessAgents();
    }

    public String getHarnessCommands() {
        return options.getHarnessCommands();
    }

    public String getHarnessMemory() {
        return options.getHarnessMemory();
    }

    public String getHarnessDownload() {
        return options.getHarnessDownload();
    }

    public String getHarnessChannels() {
        return options.getHarnessChannels();
    }

    /**
     * 当前目录
     */
    public String getUserDir() {
        return System.getProperty("user.dir");
    }

    /**
     * 用户主目录
     */
    public String getUserHome() {
        return System.getProperty("user.home");
    }

    public String getUserAgent() {
        return options.getUserAgent();
    }

    public String getWorkspace() {
        return options.getWorkspace();
    }

    public String getSystemPrompt() {
        return options.getSystemPrompt();
    }

    public int getMaxTurns() {
        return options.getMaxTurns();
    }

    public boolean isAutoRethink() {
        return options.isAutoRethink();
    }

    public int getSessionWindowSize() {
        return options.getSessionWindowSize();
    }

    public int getCompressionMaxMessages() {
        return options.getCompressionMaxMessages();
    }

    public int getCompressionMaxTokens() {
        return options.getCompressionMaxTokens();
    }

    public String getCompressionModel() {
        return options.getCompressionModel();
    }

    public boolean isMemoryEnabled() {
        return options.isMemoryEnabled();
    }

    public boolean isSandboxMode() {
        return options.isSandboxMode();
    }

    public boolean isHitlEnabled() {
        return options.isHitlEnabled();
    }

    public boolean isSubagentEnabled() {
        return options.isSubagentEnabled();
    }

    public boolean isBashAsyncEnabled() {
        return options.isBashAsyncEnabled();
    }

    public int getApiRetries() {
        return options.getApiRetries();
    }

    public int getMcpRetries() {
        return options.getMcpRetries();
    }

    public int getModelRetries() {
        return options.getModelRetries();
    }

    public Collection<String> getTools() {
        return Collections.unmodifiableCollection(options.getTools());
    }

    public Collection<String> getDisallowedTools() {
        return Collections.unmodifiableCollection(options.getDisallowedTools());
    }

    public Collection<HarnessExtension> getExtensions() {
        return Collections.unmodifiableCollection(options.getExtensions());
    }

    public Collection<MountDir> getMounts() {
        return options.getMountManager().getMounts();
    }

    public MountDir getMount(String alias) {
        return options.getMountManager().getMount(alias);
    }

    public Collection<SkillDir> getSkills() {
        return options.getMountManager().getSkills();
    }

    public Collection<SkillDir> getSkillsByMount(String alias) {
        return options.getMountManager().getSkillsByMount(alias);
    }

    public Collection<AgentMd> getAgents() {
        return options.getMountManager().getAgents();
    }

    public Collection<AgentMd> getAgentsByMount(String alias) {
        return options.getMountManager().getAgentsByMount(alias);
    }

    public Map<String, McpServerParameters> getMcpServers() {
        return Collections.unmodifiableMap(options.getMcpServers());
    }

    public Map<String, ApiSource> getApiServers() {
        return Collections.unmodifiableMap(options.getApiServers());
    }

    public Map<String, LspServerParameters> getLspServers() {
        return Collections.unmodifiableMap(options.getLspServers());
    }

    public Collection<ChatConfig> getModels() {
        return Collections.unmodifiableList(options.getModels());
    }

    public ChatConfig getModelOrNil(String name) {
        return options.getModelOrNil(name);
    }

    public ChatConfig getModelOrDef(String name) {
        return options.getModelOrDef(name);
    }

    // ========== 运行时动态修改 ==========

    public void setMaxTurns(Integer maxTurns) {
        if (maxTurns != null) {
            options.setMaxTurns(maxTurns);
        }
    }

    public void setModelRetries(Integer modelRetries) {
        if (modelRetries != null) {
            options.setModelRetries(modelRetries);
        }
    }

    public void setMcpRetries(Integer mcpRetries) {
        if (mcpRetries != null) {
            options.setMcpRetries(mcpRetries);
            mcpGatewayTalent.retryConfig(mcpRetries);
        }
    }

    public void setApiRetries(Integer apiRetries) {
        if (apiRetries != null) {
            options.setApiRetries(apiRetries);
            openApiTalent.retryConfig(apiRetries);
        }
    }

    public void setHitlEnabled(Boolean hitlEnabled) {
        if (hitlEnabled != null) {
            options.setHitlEnabled(hitlEnabled);
        }
    }

    public void setSubagentEnabled(Boolean subagentEnabled) {
        if (subagentEnabled != null) {
            options.setSubagentEnabled(subagentEnabled);
        }
    }

    public void setCompressionThreshold(Integer maxMessages, Integer maxTokens) {
        if (maxMessages != null) {
            options.setCompressionMaxMessages(maxMessages);
            options.getCompressionInterceptor().setMaxMessages(maxMessages);
        }

        if (maxTokens != null) {
            options.setCompressionMaxTokens(maxTokens);
            options.getCompressionInterceptor().setMaxTokens(maxTokens);
        }
    }

    public void setSandboxMode(Boolean sandboxMode) {
        if (sandboxMode != null) {
            options.setSandboxMode(sandboxMode);
            terminalTalent.setSandboxMode(sandboxMode);
        }
    }

    public void setSystemPrompt(String systemPrompt) {
        if (Assert.isNotEmpty(systemPrompt)) {
            options.setSystemPrompt(systemPrompt);
        }
    }

    // ========== 动态模型管理 ==========

    public void addModel(ChatConfig config) {
        if (Assert.isEmpty(config.getUserAgent())) {
            config.setUserAgent(options.getUserAgent());
        }

        options.removeModel(config.getNameOrModel());
        options.addModel(config);
    }

    public void removeModel(String name) {
        options.removeModel(name);
    }

    public boolean hasModel(String name) {
        return options.hasModel(name);
    }

    /**
     * 动态授权一个工具（允许使用）
     */
    public void allowTool(String toolName) {
        if (toolName == null || toolName.trim().isEmpty()) return;

        options.getDisallowedTools().remove(toolName);
        options.getTools().add(toolName);

        // 权限变更需要重建 Agent 以重新生成工具集
        agentLock.lock();
        try {
            if (this.mainAgent != null) {
                this.mainAgent = createMainAgent();
            }
        } finally {
            agentLock.unlock();
        }
    }

    /**
     * 动态撤销一个工具的权限（禁用）
     */
    public void disallowTool(String toolName) {
        if (toolName == null || toolName.trim().isEmpty()) return;

        options.getTools().remove(toolName);
        options.getDisallowedTools().add(toolName);

        agentLock.lock();
        try {
            if (this.mainAgent != null) {
                this.mainAgent = createMainAgent();
            }
        } finally {
            agentLock.unlock();
        }
    }


    public void addMount(MountDir mount) {
        options.getMountManager().register(mount);
    }

    public void removeMount(String alias) {
        String key = alias.startsWith("@") ? alias : "@" + alias;
        agentManager.removeByMountAlias(key);
        options.getMountManager().remove(alias);
    }

    public boolean hasMount(String alias) {
        return options.getMountManager().hasMount(alias);
    }

    public void refreshMount(@Nullable String alias) {
        if (alias != null) {
            String key = alias.startsWith("@") ? alias : "@" + alias;
            agentManager.removeByMountAlias(key);
        } else {
            agentManager.clearCustomAgents();
        }
        options.getMountManager().refresh(alias);
    }


    /**
     * 动态添加 API 源
     */
    public void addApiServer(ApiSource apiSource) {
        openApiTalent.addApi(apiSource);
        options.getApiServers().put(apiSource.getDocUrl(), apiSource);
    }

    /**
     * 获取指定 docUrl 的 ApiSourceClient
     */
    public ApiSourceClient getApiServer(String docUrl) {
        return openApiTalent.getApiSource(docUrl);
    }

    /**
     * 刷新指定 API 源（权限变更后）
     */
    public void refreshApiServer(String docUrl) {
        openApiTalent.refreshApi(docUrl);
    }

    /**
     * 动态移除 API 源
     */
    public void removeApiServer(String docUrl) {
        openApiTalent.removeApi(docUrl);
        options.getApiServers().remove(docUrl);
    }

    /**
     * 动态添加 MCP 服务
     */
    public void addMcpServer(String name, McpServerParameters mcpServer) {
        mcpGatewayTalent.addMcpServer(name, mcpServer);
        options.getMcpServers().put(name, mcpServer);
    }

    public McpClientProvider getMcpServer(String name) {
        return mcpGatewayTalent.getMcpServer(name);
    }

    public void refreshMcpServer(String name) {
        mcpGatewayTalent.refreshMcpServer(name);
    }

    /**
     * 动态移除 MCP 服务（并关闭连接）
     */
    public void removeMcpServer(String name) {
        mcpGatewayTalent.removeMcpServer(name);
        options.getMcpServers().remove(name);
    }

    /**
     * 动态添加一个 LSP 服务器
     */
    public void addLspServer(String name, LspServerParameters lspServer) {
        if (lspServer == null) {
            return;
        }

        // 将配置同步到 options 中，以便后续快照使用
        options.getLspServers().put(name, lspServer);
        // 让 LspManager 运行时加载该服务器
        lspManager.registerServer(name, lspServer);

        // LSP 工具的增减会影响 Agent 的可用工具集，需重建 Agent
        agentLock.lock();
        try {
            if (this.mainAgent != null) {
                this.mainAgent = createMainAgent();
            }
        } finally {
            agentLock.unlock();
        }
    }

    /**
     * 动态移除一个 LSP 服务器
     */
    public void removeLspServer(String name) {
        if (name == null) {
            return;
        }

        options.getLspServers().remove(name);
        lspManager.unregisterServer(name);

        agentLock.lock();
        try {
            if (this.mainAgent != null) {
                this.mainAgent = createMainAgent();
            }
        } finally {
            agentLock.unlock();
        }
    }


    public void addExtension(HarnessExtension extension) {
        if (extension == null) {
            return;
        }
        options.getExtensions().add(extension);

        agentLock.lock();
        try {
            if (this.mainAgent != null) {
                this.mainAgent = createMainAgent();
            }
        } finally {
            agentLock.unlock();
        }
    }

    /**
     * 动态移除一个扩展
     */
    public void removeExtension(HarnessExtension extension) {
        if (extension == null) {
            return;
        }
        options.getExtensions().remove(extension);

        // 重建 mainAgent 以应用变更，使用 agentLock 保证线程安全
        agentLock.lock();
        try {
            if (this.mainAgent != null) {
                this.mainAgent = createMainAgent();
            }
        } finally {
            agentLock.unlock();
        }
    }


    private HarnessEngine(HarnessOptions options) {
        this.options = options;

        //上下文压缩拦截器默认处理
        if (options.getCompressionInterceptor() == null) {
            CompressionStrategy strategy = new CompositeCompressionStrategy()
                    .addStrategy(new KeyInfoExtractionStrategy())      // 提取干货（去水）
                    .addStrategy(new HierarchicalCompressionStrategy()); // 滚动更新摘要

            options.setCompressionInterceptor(new ContextCompressionInterceptor(
                    options.getCompressionMaxMessages(),
                    options.getCompressionMaxTokens(),
                    options.getModelRetries(),
                    this::getModelForCompression,
                    strategy));
        }

        //人工介入拉截器默认处理
        if (options.getHitlInterceptor() == null) {
            options.setHitlInterceptor(new HITLInterceptor().onTool("bash", new HitlStrategy()));
        }

        this.todoTalent = new TodoTalent(options.getHarnessSessions());
        this.codeTalent = new CodeTalent(this);
        this.taskTalent = new TaskTalent(this);
        this.generateTool = new GenerateTool(this);

        this.codeSearchTool = new CodeSearchTool().retryConfig(options.getMcpRetries());
        this.websearchTool = new WebsearchTool().retryConfig(options.getMcpRetries());
        this.webfetchTool = new WebfetchTool().retryConfig(options.getApiRetries());

        //lsp
        this.lspManager = new LspManager(options.getWorkspace());
        if (Assert.isNotEmpty(options.getLspServers())) {
            for (Map.Entry<String, LspServerParameters> entry : options.getLspServers().entrySet()) {
                lspManager.registerServer(entry.getKey(), entry.getValue());
            }
        }
        this.lspTalent = new LspTalent(lspManager, options.getWorkspace());
        if (this.lspTalent != null) {
            lspManager.setDiagnosticsCallback(lspTalent::updateDiagnostics);
        }

        if (options.isMemoryEnabled() && options.getMemorySolution() != null) {
            this.memoryTalent = new MemoryTalent(options.getMemorySolution()).sessionIsolation(false);
        } else {
            this.memoryTalent = null;
        }

        openApiTalent = new OpenApiTalent().retryConfig(options.getApiRetries());
        if (Assert.isNotEmpty(options.getApiServers())) {
            for (Map.Entry<String, ApiSource> entry : options.getApiServers().entrySet()) {
                openApiTalent.addApi(entry.getValue());
            }
        }

        mcpGatewayTalent = new McpGatewayTalent().retryConfig(options.getMcpRetries());

        if (Assert.isNotEmpty(options.getMcpServers())) {
            for (Map.Entry<String, McpServerParameters> entry : options.getMcpServers().entrySet()) {
                if (entry.getValue().isEnabled()) {
                    mcpGatewayTalent.addMcpServer(entry.getKey(), entry.getValue());
                }
            }
        }

        terminalTalent = new TerminalTalent(options.getMountManager());
        skillTalent = new SkillTalent(options.getMountManager());
        agentManager = new AgentManager(options.getMountManager());

        terminalTalent.setBashAsyncEnabled(options.isBashAsyncEnabled());
        terminalTalent.setSandboxMode(options.isSandboxMode());

        //mainAgent = createMainAgent(); //改为懒加载
    }

    protected ReActAgent createMainAgent() {
        AgentDefinition agentDefinition = new AgentDefinition();

        // 系统提示词
        agentDefinition.setSystemPrompt(options.getSystemPrompt());
        // 名字
        agentDefinition.getMetadata().setName(AgentDefinition.AGENT_MAIN);
        // 主代理
        agentDefinition.getMetadata().setPrimary(true);
        // 工具权限
        agentDefinition.getMetadata().getTools().addAll(options.getTools()); //允许

        ReActAgent.Builder agentBuilder = AgentFactory.create(this, agentDefinition, null);

        return agentBuilder.build();
    }


    public AgentSession getSession(String instanceId) {
        return options.getSessionProvider().getSession(instanceId);
    }

    public ReActAgent.Builder createSubagent(AgentDefinition definition) {
        return AgentFactory.create(this, definition, null);
    }

    /**
     * 获取主模型
     */
    public ChatModel getMainModel() {
        ChatModel model = this.mainModel;
        if (model == null) {
            modelLock.lock();
            try {
                if (this.mainModel == null) {
                    // 在真正用到模型时，才进行延迟校验
                    if (Assert.isEmpty(options.getModels())) {
                        throw new IllegalStateException("Missing models config. Please configure models before routing requests.");
                    }
                    this.mainModel = options.getModelOrDef(null).toChatModel();
                }
                model = this.mainModel;
            } finally {
                modelLock.unlock();
            }
        }

        return model;
    }

    /**
     * 获取模型或主模型
     */
    public ChatModel getModelOrMain(String name) {
        ChatModel currentMain = this.getMainModel();

        if (Assert.isEmpty(name) || currentMain.getConfig().getNameOrModel().equals(name)) {
            return currentMain;
        }

        return options.getModelOrDef(name).toChatModel();
    }

    public ChatModel getModelForCompression() {
        return getModelOrMain(options.getCompressionModel());
    }


    /**
     * 切换默认主模型
     */
    public void switchMainModel(String name) {
        Objects.requireNonNull(name, "name");

        ChatConfig chatConfig = options.getModelOrNil(name);
        if (chatConfig == null) {
            throw new IllegalArgumentException("The model not found: " + name);
        }

        this.mainModel = chatConfig.toChatModel();

        agentLock.lock();
        try {
            if (this.mainAgent != null) {
                this.mainAgent = createMainAgent();
            }
        } finally {
            agentLock.unlock();
        }
    }

    public ReActAgent getMainAgent() {
        ReActAgent agent = this.mainAgent; // 引入局部变量，仅执行一次 volatile read
        if (agent == null) {
            agentLock.lock();
            try {
                if (this.mainAgent == null) {
                    getMainModel();
                    this.mainAgent = createMainAgent();
                }
                agent = this.mainAgent;
            } finally {
                agentLock.unlock();
            }
        }

        return agent;
    }

    public ReActAgent getAgentOrMain(String agentName) {
        if (Assert.isEmpty(agentName)) {
            return getMainAgent();
        } else if (agentManager.hasAgent(agentName)) {
            AgentDefinition definition = agentManager.getAgent(agentName);
            return AgentFactory.create(this, definition, null).build();
        } else {
            return getMainAgent();
        }
    }

    public ReActRequest prompt(Prompt prompt) {
        return getMainAgent()
                .prompt(prompt)
                .options(o -> {
                    o.retryConfig(options.getModelRetries());
                    o.maxTurns(options.getMaxTurns());
                });
    }


    public ReActRequest prompt(String prompt) {
        return prompt(Prompt.of(prompt));
    }


    public ReActRequest prompt() {
        return getMainAgent()
                .prompt()
                .options(o -> {
                    o.retryConfig(options.getModelRetries());
                    o.maxTurns(options.getMaxTurns());
                });
    }


    public static Builder of(String workspace, String harnessHome) {
        return new Builder(workspace, harnessHome);
    }

    public static class Builder {
        private final HarnessOptions options;

        public Builder(String workspace, String harnessHome) {
            this.options = new HarnessOptions(workspace, harnessHome);
        }

        // ========== 服务注入（代理到 options） ==========

        public Builder sessionProvider(AgentSessionProvider sessionProvider) {
            options.setSessionProvider(sessionProvider);
            return this;
        }

        /**
         * 人工介入拦截器
         */
        public Builder hitlInterceptor(HITLInterceptor hitlInterceptor) {
            options.setHitlInterceptor(hitlInterceptor);
            return this;
        }

        /**
         * 心智记忆存储方案
         */
        public Builder memorySolution(MemorySolution.Factory memorySolution) {
            options.setMemorySolution(memorySolution);
            return this;
        }

        // ========== 简单属性配置（代理到 options） ==========

        public Builder systemPrompt(String systemPrompt) {
            options.setSystemPrompt(systemPrompt);
            return this;
        }

        public Builder userAgent(String userAgent) {
            options.setUserAgent(userAgent);
            return this;
        }

        public Builder maxTurns(Integer maxTurns) {
            options.setMaxTurns(maxTurns);
            return this;
        }

        public Builder autoRethink(Boolean autoRethink) {
            options.setAutoRethink(autoRethink);
            return this;
        }

        public Builder sessionWindowSize(Integer sessionWindowSize) {
            options.setSessionWindowSize(sessionWindowSize);
            return this;
        }

        public Builder compressionThreshold(Integer maxMessages, Integer maxTokens) {
            options.setCompressionMaxMessages(maxMessages);
            options.setCompressionMaxTokens(maxTokens);
            return this;
        }

        public Builder compressionModel(String compressionModel) {
            options.setCompressionModel(compressionModel);
            return this;
        }

        public Builder compressionInterceptor(ContextCompressionInterceptor compressionInterceptor) {
            options.setCompressionInterceptor(compressionInterceptor);
            return this;
        }

        public Builder memoryEnabled(Boolean memoryEnabled) {
            options.setMemoryEnabled(memoryEnabled);
            return this;
        }

        public Builder memoryIsolation(Boolean memoryIsolation) {
            options.setMemoryIsolation(memoryIsolation);
            return this;
        }

        public Builder sandboxMode(Boolean sandboxMode) {
            options.setSandboxMode(sandboxMode);
            return this;
        }

        public Builder hitlEnabled(Boolean hitlEnabled) {
            options.setHitlEnabled(hitlEnabled);
            return this;
        }

        public Builder subagentEnabled(Boolean subagentEnabled) {
            options.setSubagentEnabled(subagentEnabled);
            return this;
        }

        public Builder bashAsyncEnabled(Boolean bashAsyncEnabled) {
            options.setBashAsyncEnabled(bashAsyncEnabled);
            return this;
        }

        public Builder apiRetries(Integer apiRetries) {
            options.setApiRetries(apiRetries);
            return this;
        }

        public Builder mcpRetries(Integer mcpRetries) {
            options.setMcpRetries(mcpRetries);
            return this;
        }

        public Builder modelRetries(Integer modelRetries) {
            options.setModelRetries(modelRetries);
            return this;
        }

        // ========== 集合类配置（代理到 options） ==========

        public Builder toolsAdd(ToolPermission... val) {
            options.addTools(val);
            return this;
        }

        public Builder toolsAdd(Collection<String> tools) {
            options.getTools().addAll(tools);
            return this;
        }

        public Builder disallowedToolsAdd(ToolPermission... val) {
            options.addDisallowedTools(val);
            return this;
        }

        public Builder disallowedToolsAdd(Collection<String> tools) {
            options.getDisallowedTools().addAll(tools);
            return this;
        }

        public Builder extensionAdd(HarnessExtension val) {
            options.getExtensions().add(val);
            return this;
        }

        public Builder modelAdd(ChatConfig val) {
            options.addModel(val);
            return this;
        }

        public Builder modelAdd(Iterable<ChatConfig> models) {
            for (ChatConfig val : models) {
                options.getModels().add(val); //build 时再加 ua
            }
            return this;
        }

        public Builder mountAdd(MountDir mount) {
            options.getMountManager().register(mount);
            return this;
        }

        public Builder mcpServerAdd(String name, McpServerParameters params) {
            options.getMcpServers().put(name, params);
            return this;
        }

        public Builder apiServerAdd(String name, ApiSource source) {
            options.getApiServers().put(name, source);
            return this;
        }

        public Builder lspServerAdd(String name, LspServerParameters lspServer) {
            options.getLspServers().put(name, lspServer);
            return this;
        }

        public HarnessEngine build() {
            Objects.nonNull(options.getSessionProvider());

            //缺省 userAgent 补尝
            if (Assert.isNotEmpty(options.getUserAgent())) {
                for (ChatConfig m1 : options.getModels()) {
                    if (Assert.isEmpty(m1.getUserAgent())) {
                        m1.setUserAgent(options.getUserAgent());
                    }
                }
            }

            return new HarnessEngine(options);
        }
    }
}