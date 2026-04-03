package org.noear.solon.ai.harness;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActAgentExtension;
import org.noear.solon.ai.agent.react.intercept.HITLInterceptor;
import org.noear.solon.ai.agent.react.intercept.SummarizationInterceptor;
import org.noear.solon.ai.agent.react.intercept.SummarizationStrategy;
import org.noear.solon.ai.agent.react.intercept.summarize.CompositeSummarizationStrategy;
import org.noear.solon.ai.agent.react.intercept.summarize.HierarchicalSummarizationStrategy;
import org.noear.solon.ai.agent.react.intercept.summarize.KeyInfoExtractionStrategy;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.harness.agent.*;
import org.noear.solon.ai.harness.code.CodeSkill;
import org.noear.solon.ai.harness.hitl.HitlStrategy;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.ai.mcp.client.McpProviders;
import org.noear.solon.ai.skills.cli.CliSkillProvider;
import org.noear.solon.ai.skills.cli.TodoSkill;
import org.noear.solon.ai.skills.restapi.ApiSource;
import org.noear.solon.ai.skills.restapi.RestApiSkill;
import org.noear.solon.ai.skills.toolgateway.ToolGatewaySkill;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.IoUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Paths;
import java.util.*;

/**
 * 马具引擎
 *
 * @author noear
 */
public class HarnessEngine {
    private final static Logger LOG = LoggerFactory.getLogger(HarnessEngine.class);

    public final static String ATTR_CWD = "__cwd";

    public final static String SESSION_DEFAULT = "default";

    public final static String NAME_CONFIG_YML = "config.yml";
    public final static String NAME_AGENTS_MD = "AGENTS.md";
    public final static String NAME_CLAUDE_MD = "CLAUDE.md";

    private final AgentSessionProvider sessionProvider;
    private final HarnessProperties props;
    private final Collection<ReActAgentExtension> extensions;

    private final CodeSkill codeSkill;
    private final TodoSkill todoSkill;
    private final TaskSkill taskSkill;
    private final GenerateTool generateTool;


    private final ToolGatewaySkill mcpGatewaySkill;
    private final RestApiSkill restApiSkill;

    private final CliSkillProvider cliSkills = new CliSkillProvider();

    private final SummarizationInterceptor summarizationInterceptor;
    private final HITLInterceptor hitlInterceptor;

    private final AgentManager agentManager;

    private ChatModel chatModel; //允许运行时切换
    private ReActAgent mainAgent; //允许运行时切换

    public String getVersion() {
        return "v2026.4.5";
    }

    public String getName() {
        return mainAgent.name();
    }

    public HarnessProperties getProps() {
        return props;
    }

    public ChatModel getChatModel() {
        return chatModel;
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

    public ToolGatewaySkill getMcpGatewaySkill() {
        return mcpGatewaySkill;
    }

    public RestApiSkill getRestApiSkill() {
        return restApiSkill;
    }


    public void setChatModel(ChatModel chatModel) {
        Objects.nonNull(chatModel);

        // chatModel 切换后，重新生成主代理
        this.chatModel = chatModel;
        this.mainAgent = createMainAgent();
    }


    private HarnessEngine(ChatModel chatModel, HarnessProperties props, AgentSessionProvider sessionProvider, SummarizationInterceptor summarizationInterceptor, HITLInterceptor hitlInterceptor, Collection<ReActAgentExtension> extensions) {
        this.chatModel = chatModel;
        this.props = props;
        this.sessionProvider = sessionProvider;
        this.summarizationInterceptor = summarizationInterceptor;
        this.hitlInterceptor = hitlInterceptor;
        this.extensions = extensions;

        this.todoSkill = new TodoSkill(props.getHarnessSessions());
        this.codeSkill = new CodeSkill(this);
        this.taskSkill = new TaskSkill(this);
        this.generateTool = new GenerateTool(this);

        if (Assert.isNotEmpty(props.getRestApis())) {
            restApiSkill = new RestApiSkill();
            for (Map.Entry<String, ApiSource> entry : props.getRestApis().entrySet()) {
                restApiSkill.addApi(entry.getValue());
            }
        } else {
            restApiSkill = null;
        }

        try {
            if (Assert.isNotEmpty(props.getMcpServers())) {
                McpProviders mcpProviders = McpProviders.fromMcpServers(props.getMcpServers());
                mcpGatewaySkill = new ToolGatewaySkill();
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

        cliSkills.skillPool("@global", Paths.get(HarnessProperties.getUserHome(), props.getHarnessSkills()));
        cliSkills.skillPool("@local", Paths.get(props.getWorkspace(), props.getHarnessSkills()));

        cliSkills.skillPool("@skills", Paths.get(props.getWorkspace(), "skills"));
        cliSkills.skillPool("@skillhub", Paths.get(HarnessProperties.getUserHome(), ".skillhub/skills/"));


        if (Assert.isNotEmpty(props.getSkillPools())) {
            props.getSkillPools().forEach((alias, dir) -> {
                cliSkills.skillPool(alias, dir);
            });
        }

        agentManager = new AgentManager();
        agentManager.agentPool(Paths.get(HarnessProperties.getUserHome(), props.getHarnessAgents())); //global
        agentManager.agentPool(Paths.get(props.getWorkspace(), props.getHarnessAgents())); //local

        mainAgent = createMainAgent();
    }

    protected ReActAgent createMainAgent() {
        AgentDefinition agentDefinition = new AgentDefinition();

        // 系统提示词
        agentDefinition.setSystemPrompt(getAgentsMd());
        // 名字
        agentDefinition.getMetadata().setName("main");
        // 主代理
        agentDefinition.getMetadata().setPrimary(true);
        // 工具权限
        agentDefinition.getMetadata().addTools(props.getTools());

        // 添加步数
        agentDefinition.getMetadata().setMaxSteps(props.getMaxSteps());
        // 添加步数自动扩展
        agentDefinition.getMetadata().setMaxStepsAutoExtensible(props.isMaxStepsAutoExtensible());
        // 添加会话窗口大小
        agentDefinition.getMetadata().setSessionWindowSize(props.getSessionWindowSize());

        ReActAgent.Builder agentBuilder = AgentFactory.create(this, agentDefinition);

        if (Assert.isNotEmpty(extensions)) {
            for (ReActAgentExtension extension : extensions) {
                extension.configure(agentBuilder);
            }
        }

        return agentBuilder.build();
    }


    public AgentSession getSession(String instanceId) {
        return sessionProvider.getSession(instanceId);
    }

    public ReActAgent getMainAgent() {
        return mainAgent;
    }

    public ReActAgent.Builder createSubagent(AgentDefinition definition) {
        return AgentFactory.create(this, definition);
    }

    private String getAgentsMd() {
        try {
            URL agentsUrl = props.getAgentsUrl();

            if (agentsUrl != null) {
                try (InputStream is = agentsUrl.openStream()) {
                    String content = IoUtil.transferToString(is, "utf-8").trim();

                    if (content.length() > 10000) { // 例如限制在 1万字符以内
                        LOG.warn("AGENTS.md is too large, truncating...");
                        return content.substring(0, 10000);
                    }
                    return content;
                }
            }
        } catch (Throwable e) {
            LOG.warn("AGENTS.md load failure: {}", e.getMessage(), e);
        }

        return null;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private ChatModel chatModel;
        private HarnessProperties properties;
        private AgentSessionProvider sessionProvider;
        private SummarizationInterceptor summarizationInterceptor;
        private HITLInterceptor hitlInterceptor;
        private List<ReActAgentExtension> extensions = new ArrayList<>();

        public Builder chatModel(ChatModel chatModel) {
            this.chatModel = chatModel;
            return this;
        }

        public Builder properties(HarnessProperties properties) {
            this.properties = properties;
            return this;
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
         */
        public Builder extensionAdd(ReActAgentExtension extension) {
            this.extensions.add(extension);
            return this;
        }

        public HarnessEngine build() {
            Objects.nonNull(chatModel);
            Objects.nonNull(properties);
            Objects.nonNull(sessionProvider);

            //上下文摘要
            SummarizationStrategy strategy = new CompositeSummarizationStrategy()
                    .addStrategy(new KeyInfoExtractionStrategy(chatModel))      // 提取干货（去水）
                    .addStrategy(new HierarchicalSummarizationStrategy(chatModel)); // 滚动更新摘要

            if (summarizationInterceptor == null) {
                summarizationInterceptor = new SummarizationInterceptor(
                        properties.getSummaryWindowSize(),
                        properties.getSummaryWindowToken(),
                        strategy);
            }

            if (hitlInterceptor == null) {
                hitlInterceptor = new HITLInterceptor().onTool("bash", new HitlStrategy());
            }

            return new HarnessEngine(chatModel, properties, sessionProvider, summarizationInterceptor, hitlInterceptor, extensions);
        }
    }
}