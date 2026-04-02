package org.noear.solon.ai.harness;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActAgentExtension;
import org.noear.solon.ai.agent.react.intercept.HITLInterceptor;
import org.noear.solon.ai.agent.react.intercept.SummarizationInterceptor;
import org.noear.solon.ai.agent.react.intercept.SummarizationStrategy;
import org.noear.solon.ai.agent.react.intercept.summarize.*;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.harness.agent.*;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.ai.skills.cli.CliSkillProvider;
import org.noear.solon.ai.skills.cli.TodoSkill;
import org.noear.solon.ai.skills.restapi.ApiSource;
import org.noear.solon.ai.skills.restapi.RestApiSkill;
import org.noear.solon.ai.skills.toolgateway.ToolGatewaySkill;
import org.noear.solon.ai.mcp.client.McpProviders;
import org.noear.solon.ai.harness.code.CodeSkill;
import org.noear.solon.ai.harness.hitl.HitlStrategy;
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
 * 智能体运行时
 *
 * @author noear
 */
public class AgentRuntime {
    private final static Logger LOG = LoggerFactory.getLogger(AgentRuntime.class);

    public final static String ATTR_CWD = "__cwd";

    public final static String NAME_AGENTS = "AGENTS.md";
    public final static String NAME_CONFIG = "config.yml";

    public final static String SESSION_DEFAULT = "default";

    public final static String SOLONCODE = ".soloncode/";
    public final static String SOLONCODE_BIN = SOLONCODE + "bin/";

    public final static String SOLONCODE_SESSIONS = SOLONCODE + "sessions/";
    public final static String SOLONCODE_SKILLS = SOLONCODE + "skills/";
    public final static String SOLONCODE_AGENTS = SOLONCODE + "agents/";
    public final static String SOLONCODE_MEMORY = SOLONCODE + "memory/";
    public final static String SOLONCODE_CLAUDE = SOLONCODE + "CLAUDE.md";

    public final static String SKILLHUB_SKILLS = ".skillhub/skills/";
    public final static String OPENCODE_SKILLS = ".opencode/skills/";
    public final static String CLAUDE_SKILLS = ".claude/skills/";

    private final ChatModel chatModel;
    private final AgentSessionProvider sessionProvider;
    private final AgentProperties properties;

    private final CodeSkill codeSkill = new CodeSkill();
    private final TodoSkill todoSkill = new TodoSkill(SOLONCODE_SESSIONS);
    private final TaskSkill taskSkill = new TaskSkill(this);
    private final GenerateTool generateTool = new GenerateTool(this);

    private final ReActAgent rootAgent;

    private final ToolGatewaySkill mcpGatewaySkill;
    private final RestApiSkill restApiSkill;

    private final CliSkillProvider cliSkills = new CliSkillProvider();

    private final SummarizationInterceptor summarizationInterceptor;
    private final HITLInterceptor hitlInterceptor = new HITLInterceptor().onTool("bash", new HitlStrategy());


    private AgentManager agentManager;

    public String getVersion() {
        return "v2026.4.5";
    }

    public String getName() {
        return rootAgent.name();
    }

    public AgentProperties getProps() {
        return properties;
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

    private AgentRuntime(ChatModel chatModel, AgentProperties properties, AgentSessionProvider sessionProvider, Collection<ReActAgentExtension> extensions) {
        this.chatModel = chatModel;
        this.properties = properties;
        this.sessionProvider = sessionProvider;

        if (Assert.isNotEmpty(properties.getRestApis())) {
            restApiSkill = new RestApiSkill();
            for (Map.Entry<String, ApiSource> entry : properties.getRestApis().entrySet()) {
                restApiSkill.addApi(entry.getValue());
            }
        } else {
            restApiSkill = null;
        }

        try {
            if (Assert.isNotEmpty(properties.getMcpServers())) {
                McpProviders mcpProviders = McpProviders.fromMcpServers(properties.getMcpServers());
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

        cliSkills.getTerminalSkill().setSandboxMode(properties.isSandboxMode());

        cliSkills.skillPool("@global", Paths.get(AgentProperties.getUserHome(), AgentRuntime.SOLONCODE_SKILLS));
        cliSkills.skillPool("@skillhub", Paths.get(AgentProperties.getUserHome(), AgentRuntime.SKILLHUB_SKILLS));
        cliSkills.skillPool("@local", Paths.get(properties.getWorkDir(), "skills"));

        cliSkills.skillPool("@soloncode_skills", Paths.get(properties.getWorkDir(), AgentRuntime.SOLONCODE_SKILLS));
        cliSkills.skillPool("@opencode_skills", Paths.get(properties.getWorkDir(), AgentRuntime.OPENCODE_SKILLS));
        cliSkills.skillPool("@claude_skills", Paths.get(properties.getWorkDir(), AgentRuntime.CLAUDE_SKILLS));

        if (Assert.isNotEmpty(properties.getSkillPools())) {
            properties.getSkillPools().forEach((alias, dir) -> {
                cliSkills.skillPool(alias, dir);
            });
        }

        agentManager = new AgentManager();
        agentManager.agentPool(Paths.get(AgentProperties.getUserHome(), AgentRuntime.SOLONCODE_AGENTS)); //global
        agentManager.agentPool(Paths.get(properties.getWorkDir(), AgentRuntime.SOLONCODE_AGENTS)); //local

        //上下文摘要
        SummarizationStrategy strategy = new CompositeSummarizationStrategy()
                .addStrategy(new KeyInfoExtractionStrategy(chatModel))      // 提取干货（去水）
                .addStrategy(new HierarchicalSummarizationStrategy(chatModel)); // 滚动更新摘要

        summarizationInterceptor = new SummarizationInterceptor(
                properties.getSummaryWindowSize(),
                properties.getSummaryWindowToken(),
                strategy);

        AgentDefinition agentDefinition = new AgentDefinition();

        // 系统提示词
        agentDefinition.setSystemPrompt(getAgentsMd());
        // 名字
        agentDefinition.getMetadata().setName("root");
        // 主代理
        agentDefinition.getMetadata().setPrimary(true);
        // 工具权限
        agentDefinition.getMetadata().addTools(properties.getTools());

        // 添加步数
        agentDefinition.getMetadata().setMaxSteps(properties.getMaxSteps());
        // 添加步数自动扩展
        agentDefinition.getMetadata().setMaxStepsAutoExtensible(properties.isMaxStepsAutoExtensible());
        // 添加会话窗口大小
        agentDefinition.getMetadata().setSessionWindowSize(properties.getSessionWindowSize());

        ReActAgent.Builder agentBuilder = AgentFactory.create(this, agentDefinition);

        if (Assert.isNotEmpty(extensions)) {
            for (ReActAgentExtension extension : extensions) {
                extension.configure(agentBuilder);
            }
        }

        rootAgent = agentBuilder.build();
    }


    public AgentSession getSession(String instanceId) {
        return sessionProvider.getSession(instanceId);
    }

    public ReActAgent getRootAgent() {
        return rootAgent;
    }

    public ReActAgent.Builder createSubagent(AgentDefinition definition) {
        return AgentFactory.create(this, definition);
    }

    private String getAgentsMd() {
        try {
            URL agentsUrl = properties.getAgentsUrl();

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
        private AgentProperties properties;
        private AgentSessionProvider sessionProvider;
        private List<ReActAgentExtension> extensions = new ArrayList<>();

        public Builder chatModel(ChatModel chatModel) {
            this.chatModel = chatModel;
            return this;
        }

        public Builder properties(AgentProperties properties) {
            this.properties = properties;
            return this;
        }

        public Builder sessionProvider(AgentSessionProvider sessionProvider) {
            this.sessionProvider = sessionProvider;
            return this;
        }

        public Builder extension(ReActAgentExtension extension) {
            this.extensions.add(extension);
            return this;
        }

        public AgentRuntime build() {
            Objects.nonNull(chatModel);
            Objects.nonNull(properties);
            Objects.nonNull(sessionProvider);

            return new AgentRuntime(chatModel, properties, sessionProvider, extensions);
        }
    }
}