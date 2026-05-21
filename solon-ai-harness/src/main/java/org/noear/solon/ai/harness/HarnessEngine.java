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
import org.noear.solon.ai.skills.lsp.LspManager;
import org.noear.solon.ai.skills.lsp.LspServerParameters;
import org.noear.solon.ai.skills.lsp.LspSkill;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.ai.mcp.client.McpProviders;
import org.noear.solon.ai.skills.cli.PoolManager;
import org.noear.solon.ai.skills.cli.CliSkillProvider;
import org.noear.solon.ai.skills.cli.TodoSkill;
import org.noear.solon.ai.skills.memory.MemorySkill;
import org.noear.solon.ai.skills.memory.MemorySolution;
import org.noear.solon.ai.skills.restapi.ApiSource;
import org.noear.solon.ai.skills.restapi.RestApiSkill;
import org.noear.solon.ai.skills.toolgateway.ToolGatewaySkill;
import org.noear.solon.ai.skills.web.CodeSearchTool;
import org.noear.solon.ai.skills.web.WebfetchTool;
import org.noear.solon.ai.skills.web.WebsearchTool;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Preview;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 马具引擎
 *
 * @author noear
 */
@Preview("3.10")
public class HarnessEngine {
    public final static String ATTR_CWD = "__cwd";

    private final ReentrantLock locker = new ReentrantLock();

    private final AgentSessionProvider sessionProvider;
    private final HarnessProperties props;

    private final CodeSkill codeSkill;
    private final TodoSkill todoSkill;
    private final TaskSkill taskSkill;
    private final GenerateTool generateTool;

    private final WebfetchTool webfetchTool;
    private final WebsearchTool websearchTool;
    private final CodeSearchTool codeSearchTool;

    private final LspManager lspManager;
    private final LspSkill lspSkill;

    private final ToolGatewaySkill mcpGatewaySkill;
    private final RestApiSkill restApiSkill;

    private final MemorySkill memorySkill;

    private final CliSkillProvider cliSkills = new CliSkillProvider();

    private final SummarizationInterceptor summarizationInterceptor;
    private final HITLInterceptor hitlInterceptor;

    private final CommandRegistry commandRegistry = new CommandRegistry();

    private final AgentManager agentManager;

    private volatile ChatModel mainModel; //允许运行时切换
    private volatile ReActAgent mainAgent; //允许运行时切换

    public String getName() {
        return getMainAgent().name();
    }

    public HarnessProperties getProps() {
        return props;
    }

    /**
     * 获取主模型
     */
    public ChatModel getMainModel() {
        return mainModel;
    }

    /**
     * 获取模型或主模型
     */
    public ChatModel getModelOrMain(String name) {
        ChatModel currentMain = this.mainModel;

        if (Assert.isEmpty(name) || currentMain.getConfig().getNameOrModel().equals(name)) {
            return currentMain;
        }

        return props.getModelOrDef(name).toChatModel();
    }

    public ChatModel getModelForSummary() {
        return getModelOrMain(props.getSummaryModel());
    }

    public CommandRegistry getCommandRegistry() {
        return commandRegistry;
    }

    public AgentManager getAgentManager() {
        return agentManager;
    }

    public SummarizationInterceptor getSummarizationInterceptor() {
        return summarizationInterceptor;
    }

    public HITLInterceptor getHitlInterceptor() {
        return hitlInterceptor;
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

    public ToolGatewaySkill getMcpGatewaySkill() {
        return mcpGatewaySkill;
    }

    public RestApiSkill getRestApiSkill() {
        return restApiSkill;
    }

    public void extensionAdd(HarnessExtension extension) {
        locker.lock();
        try {
            props.addExtension(extension);

            // 如果主代理还没懒加载触发，这里无需提前创建
            if (this.mainAgent != null) {
                this.mainAgent = createMainAgent();
            }
        } finally {
            locker.unlock();
        }
    }

    /**
     * 切换默认主模型
     */
    public void switchMainModel(String name) {
        Objects.requireNonNull(name, "name");

        ChatConfig chatConfig = props.getModelOrNil(name);
        if (chatConfig == null) {
            throw new IllegalArgumentException("The model not found: " + name);
        }


        locker.lock();
        try {
            ChatModel newModel = chatConfig.toChatModel();

            // 联动更新：先换模型，再换代理
            this.mainModel = newModel;

            // 如果 mainAgent 之前已经被懒加载初始化过了，则立刻刷新它
            if (this.mainAgent != null) {
                this.mainAgent = createMainAgent();
            }
        } finally {
            locker.unlock();
        }
    }

    private HarnessEngine(HarnessProperties props, AgentSessionProvider sessionProvider, MemorySolution.Factory memorySolution, SummarizationInterceptor summarizationInterceptor, HITLInterceptor hitlInterceptor) {
        this.props = props;
        this.mainModel = props.getModelOrDef(null).toChatModel();

        //上下文摘要拦截器默认处理
        if (summarizationInterceptor == null) {
            SummarizationStrategy strategy = new CompositeSummarizationStrategy()
                    .addStrategy(new KeyInfoExtractionStrategy(this::getModelForSummary).retryConfig(props.getModelRetries()))      // 提取干货（去水）
                    .addStrategy(new HierarchicalSummarizationStrategy(this::getModelForSummary).retryConfig(props.getModelRetries())); // 滚动更新摘要

            summarizationInterceptor = new SummarizationInterceptor(
                    props.getSummaryWindowSize(),
                    props.getSummaryWindowToken(),
                    strategy);
        }

        //人工介入拉截器默认处理
        if (hitlInterceptor == null) {
            hitlInterceptor = new HITLInterceptor().onTool("bash", new HitlStrategy());
        }

        this.sessionProvider = sessionProvider;
        this.summarizationInterceptor = summarizationInterceptor;
        this.hitlInterceptor = hitlInterceptor;

        this.todoSkill = new TodoSkill(props.getHarnessSessions());
        this.codeSkill = new CodeSkill(this);
        this.taskSkill = new TaskSkill(this);
        this.generateTool = new GenerateTool(this);

        this.codeSearchTool = new CodeSearchTool().retryConfig(props.getMcpRetries());
        this.websearchTool = new WebsearchTool().retryConfig(props.getMcpRetries());
        this.webfetchTool = new WebfetchTool().retryConfig(props.getApiRetries());

        //lsp
        this.lspManager = new LspManager(props.getWorkspace());
        if (Assert.isNotEmpty(props.getLspServers())) {
            for (Map.Entry<String, LspServerParameters> entry : props.getLspServers().entrySet()) {
                lspManager.registerServer(entry.getKey(), entry.getValue());
            }
        }
        this.lspSkill = lspManager.hasServers() ? new LspSkill(lspManager, props.getWorkspace()) : null;
        if (this.lspSkill != null) {
            lspManager.setDiagnosticsCallback(lspSkill::updateDiagnostics);
        }

        if (props.isMemoryEnabled() && memorySolution != null) {
            this.memorySkill = new MemorySkill(memorySolution).sessionIsolation(false);
        } else {
            this.memorySkill = null;
        }

        if (Assert.isNotEmpty(props.getApiServers())) {
            restApiSkill = new RestApiSkill().retryConfig(props.getApiRetries());
            for (Map.Entry<String, ApiSource> entry : props.getApiServers().entrySet()) {
                restApiSkill.addApi(entry.getValue());
            }
        } else {
            restApiSkill = null;
        }

        try {
            if (Assert.isNotEmpty(props.getMcpServers())) {
                McpProviders mcpProviders = McpProviders.fromMcpServers(props.getMcpServers());

                if (mcpProviders.getProviders().size() > 0) {
                    mcpGatewaySkill = new ToolGatewaySkill().retryConfig(props.getMcpRetries());
                    for (Map.Entry<String, McpClientProvider> entry : mcpProviders.getProviders().entrySet()) {
                        mcpGatewaySkill.addTool(entry.getKey(), entry.getValue());
                    }
                } else {
                    mcpGatewaySkill = null;
                }
            } else {
                mcpGatewaySkill = null;
            }
        } catch (IOException e) {
            throw new RuntimeException("Mcp servers load failure", e);
        }

        cliSkills.bashAsyncEnabled(props.isBashAsyncEnabled());
        cliSkills.getTerminalSkill().setSandboxMode(props.isSandboxMode());
        if (Assert.isNotEmpty(props.getSkillPools())) {
            props.getSkillPools().forEach((alias, dir) -> {
                cliSkills.skillPool(alias, dir);
            });
        }

        agentManager = new AgentManager();
        if (Assert.isNotEmpty(props.getAgentPools())) {
            props.getAgentPools().forEach(dir -> {
                agentManager.agentPool(Paths.get(dir));
            });
        }

        //mainAgent = createMainAgent(); //改为懒加载
    }

    protected ReActAgent createMainAgent() {
        AgentDefinition agentDefinition = new AgentDefinition();

        // 系统提示词
        agentDefinition.setSystemPrompt(props.getSystemPrompt());
        // 名字
        agentDefinition.getMetadata().setName(AgentDefinition.AGENT_MAIN);
        // 主代理
        agentDefinition.getMetadata().setPrimary(true);
        // 工具权限
        agentDefinition.getMetadata().getTools().addAll(props.getTools()); //允许

        // 添加步数
        agentDefinition.getMetadata().setMaxSteps(props.getMaxSteps());
        // 添加自我反思
        agentDefinition.getMetadata().setAutoRethink(props.isAutoRethink());
        // 添加会话窗口大小
        agentDefinition.getMetadata().setSessionWindowSize(props.getSessionWindowSize());

        ReActAgent.Builder agentBuilder = AgentFactory.create(this, agentDefinition, null);

        return agentBuilder.build();
    }


    public AgentSession getSession(String instanceId) {
        return sessionProvider.getSession(instanceId);
    }

    public ReActAgent.Builder createSubagent(AgentDefinition definition) {
        return AgentFactory.create(this, definition, null);
    }

    public ReActAgent getMainAgent() {
        ReActAgent agent = this.mainAgent; // 引入局部变量，仅执行一次 volatile read
        if (agent == null) {
            locker.lock();
            try {
                if (this.mainAgent == null) {
                    this.mainAgent = createMainAgent();
                }
                agent = this.mainAgent;
            } finally {
                locker.unlock();
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


    public static Builder of(HarnessProperties props) {
        return new Builder(props);
    }

    public static class Builder {
        private HarnessProperties properties;
        private AgentSessionProvider sessionProvider;
        private SummarizationInterceptor summarizationInterceptor;
        private HITLInterceptor hitlInterceptor;
        private MemorySolution.Factory memorySolution;

        public Builder(HarnessProperties properties) {
            this.properties = properties;
        }

        public Builder sessionProvider(AgentSessionProvider sessionProvider) {
            this.sessionProvider = sessionProvider;
            return this;
        }

        /**
         * 摘要拦截器
         */
        public Builder summarizationInterceptor(SummarizationInterceptor summarizationInterceptor) {
            this.summarizationInterceptor = summarizationInterceptor;
            return this;
        }

        /**
         * 人工介入拦截器
         */
        public Builder hitlInterceptor(HITLInterceptor hitlInterceptor) {
            this.hitlInterceptor = hitlInterceptor;
            return this;
        }

        /**
         * 心智记忆存储方案
         */
        public Builder memorySolution(MemorySolution.Factory memorySolution) {
            this.memorySolution = memorySolution;
            return this;
        }

        /**
         * 添加扩展
         *
         * @deprecated 3.10.4
         */
        @Deprecated
        public Builder extensionAdd(HarnessExtension extension) {
            this.properties.addExtension(extension);
            return this;
        }

        public HarnessEngine build() {
            Objects.nonNull(properties);
            Objects.nonNull(sessionProvider);

            //验证模型配置
            if (Assert.isEmpty(properties.getModels())) {
                throw new IllegalStateException("Missing models config");
            }

            //缺省 userAgent 补尝
            if (Assert.isNotEmpty(properties.getUserAgent())) {
                for (ChatConfig m1 : properties.getModels()) {
                    if (Assert.isEmpty(m1.getUserAgent())) {
                        m1.setUserAgent(properties.getUserAgent());
                    }
                }
            }

            return new HarnessEngine(properties, sessionProvider, memorySolution, summarizationInterceptor, hitlInterceptor);
        }
    }
}