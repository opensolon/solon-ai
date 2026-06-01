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
import org.noear.solon.ai.agent.react.intercept.SummarizationInterceptor;
import org.noear.solon.ai.agent.react.intercept.SummarizationStrategy;
import org.noear.solon.ai.agent.react.intercept.summarize.CompositeSummarizationStrategy;
import org.noear.solon.ai.agent.react.intercept.summarize.HierarchicalSummarizationStrategy;
import org.noear.solon.ai.agent.react.intercept.summarize.KeyInfoExtractionStrategy;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.harness.agent.*;
import org.noear.solon.ai.harness.code.CodeSkill;
import org.noear.solon.ai.harness.command.CommandRegistry;
import org.noear.solon.ai.harness.hitl.HitlStrategy;
import org.noear.solon.ai.harness.mount.MountDo;
import org.noear.solon.ai.harness.permission.ToolPermission;
import org.noear.solon.ai.skills.cli.PoolType;
import org.noear.solon.ai.skills.lsp.LspManager;
import org.noear.solon.ai.skills.lsp.LspServerParameters;
import org.noear.solon.ai.skills.lsp.LspSkill;
import org.noear.solon.ai.mcp.client.McpServerParameters;
import org.noear.solon.ai.skills.cli.PoolManager;
import org.noear.solon.ai.skills.cli.CliSkillProvider;
import org.noear.solon.ai.skills.cli.TodoSkill;
import org.noear.solon.ai.skills.memory.MemorySkill;
import org.noear.solon.ai.skills.memory.MemorySolution;
import org.noear.solon.ai.skills.openapi.ApiSource;
import org.noear.solon.ai.skills.openapi.OpenApiSkill;
import org.noear.solon.ai.skills.toolgateway.McpGatewaySkill;
import org.noear.solon.ai.skills.web.CodeSearchTool;
import org.noear.solon.ai.skills.web.WebfetchTool;
import org.noear.solon.ai.skills.web.WebsearchTool;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Preview;

import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 马具引擎
 *
 * @author noear
 */
@Preview("3.10")
public class HarnessEngine {
    public final static String ATTR_CWD = "__cwd";

    private final ReentrantLock modelLock = new ReentrantLock();
    private final ReentrantLock agentLock = new ReentrantLock();

    private final HarnessOptions options;

    private final CodeSkill codeSkill;
    private final TodoSkill todoSkill;
    private final TaskSkill taskSkill;
    private final GenerateTool generateTool;

    private final WebfetchTool webfetchTool;
    private final WebsearchTool websearchTool;
    private final CodeSearchTool codeSearchTool;

    private final LspManager lspManager;
    private final LspSkill lspSkill;

    private final McpGatewaySkill mcpGatewaySkill;
    private final OpenApiSkill openApiSkill;

    private final MemorySkill memorySkill;

    private final CliSkillProvider cliSkills = new CliSkillProvider();

    private final CommandRegistry commandRegistry = new CommandRegistry();

    private final AgentManager agentManager = new AgentManager();

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

    public SummarizationInterceptor getSummarizationInterceptor() {
        return options.getSummarizationInterceptor();
    }

    public HITLInterceptor getHitlInterceptor() {
        return options.getHitlInterceptor();
    }

    public CliSkillProvider getCliSkills() {
        return cliSkills;
    }

    public PoolManager getPoolManager() {
        return cliSkills.getPoolManager();
    }

    public TodoSkill getTodoSkill() {
        return todoSkill;
    }

    public TaskSkill getTaskSkill() {
        return taskSkill;
    }

    public CodeSkill getCodeSkill() {
        return codeSkill;
    }

    public MemorySkill getMemorySkill() {
        return memorySkill;
    }

    public GenerateTool getGenerateTool() {
        return generateTool;
    }

    public CodeSearchTool getCodeSearchTool() {
        return codeSearchTool;
    }

    public LspSkill getLspSkill() {
        return lspSkill;
    }

    public WebsearchTool getWebsearchTool() {
        return websearchTool;
    }

    public WebfetchTool getWebfetchTool() {
        return webfetchTool;
    }

    public McpGatewaySkill getMcpGatewaySkill() {
        return mcpGatewaySkill;
    }

    public OpenApiSkill getOpenApiSkill() {
        return openApiSkill;
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

    public String getWorkspace() {
        return options.getWorkspace();
    }

    public String getSystemPrompt() {
        return options.getSystemPrompt();
    }

    public String getUserAgent() {
        return options.getUserAgent();
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

    public int getSummaryWindowSize() {
        return options.getSummaryWindowSize();
    }

    public int getSummaryWindowToken() {
        return options.getSummaryWindowToken();
    }

    public String getSummaryModel() {
        return options.getSummaryModel();
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

    public List<String> getTools() {
        return options.getTools();
    }

    public List<String> getDisallowedTools() {
        return options.getDisallowedTools();
    }

    public List<HarnessExtension> getExtensions() {
        return options.getExtensions();
    }

    public List<ChatConfig> getModels() {
        return options.getModels();
    }

    public Map<String, MountDo> getMountPools() {
        return options.getMountPools();
    }

    public Map<String, McpServerParameters> getMcpServers() {
        return options.getMcpServers();
    }

    public Map<String, ApiSource> getApiServers() {
        return options.getApiServers();
    }

    public Map<String, LspServerParameters> getLspServers() {
        return options.getLspServers();
    }

    public ChatConfig getModelOrNil(String name) {
        return options.getModelOrNil(name);
    }

    public ChatConfig getModelOrDef(String name) {
        return options.getModelOrDef(name);
    }

    // ========== 运行时动态修改 ==========

    public void setMaxTurns(int val) {
        options.setMaxTurns(val);
    }

    public void setHitlEnabled(boolean val) {
        options.setHitlEnabled(val);
    }

    public void setSubagentEnabled(boolean val) {
        options.setSubagentEnabled(val);
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


    /**
     * 动态添加 API 源
     */
    public void addApi(ApiSource apiSource) {
        openApiSkill.addApi(apiSource);
        options.addApiSource(apiSource.getDocUrl(), apiSource);
    }

    /**
     * 动态移除 API 源
     */
    public void removeApi(String docUrl) {
        openApiSkill.removeApi(docUrl);
        options.getApiServers().remove(docUrl);
    }

    /**
     * 动态添加 MCP 服务
     */
    public void addMcpServer(String name, McpServerParameters mcpServer) {
        mcpGatewaySkill.addMcpServer(name, mcpServer);
        options.addMcpServer(name, mcpServer);
    }

    /**
     * 动态移除 MCP 服务（并关闭连接）
     */
    public void removeMcpServer(String name) {
        mcpGatewaySkill.removeMcpServer(name);
        options.getMcpServers().remove(name);
    }

    public void addExtension(HarnessExtension extension) {
        options.addExtension(extension);

        // 如果主代理还没懒加载触发，这里无需提前创建
        if (this.mainAgent != null) {
            this.mainAgent = createMainAgent();
        }
    }

    private HarnessEngine(HarnessOptions options) {
        this.options = options;

        //上下文摘要拦截器默认处理
        if (options.getSummarizationInterceptor() == null) {
            SummarizationStrategy strategy = new CompositeSummarizationStrategy()
                    .addStrategy(new KeyInfoExtractionStrategy(this::getModelForSummary).retryConfig(options.getModelRetries()))      // 提取干货（去水）
                    .addStrategy(new HierarchicalSummarizationStrategy(this::getModelForSummary).retryConfig(options.getModelRetries())); // 滚动更新摘要

            options.setSummarizationInterceptor(new SummarizationInterceptor(
                    options.getSummaryWindowSize(),
                    options.getSummaryWindowToken(),
                    strategy));
        }

        //人工介入拉截器默认处理
        if (options.getHitlInterceptor() == null) {
            options.setHitlInterceptor(new HITLInterceptor().onTool("bash", new HitlStrategy()));
        }

        this.todoSkill = new TodoSkill(options.getHarnessSessions());
        this.codeSkill = new CodeSkill(this);
        this.taskSkill = new TaskSkill(this);
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
        this.lspSkill = lspManager.hasServers() ? new LspSkill(lspManager, options.getWorkspace()) : null;
        if (this.lspSkill != null) {
            lspManager.setDiagnosticsCallback(lspSkill::updateDiagnostics);
        }

        if (options.isMemoryEnabled() && options.getMemorySolution() != null) {
            this.memorySkill = new MemorySkill(options.getMemorySolution()).sessionIsolation(false);
        } else {
            this.memorySkill = null;
        }

        openApiSkill = new OpenApiSkill().retryConfig(options.getApiRetries());
        if (Assert.isNotEmpty(options.getApiServers())) {
            for (Map.Entry<String, ApiSource> entry : options.getApiServers().entrySet()) {
                openApiSkill.addApi(entry.getValue());
            }
        }

        mcpGatewaySkill = new McpGatewaySkill().retryConfig(options.getMcpRetries());

        if (Assert.isNotEmpty(options.getMcpServers())) {
            for (Map.Entry<String, McpServerParameters> entry : options.getMcpServers().entrySet()) {
                if (entry.getValue().isEnabled()) {
                    mcpGatewaySkill.addMcpServer(entry.getKey(), entry.getValue());
                }
            }
        }


        cliSkills.bashAsyncEnabled(options.isBashAsyncEnabled());
        cliSkills.getTerminalSkill().setSandboxMode(options.isSandboxMode());

        if (Assert.isNotEmpty(options.getMountPools())) {
            options.getMountPools().forEach((alias, mount) -> {
                if (mount.getType() == PoolType.SUBAGENTS) {
                    agentManager.agentPool(Paths.get(mount.getPath()));
                }

                cliSkills.getPoolManager().register(alias, mount.getType(), mount.getPath());
            });
        }

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

    public ChatModel getModelForSummary() {
        return getModelOrMain(options.getSummaryModel());
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

        // 如果 mainAgent 之前已经被懒加载初始化过了，则立刻刷新它
        if (this.mainAgent != null) {
            this.mainAgent = createMainAgent();
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
        return getMainAgent().prompt(prompt);
    }


    public ReActRequest prompt(String prompt) {
        return prompt(Prompt.of(prompt));
    }


    public ReActRequest prompt() {
        return getMainAgent().prompt();
    }


    public static Builder of(String harnessHome) {
        return new Builder(harnessHome);
    }

    public static class Builder {
        private final HarnessOptions options;

        public Builder(String harnessHome) {
            this.options = new HarnessOptions(harnessHome);
        }

        // ========== 服务注入（代理到 options） ==========

        public Builder sessionProvider(AgentSessionProvider sessionProvider) {
            options.setSessionProvider(sessionProvider);
            return this;
        }

        /**
         * 摘要拦截器
         */
        public Builder summarizationInterceptor(SummarizationInterceptor summarizationInterceptor) {
            options.setSummarizationInterceptor(summarizationInterceptor);
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

        public Builder workspace(String val) {
            options.setWorkspace(val);
            return this;
        }

        public Builder systemPrompt(String val) {
            options.setSystemPrompt(val);
            return this;
        }

        public Builder userAgent(String val) {
            options.setUserAgent(val);
            return this;
        }

        public Builder maxTurns(int val) {
            options.setMaxTurns(val);
            return this;
        }

        public Builder autoRethink(boolean val) {
            options.setAutoRethink(val);
            return this;
        }

        public Builder sessionWindowSize(int val) {
            options.setSessionWindowSize(val);
            return this;
        }

        public Builder summaryWindowSize(int val) {
            options.setSummaryWindowSize(val);
            return this;
        }

        public Builder summaryWindowToken(int val) {
            options.setSummaryWindowToken(val);
            return this;
        }

        public Builder summaryModel(String val) {
            options.setSummaryModel(val);
            return this;
        }

        public Builder memoryEnabled(boolean val) {
            options.setMemoryEnabled(val);
            return this;
        }

        public Builder memoryIsolation(boolean val) {
            options.setMemoryIsolation(val);
            return this;
        }

        public Builder sandboxMode(boolean val) {
            options.setSandboxMode(val);
            return this;
        }

        public Builder hitlEnabled(boolean val) {
            options.setHitlEnabled(val);
            return this;
        }

        public Builder subagentEnabled(boolean val) {
            options.setSubagentEnabled(val);
            return this;
        }

        public Builder bashAsyncEnabled(boolean val) {
            options.setBashAsyncEnabled(val);
            return this;
        }

        public Builder apiRetries(int val) {
            options.setApiRetries(val);
            return this;
        }

        public Builder mcpRetries(int val) {
            options.setMcpRetries(val);
            return this;
        }

        public Builder modelRetries(int val) {
            options.setModelRetries(val);
            return this;
        }

        // ========== 集合类配置（代理到 options） ==========

        public Builder toolsAdd(ToolPermission... val) {
            options.addTools(val);
            return this;
        }

        public Builder disallowedToolsAdd(ToolPermission... val) {
            options.addDisallowedTools(val);
            return this;
        }

        public Builder extensionAdd(HarnessExtension val) {
            options.addExtension(val);
            return this;
        }

        public Builder modelAdd(ChatConfig val) {
            options.addModel(val);
            return this;
        }

        public Builder mountPoolAdd(String alias, PoolType type, String path) {
            options.addMountPool(alias, type, path);
            return this;
        }

        public Builder mcpServerAdd(String name, McpServerParameters params) {
            options.addMcpServer(name, params);
            return this;
        }

        public Builder apiSourceAdd(String name, ApiSource source) {
            options.addApiSource(name, source);
            return this;
        }

        public Builder lspServerAdd(String name, LspServerParameters params) {
            options.addLspServer(name, params);
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