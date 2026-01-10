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
import org.noear.solon.ai.chat.tool.ToolSchemaUtil;
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

import java.lang.reflect.Type;
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
@Preview("3.8.1")
public class ReActAgent implements Agent {
    private static final Logger LOG = LoggerFactory.getLogger(ReActAgent.class);

    /** 智能体名称 */
    private final String name;
    /** 智能体标题 */
    private final String title;
    /** 智能体功能描述 */
    private final String description;
    /** 推理配置 */
    private final ReActConfig config;
    /** 逻辑计算图 */
    private final Graph graph;
    /** 工作流引擎 */
    private final FlowEngine flowEngine;
    /** 状态追踪键 */
    private final String traceKey;

    /**
     * 构造函数
     *
     * @param config ReAct 配置对象
     */
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
     *
     * @return 逻辑计算图
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
     *
     * @return 逻辑图
     */
    public Graph getGraph() {
        return graph;
    }

    /**
     * 获取配置（方便重写使用）
     *
     * @return 推理配置
     */
    public ReActConfig getConfig() {
        return config;
    }

    /**
     * 从上下文中获取当前 Agent 的执行状态追踪实例
     *
     * @param session 会话对象
     * @return 推理轨迹状态
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

    /**
     * 构建推理请求
     *
     * @param prompt 提示词对象
     * @return 请求对象
     */
    public AgentRequest prompt(Prompt prompt) {
        return new ReActRequestImpl(this, prompt);
    }

    /**
     * 构建推理请求
     *
     * @param prompt 提示词文本
     * @return 请求对象
     */
    public AgentRequest prompt(String prompt) {
        return new ReActRequestImpl(this, Prompt.of(prompt));
    }

    /**
     * 智能体调用入口
     *
     * @param prompt  用户输入的提示词
     * @param session 会话
     * @return 最终响应消息
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

        // 1. 记忆加载时序：新推理周期开始时，先从 Session 加载历史记忆
        if (trace.getMessages().isEmpty()) {
            // A. 加载历史（如果配置了窗口）
            if (config.getHistoryWindowSize() > 0) {
                // 此时尚未存入当前 prompt，获取的是纯历史记录
                Collection<ChatMessage> history = session.getHistoryMessages(name, config.getHistoryWindowSize());
                for (ChatMessage message : history) {
                    trace.appendMessage(message);
                }
            }
        }

        // 2. 消息持久化：将当前请求同步到 Session 归档与 Trace 推理上下文
        if (!Prompt.isEmpty(prompt)) {
            for (ChatMessage message : prompt.getMessages()) {
                // 持久化到 Session（归档）
                session.addHistoryMessage(name, message);
                // 追加到 Trace（作为推理上下文）
                trace.appendMessage(message);
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

        // 触发开始事件
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

        // 将 AI 的最终回答持久化到会话并更新快照
        AssistantMessage assistantMessage = ChatMessage.ofAssistant(result);
        session.addHistoryMessage(name, assistantMessage);
        session.updateSnapshot(context);

        // 触发结束事件
        for (RankEntity<ReActInterceptor> item : config.getInterceptorList()) {
            item.target.onAgentEnd(trace);
        }

        return assistantMessage;
    }

    /// //////////// Builder 静态构造模式 ////////////

    /**
     * 创建构建器
     *
     * @param chatModel 推理模型
     * @return 构建器实例
     */
    public static Builder of(ChatModel chatModel) {
        return new Builder(chatModel);
    }

    /**
     * ReAct 智能体构建器
     */
    public static class Builder {
        /** 推理配置 */
        private ReActConfig config;

        /**
         * 构造构建器
         *
         * @param chatModel 推理模型
         */
        public Builder(ChatModel chatModel) {
            this.config = new ReActConfig(chatModel);
        }

        /**
         * 链式配置扩展
         *
         * @param consumer 配置函数
         * @return 构建器
         */
        public Builder then(Consumer<Builder> consumer) {
            consumer.accept(this);
            return this;
        }

        /**
         * 设置智能体名称
         *
         * @param val 名称
         * @return 构建器
         */
        public Builder name(String val) {
            config.setName(val);
            return this;
        }

        /**
         * 设置智能体显示标题
         *
         * @param val 标题
         * @return 构建器
         */
        public Builder title(String val) {
            config.setTitle(val);
            return this;
        }

        /**
         * 设置智能体描述（协作模式下尤为重要）
         *
         * @param val 描述
         * @return 构建器
         */
        public Builder description(String val) {
            config.setDescription(val);
            return this;
        }

        /**
         * 添加功能工具
         *
         * @param tool 工具对象
         * @return 构建器
         */
        public Builder addTool(FunctionTool tool) {
            config.addTool(tool);
            return this;
        }

        /**
         * 批量添加工具
         *
         * @param tools 工具集合
         * @return 构建器
         */
        public Builder addTool(Collection<FunctionTool> tools) {
            config.addTool(tools);
            return this;
        }

        /**
         * 通过提供者添加工具
         *
         * @param toolProvider 工具提供者
         * @return 构建器
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
         * @return 构建器
         */
        public Builder retryConfig(int maxRetries, long retryDelayMs) {
            config.setRetryConfig(maxRetries, retryDelayMs);
            return this;
        }

        /**
         * 限制单次任务的最大循环步数
         *
         * @param val 最大步数
         * @return 构建器
         */
        public Builder maxSteps(int val) {
            config.setMaxSteps(val);
            return this;
        }

        /**
         * 设置图结构微调器
         *
         * @param graphBuilder 微调逻辑
         * @return 构建器
         */
        public Builder graphAdjuster(Consumer<GraphSpec> graphBuilder) {
            config.setGraphAdjuster(graphBuilder);
            return this;
        }

        /**
         * 定义模型输出中的“完成任务”标识
         *
         * @param val 标识符
         * @return 构建器
         */
        public Builder finishMarker(String val) {
            config.setFinishMarker(val);
            return this;
        }

        /**
         * 设置输出结果在 Context 中的存储键
         *
         * @param val 存储键
         * @return 构建器
         */
        public Builder outputKey(String val) {
            config.setOutputKey(val);
            return this;
        }

        /**
         * 设置输出格式要求（例如 JSON 结构描述）
         */
        public Builder outputSchema(String val) {
            config.setOutputSchema(val);
            return this;
        }

        /**
         * 快捷方式：通过 Class 生成 schema 描述（如果 ChatModel 支持此功能）
         */
        public Builder outputSchema(Type type) {
            config.setOutputSchema(ToolSchemaUtil.buildOutputSchema(type));
            return this;
        }

        /**
         * 设置历史窗口大小（多轮对话记忆条数）
         *
         * @param val 窗口大小
         * @return 构建器
         */
        public Builder historyWindowSize(int val) {
            config.setHistoryWindowSize(val);
            return this;
        }

        /**
         * 自定义推理提示词模板生成器
         *
         * @param val 模板生成器
         * @return 构建器
         */
        public Builder systemPrompt(ReActSystemPrompt val) {
            config.setPromptProvider(val);
            return this;
        }

        /**
         * 配置 ChatModel 推理选项
         *
         * @param chatOptions 配置函数
         * @return 构建器
         */
        public Builder chatOptions(Consumer<ChatOptions> chatOptions) {
            config.setChatOptions(chatOptions);
            return this;
        }

        /**
         * 添加生命周期拦截器
         *
         * @param vals 拦截器数组
         * @return 构建器
         */
        public Builder addInterceptor(ReActInterceptor... vals) {
            for (ReActInterceptor val : vals) {
                config.addInterceptor(val);
            }
            return this;
        }

        /**
         * 添加生命周期拦截器（带排序索引）
         *
         * @param val   拦截器
         * @param index 排序索引
         * @return 构建器
         */
        public Builder addInterceptor(ReActInterceptor val, int index) {
            config.addInterceptor(val, index);
            return this;
        }

        /**
         * 实例化 ReActAgent
         *
         * @return 智能体实例
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