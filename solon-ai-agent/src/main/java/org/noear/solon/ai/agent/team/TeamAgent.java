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
package org.noear.solon.ai.agent.team;

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentProfile;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ModelOptionsAmend;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.ChatPrompt;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.RankEntity;
import org.noear.solon.flow.*;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 团队协作智能体 (Team Agent)
 * * <p>核心定位：多智能体协作容器。底层依托 <b>Solon Flow</b> 状态机引擎实现复杂的协作逻辑。</p>
 * <ul>
 * <li><b>封装性：</b>将多个专家 Agent 封装为统一接口，屏蔽内部调度逻辑。</li>
 * <li><b>协议驱动：</b>协作模式（如 HIERARCHICAL, SWARM）由 {@link TeamProtocol} 定义并构建执行图。</li>
 * <li><b>踪迹隔离：</b>通过独有的 TraceKey 隔离不同团队在同一会话中的协作痕迹。</li>
 * </ul>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class TeamAgent implements Agent {
    public final static String ID_SYSTEM = "system";
    public final static String ID_SUPERVISOR = "supervisor";

    private static final Logger LOG = LoggerFactory.getLogger(TeamAgent.class);

    private final TeamAgentConfig config;
    private final Graph graph;
    private final FlowEngine flowEngine;

    public TeamAgent(TeamAgentConfig config) {
        Objects.requireNonNull(config, "Missing config!");
        this.config = config;
        this.flowEngine = FlowEngine.newInstance(true);
        // 初始化协作拓扑结构（计算图）
        this.graph = buildGraph();
    }

    /**
     * 依据协作协议构建计算图（Graph）
     */
    protected Graph buildGraph() {
        return Graph.create(config.getTraceKey(), spec -> {
            // 1. 由协议构建基础拓扑结构（如生成 Supervisor 或指定顺序）
            if (config.getChatModel() != null) {
                config.getProtocol().buildGraph(spec);
            }

            // 2. 执行用户自定义的图结构微调
            if (config.getGraphAdjuster() != null) {
                config.getGraphAdjuster().accept(spec);
            }
        });
    }

    public Graph getGraph() {
        return graph;
    }

    public TeamAgentConfig getConfig() {
        return config;
    }

    /**
     * 获取当前会话中的团队协作踪迹（Trace）
     */
    public @Nullable TeamTrace getTrace(AgentSession session) {
        return session.getSnapshot().getAs(config.getTraceKey());
    }

    @Override
    public String name() {
        return config.getName();
    }

    @Override
    public String title() {
        return config.getTitle();
    }

    @Override
    public String description() {
        return config.getDescription();
    }

    @Override
    public AgentProfile profile() {
        return config.getProfile();
    }

    /**
     * 创建团队请求（支持后续流式或异步处理）
     */
    public TeamRequest prompt(Prompt prompt) {
        return new TeamRequest(this, prompt);
    }

    public TeamRequest prompt(String prompt) {
        return prompt(Prompt.of(prompt));
    }

    /**
     * 同步执行团队协作任务
     */
    @Override
    public AssistantMessage call(Prompt prompt, AgentSession session) throws Throwable {
        return call(prompt, session, null);
    }

    /**
     * 执行团队协作（核心生命周期管理）
     */
    protected AssistantMessage call(Prompt prompt, AgentSession session, TeamOptions options) throws Throwable {
        FlowContext context = session.getSnapshot();
        // 初始化或获取本次协作的轨迹快照
        TeamTrace trace = context.computeIfAbsent(config.getTraceKey(), k -> new TeamTrace(prompt));

        if (options == null) {
            options = config.getDefaultOptions();
        }

        // 1. 运行时环境准备
        trace.prepare(config, options, session, config.getName());

        if (ChatPrompt.isEmpty(prompt) == false) {
            context.trace().recordNode(graph, null);
            trace.setPrompt(prompt);
            trace.setRoute(null);
            trace.resetProtocolContext();
            trace.resetTurnCount();
        } else {
            if (ChatPrompt.isEmpty(trace.getPrompt())) {
                LOG.warn("Prompt is empty!");
                return ChatMessage.ofAssistant("");
            }
        }

        //如果提示词没问题，开始部署技能
        trace.activeSkills();

        // 触发团队启动拦截
        options.getInterceptors().forEach(item -> item.target.onTeamStart(trace));

        try {
            // 2. 消息归档：用户输入存入 Session
            if (prompt != null) {
                prompt.getMessages().forEach(m -> session.addHistoryMessage(config.getName(), m));
            }

            final FlowOptions flowOptions = new FlowOptions();
            if (!options.getInterceptors().isEmpty()) {
                for (RankEntity<TeamInterceptor> item : options.getInterceptors()) {
                    flowOptions.interceptorAdd(item.target, item.index);
                }
            }

            // 3. 驱动 Flow 引擎：在协议上下文中求值执行图
            if (LOG.isDebugEnabled()) {
                LOG.debug("TeamAgent [{}] starting collaboration flow...", name());
            }

            context.with(Agent.KEY_CURRENT_TEAM_TRACE_KEY, config.getTraceKey(), () -> {
                context.with(Agent.KEY_PROTOCOL, config.getProtocol(), () -> {
                    flowEngine.eval(graph, -1, context, flowOptions);
                });
            });

            // 4. 结果收敛：从轨迹中提取最终答案
            String result = trace.getFinalAnswer();
            if (result == null) {
                // 兜底策略：若无显式标记的 Final Answer，取最后一个执行专家的回复
                result = trace.getLastAgentContent();
            }

            trace.setFinalAnswer(result);

            // 回填业务上下文
            if (Assert.isNotEmpty(config.getOutputKey())) {
                context.put(config.getOutputKey(), result);
            }

            AssistantMessage assistantMessage = ChatMessage.ofAssistant(result);
            session.addHistoryMessage(config.getName(), assistantMessage);
            session.updateSnapshot(context);

            // 触发完成拦截
            options.getInterceptors().forEach(item -> item.target.onTeamEnd(trace));

            if (LOG.isDebugEnabled()) {
                LOG.debug("TeamAgent [{}] collaboration completed.", name());
            }

            return assistantMessage;

        } finally {
            // 5. 资源清理与协议后置处理
            config.getProtocol().onTeamFinished(context, trace);

            if (context.containsKey(Agent.KEY_CURRENT_TEAM_TRACE_KEY)) {
                //说明有嵌套，需要清空协议上下文（可能会重新再进来）
                if (context.isStopped() == false) {
                    trace.resetProtocolContext();
                }
            }
        }
    }

    // --- Builder 实现 ---

    public static Builder of(@Nullable ChatModel chatModel) {
        return new Builder(chatModel);
    }

    public static class Builder {
        private final TeamAgentConfig config;

        public Builder(@Nullable ChatModel chatModel) {
            this.config = new TeamAgentConfig(chatModel);
        }

        public Builder then(Consumer<Builder> consumer) {
            consumer.accept(this);
            return this;
        }

        public Builder name(String name) {
            config.setName(name);
            return this;
        }

        public Builder title(String title) {
            config.setTitle(title);
            return this;
        }

        public Builder description(String description) {
            config.setDescription(description);
            return this;
        }

        public Builder profile(AgentProfile profile) {
            config.setProfile(profile);
            return this;
        }

        public Builder profile(Consumer<AgentProfile> profileConsumer) {
            profileConsumer.accept(config.getProfile());
            return this;
        }

        /**
         * 添加团队成员
         */
        public Builder agentAdd(Agent... agents) {
            for (Agent agent : agents) {
                config.addAgent(agent);
            }
            return this;
        }

        public Builder systemPrompt(TeamSystemPrompt promptProvider) {
            config.setSystemPrompt(promptProvider);
            return this;
        }

        public Builder systemPrompt(Consumer<TeamSystemPrompt.Builder> promptBuilder) {
            TeamSystemPrompt.Builder builder = TeamSystemPrompt.builder();
            promptBuilder.accept(builder);
            config.setSystemPrompt(builder.build());
            return this;
        }

        /**
         * 设置任务终止的语义标识符（如 "FINISH"）
         */
        public Builder finishMarker(String finishMarker) {
            config.setFinishMarker(finishMarker);
            return this;
        }

        public Builder outputKey(String outputKey) {
            config.setOutputKey(outputKey);
            return this;
        }

        public Builder retryConfig(int maxRetries, long retryDelayMs) {
            config.getDefaultOptions().setRetryConfig(maxRetries, retryDelayMs);
            return this;
        }

        public Builder maxTurns(int maxTurns) {
            config.getDefaultOptions().setMaxTurns(maxTurns);
            return this;
        }

        public Builder recordWindowSize(int recordWindowSize) {
            config.getDefaultOptions().setRecordWindowSize(recordWindowSize);
            return this;
        }

        /**
         * 指定团队协作协议（Swarm, Sequential, Hierarchical 等）
         */
        public Builder protocol(TeamProtocolFactory protocolFactory) {
            config.setProtocol(protocolFactory);
            return this;
        }

        /**
         * 编排或调整计算图逻辑
         */
        public Builder graphAdjuster(Consumer<GraphSpec> graphBuilder) {
            config.setGraphAdjuster(graphBuilder);
            return this;
        }

        /**
         * 配置主管模型的对话参数
         */
        public Builder modelOptions(Consumer<ModelOptionsAmend<?,TeamInterceptor>> chatOptions) {
            chatOptions.accept(config.getDefaultOptions().getModelOptions());
            return this;
        }

        public Builder defaultToolAdd(FunctionTool tool) {
            config.getDefaultOptions().getModelOptions().toolAdd(tool);
            return this;
        }

        public Builder defaultToolAdd(Collection<FunctionTool> tools) {
            config.getDefaultOptions().getModelOptions().toolAdd(tools);
            return this;
        }

        public Builder defaultToolAdd(ToolProvider toolProvider) {
            config.getDefaultOptions().getModelOptions().toolAdd(toolProvider);
            return this;
        }

        public Builder defaultToolContextPut(String key, Object value) {
            config.getDefaultOptions().getModelOptions().toolContextPut(key, value);
            return this;
        }

        public Builder defaultToolContextPut(Map<String, Object> objectsMap) {
            config.getDefaultOptions().getModelOptions().toolContextPut(objectsMap);
            return this;
        }

        public Builder defaultInterceptorAdd(TeamInterceptor... interceptors) {
            for (TeamInterceptor interceptor : interceptors) {
                config.getDefaultOptions().addInterceptor(interceptor, 0);
            }
            return this;
        }

        public Builder defaultInterceptorAdd(TeamInterceptor interceptor, int index) {
            config.getDefaultOptions().addInterceptor(interceptor, index);
            return this;
        }

        public TeamAgent build() {
            if (config.getName() == null) {
                config.setName("team_agent");
            }

            if (config.getDescription() == null) {
                config.setDescription(config.getTitle() != null ? config.getTitle() : config.getName());
            }

            if (config.getAgentMap().isEmpty() && config.getGraphAdjuster() == null) {
                throw new IllegalStateException("At least one agent or a graphAdjuster is required.");
            }

            return new TeamAgent(config);
        }
    }
}