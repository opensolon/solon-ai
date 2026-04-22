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
import org.noear.solon.ai.harness.hitl.HitlStrategy;
import org.noear.solon.ai.skills.lsp.LspManager;
import org.noear.solon.ai.skills.lsp.LspServerParameters;
import org.noear.solon.ai.skills.lsp.LspSkill;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.ai.mcp.client.McpProviders;
import org.noear.solon.ai.skills.cli.CliSkillProvider;
import org.noear.solon.ai.skills.cli.TodoSkill;
import org.noear.solon.ai.skills.restapi.ApiSource;
import org.noear.solon.ai.skills.restapi.RestApiSkill;
import org.noear.solon.ai.skills.toolgateway.ToolGatewaySkill;
import org.noear.solon.ai.skills.web.CodeSearchTool;
import org.noear.solon.ai.skills.web.WebfetchTool;
import org.noear.solon.ai.skills.web.WebsearchTool;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.*;

/**
 * 马具引擎
 *
 * @author noear
 */
@Preview("3.10")
public class HarnessEngine {
    public final static String ATTR_CWD = "__cwd";

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

    private final CliSkillProvider cliSkills = new CliSkillProvider();

    private final SummarizationInterceptor summarizationInterceptor;
    private final HITLInterceptor hitlInterceptor;

    private final AgentManager agentManager;

    private ChatModel mainModel; //允许运行时切换
    private ReActAgent mainAgent; //允许运行时切换

    public String getName() {
        return mainAgent.name();
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
        if (Assert.isEmpty(name) || mainModel.getModel().equals(name)) {
            return mainModel;
        }

        return props.getModelOrDef(name).toChatModel();
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

    public TodoSkill getTodoSkill() {
        return todoSkill;
    }

    public TaskSkill getTaskSkill() {
        return taskSkill;
    }

    public CodeSkill getCodeSkill() {
        return codeSkill;
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

    /**
     * 切换默认主模型
     */
    public void switchMainModel(String name) {
        Objects.requireNonNull(name, "name");

        ChatConfig chatConfig = props.getModelOrNil(name);
        if (chatConfig == null) {
            throw new IllegalArgumentException("The model not found: " + name);
        }

        // chatModel 切换后，重新生成主代理
        this.mainModel = chatConfig.toChatModel();
        this.mainAgent = createMainAgent();
    }

    private HarnessEngine(HarnessProperties props, AgentSessionProvider sessionProvider, SummarizationInterceptor summarizationInterceptor, HITLInterceptor hitlInterceptor) {
        this.props = props;
        this.mainModel = props.getModelOrDef(null).toChatModel();

        //上下文摘要拦截器默认处理
        if (summarizationInterceptor == null) {
            ChatModel summaryModel = getModelOrMain(props.getSummaryModel());

            SummarizationStrategy strategy = new CompositeSummarizationStrategy()
                    .addStrategy(new KeyInfoExtractionStrategy(summaryModel).retryConfig(props.getModelRetries()))      // 提取干货（去水）
                    .addStrategy(new HierarchicalSummarizationStrategy(summaryModel).retryConfig(props.getModelRetries())); // 滚动更新摘要

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
                mcpGatewaySkill = new ToolGatewaySkill().retryConfig(props.getMcpRetries());
                for (Map.Entry<String, McpClientProvider> entry : mcpProviders.getProviders().entrySet()) {
                    mcpGatewaySkill.addTool(entry.getKey(), entry.getValue());
                }
            } else {
                mcpGatewaySkill = null;
            }
        } catch (IOException e) {
            throw new RuntimeException("Mcp servers load failure", e);
        }

        cliSkills.getTerminalSkill().setSandboxMode(props.isSandboxMode());
        if (Assert.isNotEmpty(props.getSkillPools())) {
            props.getSkillPools().forEach((alias, dir) -> {
                cliSkills.skillPool(alias, dir);
            });
        }

        agentManager = new AgentManager();
        if(Assert.isNotEmpty(props.getAgentPools())) {
            props.getAgentPools().forEach(dir -> {
                agentManager.agentPool(Paths.get(dir));
            });
        }

        mainAgent = createMainAgent();
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
        // 添加步数自动扩展
        agentDefinition.getMetadata().setMaxStepsAutoExtensible(props.isMaxStepsAutoExtensible());
        // 添加会话窗口大小
        agentDefinition.getMetadata().setSessionWindowSize(props.getSessionWindowSize());

        ReActAgent.Builder agentBuilder = AgentFactory.create(this, agentDefinition);

        return agentBuilder.build();
    }


    public AgentSession getSession(String instanceId) {
        return sessionProvider.getSession(instanceId);
    }

    public ReActAgent.Builder createSubagent(AgentDefinition definition) {
        return AgentFactory.create(this, definition);
    }

    public ReActAgent getMainAgent() {
        return mainAgent;
    }

    public ReActRequest prompt(Prompt prompt) {
        return mainAgent.prompt(prompt);
    }


    public ReActRequest prompt(String prompt) {
       return mainAgent.prompt(prompt);
    }


    public ReActRequest prompt() {
        return mainAgent.prompt();
    }


    public static Builder of(HarnessProperties props) {
        return new Builder(props);
    }

    public static class Builder {
        private HarnessProperties properties;
        private AgentSessionProvider sessionProvider;
        private SummarizationInterceptor summarizationInterceptor;
        private HITLInterceptor hitlInterceptor;

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

            return new HarnessEngine(properties, sessionProvider, summarizationInterceptor, hitlInterceptor);
        }
    }
}