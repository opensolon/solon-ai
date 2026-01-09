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
package org.noear.solon.ai.agent.react;

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentRequest;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.task.ActionTask;
import org.noear.solon.ai.agent.react.task.ReasonTask;
import org.noear.solon.ai.agent.team.TeamProtocol;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.RankEntity;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.FlowEngine;
import org.noear.solon.flow.Graph;
import org.noear.solon.flow.GraphSpec;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * ReAct (Reason + Act) 协同推理智能体
 * <p>通过“思考-行动-观察”的循环模式，结合外部工具调用来解决复杂问题。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public class ReActAgent implements Agent {
    private static final Logger LOG = LoggerFactory.getLogger(ReActAgent.class);

    private final String name;
    private final String title;
    private final String description;
    private final ReActConfig config;
    private final Graph graph;
    private final FlowEngine flowEngine;
    private final String traceKey;

    public ReActAgent(ReActConfig config) {
        Objects.requireNonNull(config, "Missing config!");

        this.config = config;

        this.name = config.getName();
        this.title = config.getTitle();
        this.description = config.getDescription();

        this.traceKey = "__" + name; // 每个 Agent 实例拥有独立的追踪键
        this.flowEngine = FlowEngine.newInstance(true);

        // 1. 挂载流拦截器（用于全局监控或审计）
        for (RankEntity<ReActInterceptor> item : config.getInterceptorList()) {
            flowEngine.addInterceptor(item.target, item.index);
        }

        // 2. 构建 ReAct 执行计算图：Start -> [Reason <-> Action] -> End
        this.graph = buildGraph();
    }

    /**
     * 构建计算图（可重写）
     */
    protected Graph buildGraph() {
        return Graph.create(this.name(), spec -> {
            spec.addStart(Agent.ID_START).linkAdd(Agent.ID_REASON);

            // 推理任务节点（Reasoning）：决定下一步是调用工具还是直接回答
            spec.addExclusive(new ReasonTask(config,this))
                    .linkAdd(Agent.ID_ACTION, l -> l.title("route = " + Agent.ID_ACTION).when(ctx ->
                            Agent.ID_ACTION.equals(ctx.<ReActTrace>getAs(traceKey).getRoute())))
                    .linkAdd(Agent.ID_END);

            // 执行任务节点（Acting）：执行具体的工具调用并返回观察结果
            spec.addActivity(new ActionTask(config))
                    .linkAdd(Agent.ID_REASON); // 动作完成后再次回到推理节点

            spec.addEnd(Agent.ID_END);

            if (config.getGraphAdjuster() != null) {
                config.getGraphAdjuster().accept(spec);
            }
        });
    }

    /**
     * 获取当前智能体的逻辑执行图
     */
    public Graph getGraph() {
        return graph;
    }

    /**
     * 获取配置（方便重写使用）
     */
    protected ReActConfig getConfig() {
        return config;
    }

    /**
     * 从上下文中获取当前 Agent 的执行状态追踪实例
     */
    public @Nullable ReActTrace getTrace(AgentSession session) {
        return session.getSnapshot().getAs("__" + name);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public String description() {
        return description;
    }

    public AgentRequest prompt(Prompt prompt) {
        return new ReActRequestImpl(this, prompt);
    }

    public AgentRequest prompt(String prompt) {
        return new ReActRequestImpl(this, Prompt.of(prompt));
    }

    /**
     * 智能体调用入口
     *
     * @param session 会话
     * @param prompt  用户输入的提示词
     */
    public AssistantMessage call(Prompt prompt, AgentSession session) throws Throwable {
        FlowContext context = session.getSnapshot();
        // 维护执行痕迹：若上下文已存在则复用，支持多轮对话或中断恢复
        TeamProtocol protocol = context.getAs(Agent.KEY_PROTOCOL);
        ReActTrace trace = context.getAs(traceKey);
        if (trace == null) {
            trace = new ReActTrace(prompt);
            context.put(traceKey, trace);
        }

        trace.prepare(config, session, name, protocol);

        if(protocol != null){
            protocol.injectAgentTools(this, trace);
        }

        // 只有当 trace 消息为空（首轮执行）且 prompt 不为空时才加载历史
        if (trace.getMessages().isEmpty() && !Prompt.isEmpty(prompt)) {
            // 1. 存入当前 prompt 到 session 历史（持久化记录）
            for (ChatMessage message : prompt.getMessages()) {
                session.addHistoryMessage(name, message);
            }

            // 2. 根据 historyWindowSize 从 session 加载最近的历史到当前推理 trace
            if (config.getHistoryWindowSize() > 0) {
                Collection<ChatMessage> history = session.getHistoryMessages(name, config.getHistoryWindowSize());
                for (ChatMessage message : history) {
                    trace.appendMessage(message);
                }
            } else {
                // 如果窗口为 0，至少要把当前的 prompt 加入 trace 供 ReasonTask 使用
                for (ChatMessage message : prompt.getMessages()) {
                    trace.appendMessage(message);
                }
            }
        }


        if (Prompt.isEmpty(prompt)) {
            prompt = trace.getPrompt();
        } else {
            // 记录流节点链路，方便追踪调试
            context.trace().recordNode(graph, null);
            trace.setPrompt(prompt);
        }

        Objects.requireNonNull(prompt, "Missing prompt!");

        //开始事件
        for (RankEntity<ReActInterceptor> item : config.getInterceptorList()) {
            item.target.onAgentStart(trace);
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("ReActAgent [{}] starting: {}", this.name, prompt.getUserContent());
        }

        long startTime = System.currentTimeMillis();

        try {
            // [核心机制] 采用变量域思想传递 KEY_CURRENT_TRACE_KEY
            // 确保任务组件（Task）能根据该 Key 在上下文中定位到正确的状态机（Trace）
            context.with(Agent.KEY_CURRENT_TRACE_KEY, traceKey, () -> {
                flowEngine.eval(graph, context);
            });
        } finally {
            // 记录性能统计指标
            long duration = System.currentTimeMillis() - startTime;
            trace.getMetrics().setTotalDuration(duration);
            trace.getMetrics().setStepCount(trace.getStepCount());
            trace.getMetrics().setToolCallCount(trace.getToolCallCount());

            if (LOG.isDebugEnabled()) {
                LOG.debug("ReActAgent [{}] completed in {}ms, {} steps, {} tool calls",
                        this.name, duration, trace.getStepCount(), trace.getMetrics().getToolCallCount());
            }
        }

        String result = trace.getFinalAnswer();
        if (LOG.isDebugEnabled()) {
            LOG.debug("ReActAgent [{}] final Answer: {}", this.name, result);
        }


        if (Assert.isNotEmpty(config.getOutputKey())) {
            context.put(config.getOutputKey(), result);
        }

        AssistantMessage assistantMessage = ChatMessage.ofAssistant(result);
        session.addHistoryMessage(name, assistantMessage);
        session.updateSnapshot(context);

        //结束事件
        for (RankEntity<ReActInterceptor> item : config.getInterceptorList()) {
            item.target.onAgentEnd(trace);
        }

        return assistantMessage;
    }

    /// //////////// Builder 静态构造模式 ////////////

    public static Builder of(ChatModel chatModel) {
        return new Builder(chatModel);
    }

    /**
     * ReAct 智能体构建器
     */
    public static class Builder {
        private ReActConfig config;

        public Builder(ChatModel chatModel) {
            this.config = new ReActConfig(chatModel);
        }

        /**
         * 然后（构建自己）
         */
        public Builder then(Consumer<Builder> consumer) {
            consumer.accept(this);
            return this;
        }

        /**
         * 智能体名称
         */
        public Builder name(String val) {
            config.setName(val);
            return this;
        }

        /**
         * 智能体名称
         */
        public Builder title(String val) {
            config.setTitle(val);
            return this;
        }

        /**
         * 智能体功能描述（在 TeamAgent 协作模式下尤为重要）
         */
        public Builder description(String val) {
            config.setDescription(val);
            return this;
        }

        /**
         * 添加功能工具
         */
        public Builder addTool(FunctionTool tool) {
            config.addTool(tool);
            return this;
        }

        /**
         * 批量添加功能工具
         */
        public Builder addTool(Collection<FunctionTool> tools) {
            config.addTool(tools);
            return this;
        }

        /**
         * 通过提供者添加工具
         */
        public Builder addTool(ToolProvider toolProvider) {
            config.addTool(toolProvider);
            return this;
        }

        /**
         * 配置 LLM 调用重试机制
         *
         * @param maxRetries   最大重试次数
         * @param retryDelayMs 重试时间间隔（毫秒）
         */
        public Builder retryConfig(int maxRetries, long retryDelayMs) {
            config.setRetryConfig(maxRetries, retryDelayMs);
            return this;
        }

        /**
         * 限制单次任务的最大循环思考步数，防止死循环
         */
        public Builder maxSteps(int val) {
            config.setMaxSteps(val);
            return this;
        }

        /**
         * 设置图结构微调器（允许在自动构建图后进行手动微调）
         */
        public Builder graphAdjuster(Consumer<GraphSpec> graphBuilder) {
            config.setGraphAdjuster(graphBuilder);
            return this;
        }

        /**
         * 定义模型输出中的“完成任务”标识（尽量不要改）
         */
        public Builder finishMarker(String val) {
            config.setFinishMarker(val);
            return this;
        }

        public Builder outputKey(String val) {
            config.setOutputKey(val);
            return this;
        }

        public Builder historyWindowSize(int val) {
            config.setHistoryWindowSize(val);
            return this;
        }

        /**
         * 自定义推理提示词模板生成器
         */
        public Builder promptProvider(ReActPromptProvider val) {
            config.setPromptProvider(val);
            return this;
        }

        /**
         * 配置推理阶段的 ChatModel 选项（如温度、TopP 等）
         */
        public Builder chatOptions(Consumer<ChatOptions> chatOptions) {
            config.setChatOptions(chatOptions);
            return this;
        }

        /**
         * 设置 ReAct 生命周期拦截器
         */
        public Builder addInterceptor(ReActInterceptor... vals) {
            for (ReActInterceptor val : vals) {
                config.addInterceptor(val);
            }
            return this;
        }

        /**
         * 设置 ReAct 生命周期拦截器
         */
        public Builder addInterceptor(ReActInterceptor val, int index) {
            config.addInterceptor(val, index);
            return this;
        }

        /**
         * 实例化 ReActAgent
         */
        public ReActAgent build() {
            if (config.getName() == null) {
                config.setName("react_agent");
            }

            if (config.getDescription() == null) {
                if (config.getTitle() != null) {
                    config.setDescription(config.getTitle());
                } else {
                    config.setDescription(config.getName());
                }
            }

            return new ReActAgent(config);
        }
    }
}