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
import org.noear.solon.ai.talents.memory.MemorySolutionProvider;
import org.noear.solon.ai.talents.mount.AgentMd;
import org.noear.solon.ai.talents.mount.MountDir;
import org.noear.solon.ai.harness.permission.ToolPermission;
import org.noear.solon.ai.talents.cli.*;
import org.noear.solon.ai.talents.lsp.LspManager;
import org.noear.solon.ai.talents.lsp.LspServerParameters;
import org.noear.solon.ai.talents.lsp.LspTalent;
import org.noear.solon.ai.mcp.client.McpServerParameters;
import org.noear.solon.ai.talents.memory.MemoryTalent;
import org.noear.solon.ai.talents.mount.SkillDir;
import org.noear.solon.ai.talents.gateway.openapi.ApiSource;
import org.noear.solon.ai.talents.gateway.openapi.ApiSourceClient;
import org.noear.solon.ai.talents.gateway.OpenApiGatewayTalent;
import org.noear.solon.ai.talents.gateway.McpGatewayTalent;
import org.noear.solon.ai.talents.web.CodeSearchTalent;
import org.noear.solon.ai.talents.web.WebfetchTalent;
import org.noear.solon.ai.talents.web.WebsearchTalent;
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
    public final static String CTX_MODEL_SELECTED = "_model_selected";

    private final ReentrantLock agentLock = new ReentrantLock();

    private final HarnessOptions options;

    private final CodeTalent codeTalent;
    private final TodoTalent todoTalent;
    private final TaskTalent taskTalent;
    private final GenerateTool generateTool;

    private final WebfetchTalent webfetchTalent;
    private final WebsearchTalent websearchTalent;
    private final CodeSearchTalent codeSearchTalent;

    private final LspManager lspManager;
    private final LspTalent lspTalent;

    private final McpGatewayTalent mcpGatewayTalent;
    private final OpenApiGatewayTalent openApiGatewayTalent;

    private final MemoryTalent memoryTalent;

    private final TerminalTalent terminalTalent;
    private final SkillTalent skillTalent;

    private final CommandRegistry commandRegistry = new CommandRegistry();

    private final AgentManager agentManager;

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

    public CodeSearchTalent getCodeSearchTalent() {
        return codeSearchTalent;
    }

    public WebsearchTalent getWebsearchTalent() {
        return websearchTalent;
    }

    public WebfetchTalent getWebfetchTalent() {
        return webfetchTalent;
    }

    public McpGatewayTalent getMcpGatewayTalent() {
        return mcpGatewayTalent;
    }

    public OpenApiGatewayTalent getOpenApiGatewayTalent() {
        return openApiGatewayTalent;
    }

    public LspTalent getLspTalent() {
        return lspTalent;
    }

    public AgentSessionProvider getSessionProvider() {
        return options.getSessionProvider();
    }

    public MemorySolutionProvider getMemoryProvider() {
        return options.getMemoryProvider();
    }

    public SkillProvider getSkillProvider() {
        return options.getSkillProvider();
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

    public boolean isSandboxEnabled() {
        return options.isSandboxEnabled();
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
        return Collections.unmodifiableCollection(options.getModels().values());
    }

    public ChatConfig getModelOrNil(String name) {
        return options.getModelOrNil(name);
    }

    public ChatConfig getModelOrDef(String name) {
        return options.getModelOrDef(name);
    }

    public String getDefaultModel() {
        return options.getDefaultModel();
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
            openApiGatewayTalent.retryConfig(apiRetries);
        }
    }

    public void setMemoryEnabled(Boolean memoryEnabled) {
        options.setMemoryEnabled(memoryEnabled);
        memoryTalent.setEnabled(memoryEnabled);
    }

    public void setSandboxEnabled(Boolean sandboxEnabled) {
        options.setSandboxEnabled(sandboxEnabled);
        terminalTalent.setSandboxEnabled(sandboxEnabled);
    }

    public void setSandboxAllowUserHome(Boolean sandboxAllowUserHome) {
        options.setSandboxAllowUserHome(sandboxAllowUserHome);
        terminalTalent.setSandboxAllowUserHome(sandboxAllowUserHome);
    }

    public boolean isSandboxSystemRestrict() {
        return options.isSandboxSystemRestrict();
    }

    public void setSandboxSystemRestrict(Boolean sandboxSystemRestrict) {
        options.setSandboxSystemRestrict(sandboxSystemRestrict);
        terminalTalent.setSandboxSystemRestrict(sandboxSystemRestrict);
    }

    public void setHitlEnabled(Boolean hitlEnabled) {
        options.setHitlEnabled(hitlEnabled);
    }

    public void setSubagentEnabled(Boolean subagentEnabled) {
        options.setSubagentEnabled(subagentEnabled);
    }

    public void setBashAsyncEnabled(Boolean bashAsyncEnabled) {
        options.setBashAsyncEnabled(bashAsyncEnabled);
        terminalTalent.setBashAsyncEnabled(bashAsyncEnabled);
    }

    public void setSessionWindowSize(Integer sessionWindowSize) {
        options.setSessionWindowSize(sessionWindowSize);
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

    public void setSystemPrompt(String systemPrompt) {
        if (Assert.isNotEmpty(systemPrompt)) {
            options.setSystemPrompt(systemPrompt);
        }
    }

    // ========== 动态模型管理 ==========

    public void setDefaultModel(String defaultModel) {
        String oldDefault = options.getDefaultModel();
        options.setDefaultModel(defaultModel);

        if (mainAgent != null
                && !defaultModel.equals(oldDefault)
                && (oldDefault == null || oldDefault.equals(mainAgent.getModel().getNameOrModel()))) {
            refreshMainAgent();
        }
    }

    public void addModel(ChatConfig config) {
        if (Assert.isEmpty(config.getUserAgent())) {
            config.setUserAgent(options.getUserAgent());
        }

        options.addModel(config);

        if (mainAgent != null && mainAgent.getModel().getNameOrModel().equals(config.getNameOrModel())) {
            refreshMainAgent();
        }
    }

    public void removeModel(String name) {
        options.removeModel(name);

        if (mainAgent != null && mainAgent.getModel().getNameOrModel().equals(name)) {
            refreshMainAgent();
        }
    }

    public boolean hasModel(String name) {
        return options.hasModel(name);
    }

    /**
     * 动态授权一个工具（允许使用）
     */
    public void allowTool(String toolName) {
        if (Assert.isEmpty(toolName)) {
            return;
        }

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

    public void allowToolReset(Collection<String> tools) {
        options.getTools().clear();
        if (tools != null) {
            options.getTools().addAll(tools);
        }

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
        if (Assert.isEmpty(toolName)) {
            return;
        }

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

    public void disallowToolReset(Collection<String> disallowedTools) {
        options.getDisallowedTools().clear();
        if (disallowedTools != null) {
            options.getDisallowedTools().addAll(disallowedTools);
        }

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
        openApiGatewayTalent.addApi(apiSource);
        options.getApiServers().put(apiSource.getDocUrl(), apiSource);
    }

    /**
     * 获取指定 docUrl 的 ApiSourceClient
     */
    public ApiSourceClient getApiServer(String docUrl) {
        return openApiGatewayTalent.getApiSource(docUrl);
    }

    /**
     * 刷新指定 API 源（权限变更后）
     */
    public void refreshApiServer(String docUrl) {
        openApiGatewayTalent.refreshApi(docUrl);
    }

    /**
     * 动态移除 API 源
     */
    public void removeApiServer(String docUrl) {
        openApiGatewayTalent.removeApi(docUrl);
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
                    () -> getModelOrMain(options.getCompressionModel()),
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

        this.codeSearchTalent = new CodeSearchTalent().retryConfig(options.getMcpRetries());
        this.websearchTalent = new WebsearchTalent().retryConfig(options.getMcpRetries());
        this.webfetchTalent = new WebfetchTalent().retryConfig(options.getApiRetries());

        //lsp
        this.lspManager = new LspManager(options.getWorkspace());
        if (Assert.isNotEmpty(options.getLspServers())) {
            for (Map.Entry<String, LspServerParameters> entry : options.getLspServers().entrySet()) {
                lspManager.registerServer(entry.getKey(), entry.getValue());
            }
        }
        this.lspTalent = new LspTalent(lspManager, options.getWorkspace());
        this.lspManager.setDiagnosticsCallback(lspTalent::updateDiagnostics);

        if (options.getMemoryProvider() != null) {
            this.memoryTalent = new MemoryTalent(options.getMemoryProvider()).sessionIsolation(false);
            this.memoryTalent.setEnabled(options.isMemoryEnabled());
        } else {
            this.memoryTalent = null;
        }

        openApiGatewayTalent = new OpenApiGatewayTalent().retryConfig(options.getApiRetries());
        if (Assert.isNotEmpty(options.getApiServers())) {
            for (Map.Entry<String, ApiSource> entry : options.getApiServers().entrySet()) {
                openApiGatewayTalent.addApi(entry.getValue());
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

        if (options.getSkillProvider() == null) {
            skillTalent = new SkillTalent(options.getMountManager());
        } else {
            skillTalent = new SkillTalent(options.getSkillProvider());
        }

        agentManager = new AgentManager(options.getMountManager());

        terminalTalent.setBashAsyncEnabled(options.isBashAsyncEnabled());
        terminalTalent.setSandboxEnabled(options.isSandboxEnabled());
        terminalTalent.setSandboxAllowUserHome(options.isSandboxAllowUserHome());
        terminalTalent.setSandboxSystemRestrict(options.isSandboxSystemRestrict());

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

    public ChatModel getMainModel() {
        return getMainAgent().getModel();
    }

    public ReActAgent getMainAgent() {
        ReActAgent agent = this.mainAgent; // 引入局部变量，仅执行一次 volatile read
        if (agent == null) {
            agentLock.lock();
            try {
                if (this.mainAgent == null) {
                    this.mainAgent = createMainAgent();
                }
                agent = this.mainAgent;
            } finally {
                agentLock.unlock();
            }
        }

        return agent;
    }

    /**
     * 刷新主代理
     */
    public void refreshMainAgent() {
        agentLock.lock();
        try {
            this.mainAgent = null;
        } finally {
            agentLock.unlock();
        }
    }

    public ChatModel getModelOrMain(String modelName) {
        ChatConfig config = getModelOrDef(modelName);
        if (config == null) {
            return null;
        } else {
            return config.toChatModel();
        }
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

    private ReActRequest promptDo(Prompt prompt) {
        return getMainAgent()
                .prompt(prompt)
                .options(o -> {
                    o.retryConfig(options.getModelRetries());
                    o.maxTurns(options.getMaxTurns());
                    o.sessionWindowSize(options.getSessionWindowSize());
                });
    }

    public ReActRequest prompt(Prompt prompt) {
        return promptDo(prompt);
    }


    public ReActRequest prompt(String prompt) {
        return promptDo(Prompt.of(prompt));
    }


    public ReActRequest prompt() {
        return promptDo(null);
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
        public Builder memoryProvider(MemorySolutionProvider memoryProvider) {
            options.setMemoryProvider(memoryProvider);
            return this;
        }

        /**
         * 技能提供者（如果需要对接数据库，通过此接口适配）
         */
        public Builder skillProvider(SkillProvider skillProvider) {
            options.setSkillProvider(skillProvider);
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

        public Builder sandboxEnabled(Boolean sandboxEnabled) {
            options.setSandboxEnabled(sandboxEnabled);
            return this;
        }

        public Builder sandboxAllowUserHome(Boolean sandboxAllowUserHome) {
            options.setSandboxAllowUserHome(sandboxAllowUserHome);
            return this;
        }

        public Builder sandboxSystemRestrict(Boolean sandboxSystemRestrict) {
            options.setSandboxSystemRestrict(sandboxSystemRestrict);
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
                options.getModels().put(val.getNameOrModel(), val); //build 时再加 ua
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

        public Builder defaultModel(String defaultModel) {
            options.setDefaultModel(defaultModel);
            return this;
        }

        public HarnessEngine build() {
            Objects.nonNull(options.getSessionProvider());

            //缺省 userAgent 补尝
            if (Assert.isNotEmpty(options.getUserAgent())) {
                for (ChatConfig m1 : options.getModels().values()) {
                    if (Assert.isEmpty(m1.getUserAgent())) {
                        m1.setUserAgent(options.getUserAgent());
                    }
                }
            }

            return new HarnessEngine(options);
        }
    }
}