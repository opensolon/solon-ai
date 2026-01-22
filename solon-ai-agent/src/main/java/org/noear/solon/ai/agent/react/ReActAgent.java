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
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.ChatPrompt;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.RankEntity;
import org.noear.solon.flow.*;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Objects;

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
     * 构建 ReAct 执行图：Start -> [Reason <-> Action] -> End
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
            Collection<ChatMessage> history = session.getHistoryMessages(config.getName(), options.getSessionWindowSize());
            trace.appendMessages(history);
        }

        if (ChatPrompt.isEmpty(prompt)) {
            //可能是旧问题（之前中断的）
            prompt = trace.getPrompt();

            if (ChatPrompt.isEmpty(prompt)) {
                LOG.warn("Prompt is empty!");
                return ChatMessage.ofAssistant("");
            }
        } else {
            //新的问题
            for (ChatMessage message : prompt.getMessages()) {
                session.addHistoryMessage(config.getName(), message);
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
            LOG.debug("ReActAgent [{}] start thinking... Prompt: {}", config.getName(), prompt.getUserMessageContent());
        }

        long startTime = System.currentTimeMillis();
        try {
            final FlowOptions flowOptions = new FlowOptions();
            options.getInterceptors().forEach(item -> flowOptions.interceptorAdd(item.target, item.index));

            trace.getMetrics().setTokenUsage(0L);

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
                teamTrace.getMetrics().addTokenUsage(trace.getMetrics().getTokenUsage());
            }
        }

        String result = trace.getFinalAnswer();

        // 结果回填
        if (Assert.isNotEmpty(config.getOutputKey())) {
            context.put(config.getOutputKey(), result);
        }

        AssistantMessage assistantMessage = ChatMessage.ofAssistant(result);
        session.addHistoryMessage(config.getName(), assistantMessage);
        session.updateSnapshot(context);

        // 拦截器：任务结束事件
        for (RankEntity<ReActInterceptor> item : options.getInterceptors()) {
            item.target.onAgentEnd(trace);
        }

        return assistantMessage;
    }

    /// //////////// Builder 模式 ////////////

    public static ReActAgentBuilder of(ChatModel chatModel) {
        return new ReActAgentBuilder(chatModel);
    }
}