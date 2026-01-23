/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.agent.react;

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentProfile;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.task.ActionTask;
import org.noear.solon.ai.agent.react.task.PlanTask;
import org.noear.solon.ai.agent.react.task.ReasonTask;
import org.noear.solon.ai.agent.team.TeamProtocol;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ModelOptionsAmend;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.Skill;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.ai.chat.tool.ToolSchemaUtil;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.RankEntity;
import org.noear.solon.flow.*;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * ReAct (Reason + Act) 协同推理智能体
 * <p>通过 [Reasoning -> Acting -> Observation] 的闭环模式，利用工具解决复杂逻辑任务。</p>
 */
@Preview("3.8.1")
public class ReActAgent implements Agent {
    public final static String ID_REASON = "reason";
    public final static String ID_REASON_BEF = "reason_bef";
    public final static String ID_REASON_AFT = "reason_aft";
    public final static String ID_PLAN = "plan";
    public final static String ID_ACTION = "action";
    public final static String ID_ACTION_BEF = "action_bef";
    public final static String ID_ACTION_AFT = "action_aft";

    private static final Logger LOG = LoggerFactory.getLogger(ReActAgent.class);

    private final ReActAgentConfig config;
    private final Graph graph;
    private final FlowEngine flowEngine;

    public ReActAgent(ReActAgentConfig config) {
        Objects.requireNonNull(config, "Missing config!");
        this.config = config;
        this.flowEngine = FlowEngine.newInstance(true);
        // 初始化推理计算图
        this.graph = buildGraph();
    }

    /**
     * 构建 ReAct 执行图：Start -> [Plan -> Reason <-> Action] -> End
     */
    protected Graph buildGraph() {
        return Graph.create(config.getTraceKey(), spec -> {
            spec.addStart(Agent.ID_START).linkAdd(ReActAgent.ID_PLAN);
            spec.addActivity(new PlanTask(config)).linkAdd(ID_REASON_BEF);

            spec.addActivity(ID_REASON_BEF).title("Pre-Reasoning").linkAdd(ID_REASON);

            spec.addExclusive(new ReasonTask(config, this))
                    .linkAdd(ID_REASON_AFT, l -> l.title("route = ACTION").when(ctx ->
                            ID_ACTION.equals(ctx.<ReActTrace>getAs(config.getTraceKey()).getRoute())))
                    .linkAdd(Agent.ID_END);

            spec.addActivity(ID_REASON_AFT).title("Post-Reasoning").linkAdd(ID_ACTION_BEF);

            spec.addActivity(ID_ACTION_BEF).title("Pre-Action").linkAdd(ID_ACTION);

            // 执行节点：调用工具，产生观察结果（Observation），然后返回推理节点
            spec.addActivity(new ActionTask(config)).linkAdd(ID_ACTION_AFT);
            spec.addActivity(ID_ACTION_AFT).title("Post-Action").linkAdd(ID_REASON_BEF);

            spec.addEnd(Agent.ID_END);

            if (config.getGraphAdjuster() != null) {
                config.getGraphAdjuster().accept(spec);
            }
        });
    }

    public Graph getGraph() {
        return graph;
    }

    public ReActAgentConfig getConfig() {
        return config;
    }

    /**
     * 获取会话中的执行轨迹
     */
    public @Nullable ReActTrace getTrace(AgentSession session) {
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

    public ReActRequest prompt(Prompt prompt) {
        return new ReActRequest(this, prompt);
    }

    public ReActRequest prompt(String prompt) {
        return new ReActRequest(this, Prompt.of(prompt));
    }

    @Override
    public AssistantMessage call(Prompt prompt, AgentSession session) throws Throwable {
        return this.call(prompt, session, null);
    }

    /**
     * 智能体核心调用流程
     */
    protected AssistantMessage call(Prompt prompt, AgentSession session, ReActOptions options) throws Throwable {
        FlowContext context = session.getSnapshot();
        TeamProtocol protocol = context.getAs(Agent.KEY_PROTOCOL);
        TeamTrace parentTeamTrace = TeamTrace.getCurrent(context);

        // 初始化或恢复推理痕迹 (Trace)
        ReActTrace trace = context.getAs(config.getTraceKey());
        if (trace == null) {
            trace = new ReActTrace(prompt);
            context.put(config.getTraceKey(), trace);
        }

        if (options == null) {
            options = config.getDefaultOptions();
        }

        trace.prepare(config, options, session, protocol);


        if (protocol != null) {
            protocol.injectAgentTools(session.getSnapshot(), this, trace::addProtocolTool);
        }

        // 1. 加载历史上下文（短期记忆）
        if (trace.getMessagesSize() == 0 && options.getSessionWindowSize() > 0) {
            if (parentTeamTrace == null) {
                Collection<ChatMessage> history = session.getHistoryMessages(config.getName(), options.getSessionWindowSize());
                trace.appendMessages(history);
            }
        }

        if (Prompt.isEmpty(prompt)) {
            //可能是旧问题（之前中断的）
            prompt = trace.getPrompt();

            if (Prompt.isEmpty(prompt)) {
                LOG.warn("Prompt is empty!");
                return ChatMessage.ofAssistant("");
            }
        } else {
            //新的问题
            for (ChatMessage message : prompt.getMessages()) {
                if (parentTeamTrace == null) {
                    session.addHistoryMessage(config.getName(), message);
                }

                trace.appendMessage(message);
            }

            trace.setPlans(null);
            context.trace().recordNode(graph, null);
            trace.setPrompt(prompt);
        }

        //如果提示词没问题，开始激活技能
        trace.activeSkills();


        // 拦截器：任务开始事件
        for (RankEntity<ReActInterceptor> item : options.getInterceptors()) {
            item.target.onAgentStart(trace);
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("ReActAgent [{}] start thinking... Prompt: {}", config.getName(), prompt.getUserContent());
        }

        long startTime = System.currentTimeMillis();
        try {
            final FlowOptions flowOptions = new FlowOptions();
            options.getInterceptors().forEach(item -> flowOptions.interceptorAdd(item.target, item.index));

            trace.getMetrics().reset();

            // 核心执行：基于计算图进行循环推理
            context.with(KEY_CURRENT_UNIT_TRACE_KEY, config.getTraceKey(), () -> {
                flowEngine.eval(graph, -1, context, flowOptions);
            });
        } finally {
            // 记录性能指标
            long duration = System.currentTimeMillis() - startTime;
            trace.getMetrics().setTotalDuration(duration);

            if (LOG.isDebugEnabled()) {
                LOG.debug("ReActAgent [{}] finished. Duration: {}ms, Steps: {}, Tools: {}",
                        config.getName(), duration, trace.getStepCount(), trace.getToolCallCount());
            }

            // 父一级团队轨迹
            TeamTrace teamTrace = TeamTrace.getCurrent(context);
            if (teamTrace != null) {
                // 汇总 token 使用情况
                teamTrace.getMetrics().addMetrics(trace.getMetrics());
            }
        }

        String result = trace.getFinalAnswer();

        // 结果回填
        if (Assert.isNotEmpty(config.getOutputKey())) {
            context.put(config.getOutputKey(), result);
        }

        AssistantMessage assistantMessage = ChatMessage.ofAssistant(result);
        if (parentTeamTrace == null) {
            session.addHistoryMessage(config.getName(), assistantMessage);
        }

        session.updateSnapshot(context);

        // 拦截器：任务结束事件
        for (RankEntity<ReActInterceptor> item : options.getInterceptors()) {
            item.target.onAgentEnd(trace);
        }

        return assistantMessage;
    }

    /// //////////// Builder 模式 ////////////

    public static Builder of(ChatModel chatModel) {
        return new Builder(chatModel);
    }

    public static class Builder {
        private ReActAgentConfig config;

        public Builder(ChatModel chatModel) {
            this.config = new ReActAgentConfig(chatModel);
        }

        public Builder then(Consumer<Builder> consumer) {
            consumer.accept(this);
            return this;
        }

        public Builder name(String val) {
            config.setName(val);
            return this;
        }

        public Builder title(String val) {
            config.setTitle(val);
            return this;
        }

        public Builder description(String val) {
            config.setDescription(val);
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
         * 微调推理图结构
         */
        public Builder graphAdjuster(Consumer<GraphSpec> graphBuilder) {
            config.setGraphAdjuster(graphBuilder);
            return this;
        }

        /**
         * 定义 LLM 输出中的任务结束标识符
         */
        public Builder finishMarker(String val) {
            config.setFinishMarker(val);
            return this;
        }

        public Builder systemPrompt(ReActSystemPrompt val) {
            config.setSystemPrompt(val);
            return this;
        }

        public Builder systemPrompt(Consumer<ReActSystemPrompt.Builder> promptBuilder) {
            ReActSystemPrompt.Builder builder = ReActSystemPrompt.builder();
            promptBuilder.accept(builder);
            config.setSystemPrompt(builder.build());
            return this;
        }

        public Builder modelOptions(Consumer<ModelOptionsAmend<?, ReActInterceptor>> chatOptions) {
            chatOptions.accept(config.getDefaultOptions().getModelOptions());
            return this;
        }

        public Builder retryConfig(int maxRetries, long retryDelayMs) {
            config.getDefaultOptions().setRetryConfig(maxRetries, retryDelayMs);
            return this;
        }

        /**
         * 单次任务允许的最大推理步数（防止死循环）
         */
        public Builder maxSteps(int val) {
            config.getDefaultOptions().setMaxSteps(val);
            return this;
        }

        public Builder outputKey(String val) {
            config.setOutputKey(val);
            return this;
        }

        public Builder outputSchema(String val) {
            config.getDefaultOptions().setOutputSchema(val);
            return this;
        }

        public Builder outputSchema(Type type) {
            config.getDefaultOptions().setOutputSchema(ToolSchemaUtil.buildOutputSchema(type));
            return this;
        }

        public Builder sessionWindowSize(int val) {
            config.getDefaultOptions().setSessionWindowSize(val);
            return this;
        }


        public Builder defaultToolAdd(FunctionTool tool) {
            config.getDefaultOptions().getModelOptions().toolAdd(tool);
            return this;
        }

        public Builder defaultSkillAdd(Skill skill) {
            config.getDefaultOptions().getModelOptions().skillAdd(skill);
            return this;
        }

        public Builder defaultSkillAdd(Skill skill, int index) {
            config.getDefaultOptions().getModelOptions().skillAdd(index, skill);
            return this;
        }

        public Builder defaultToolAdd(Iterable<FunctionTool> tools) {
            config.getDefaultOptions().getModelOptions().toolAdd(tools);
            return this;
        }

        public Builder defaultToolAdd(ToolProvider toolProvider) {
            config.getDefaultOptions().getModelOptions().toolAdd(toolProvider);
            return this;
        }

        public Builder defaultToolAdd(Object toolObj) {
            return defaultToolAdd(new MethodToolProvider(toolObj));
        }


        public Builder defaultToolContextPut(String key, Object value) {
            config.getDefaultOptions().getModelOptions().toolContextPut(key, value);
            return this;
        }

        public Builder defaultToolContextPut(Map<String, Object> objectsMap) {
            config.getDefaultOptions().getModelOptions().toolContextPut(objectsMap);
            return this;
        }

        public Builder defaultInterceptorAdd(ReActInterceptor... vals) {
            for (ReActInterceptor val : vals) {
                config.getDefaultOptions().getModelOptions().interceptorAdd(0, val);
            }

            return this;
        }

        public Builder defaultInterceptorAdd(int index, ReActInterceptor val) {
            config.getDefaultOptions().getModelOptions().interceptorAdd(index, val);
            return this;
        }

        public Builder enablePlanning(boolean val) {
            config.getDefaultOptions().setEnablePlanning(val);
            return this;
        }

        public Builder planInstruction(Function<ReActTrace, String> provider) {
            config.getDefaultOptions().setPlanInstructionProvider(provider);
            return this;
        }

        public ReActAgent build() {
            if (config.getName() == null) {
                config.setName("react_agent");
            }

            if (config.getDescription() == null) {
                config.setDescription(config.getTitle() != null ? config.getTitle() : config.getName());
            }

            return new ReActAgent(config);
        }
    }
}