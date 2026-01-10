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
import org.noear.solon.ai.agent.AgentRequest;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.RankEntity;
import org.noear.solon.flow.*;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 团队协作智能体 (Team Agent)
 * * <p>TeamAgent 是一个高度可扩展的多智能体协作容器，其核心底层依托于 <b>Solon Flow</b> 状态机引擎。</p>
 * <p>它将多个独立智能体封装为一个统一的 Agent 接口，对外部调用者屏蔽了内部复杂的协作细节（如主管分配、专家竞标、顺序接力等）。</p>
 * * <p><b>核心机制：</b></p>
 * <ul>
 * <li><b>图驱动 (Graph-Driven)：</b> 协作逻辑被抽象为计算图（Graph），由协议（Protocol）决定拓扑结构。</li>
 * <li><b>上下文隔离：</b> 通过专有的 traceKey 在会话中隔离不同团队的协作痕迹（Trace）。</li>
 * <li><b>动态路由：</b> 支持在运行时根据 LLM 的决策动态决定下一个执行节点。</li>
 * </ul>
 *
 *
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public class TeamAgent implements Agent {
    private static final Logger LOG = LoggerFactory.getLogger(TeamAgent.class);

    /**
     * 团队唯一名称标识
     */
    private final String name;
    /**
     * 团队标题
     */
    private final String title;
    /**
     * 团队职责描述
     */
    private final String description;
    /**
     * 团队轨迹在上下文中的存储键名
     */
    private final String traceKey;

    /**
     * 静态配置对象
     */
    private final TeamConfig config;
    /**
     * 协作执行图实例
     */
    private final Graph graph;
    /**
     * 工作流驱动引擎
     */
    private final FlowEngine flowEngine;

    /**
     * 构造函数：初始化团队容器
     * * @param config 团队配置，包含成员、协议、拦截器等
     */
    public TeamAgent(TeamConfig config) {
        Objects.requireNonNull(config, "Missing config!");

        this.config = config;
        this.name = config.getName();
        this.title = config.getTitle();
        this.description = config.getDescription();

        // 为团队生成的唯一 Trace 标识，确保在多团队并行或嵌套时数据不冲突
        this.traceKey = config.getTraceKey();
        this.flowEngine = FlowEngine.newInstance(true);

        // 注入生命周期拦截器，实现审计、监控或数据增强
        for (RankEntity<TeamInterceptor> item : config.getInterceptorList()) {
            flowEngine.addInterceptor(item.target, item.index);
        }

        // 初始化协作拓扑结构
        this.graph = buildGraph();
    }

    /**
     * 依据协作协议构建计算图
     * <p>1. 协议定义骨架（如 HIERARCHICAL 模式自动创建 Supervisor 节点）。</p>
     * <p>2. 微调器进行个性化修饰（如增加特定的条件分支）。</p>
     */
    protected Graph buildGraph() {
        return Graph.create(this.name(), spec -> {
            // 由协议介入构建基础图谱
            if (config.getChatModel() != null) {
                config.getProtocol().buildGraph(spec);
            }

            // 执行用户自定义的图结构调整
            if (config.getGraphAdjuster() != null) {
                config.getGraphAdjuster().accept(spec);
            }
        });
    }

    /**
     * 获取团队内部运行的计算图
     */
    public Graph getGraph() {
        return graph;
    }

    /**
     * 获取团队原始配置
     */
    public TeamConfig getConfig() {
        return config;
    }

    /**
     * 从当前会话中提取此团队的执行踪迹
     */
    public @Nullable TeamTrace getTrace(AgentSession session) {
        return session.getSnapshot().getAs(traceKey);
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

    public String getTraceKey() {
        return traceKey;
    }

    /**
     * 创建异步或流式请求包装器
     */
    public AgentRequest prompt(Prompt prompt) {
        return new TeamRequestImpl(this, prompt);
    }

    /**
     * 创建基于文本的请求包装器
     */
    public AgentRequest prompt(String prompt) {
        return prompt(Prompt.of(prompt));
    }

    /**
     * 执行团队协作（同步阻塞模式）
     * * <p><b>执行全生命周期：</b></p>
     * <ul>
     * <li>1. <b>环境对齐：</b> 初始化 TeamTrace 并准备运行参数。</li>
     * <li>2. <b>前置拦截：</b> 触发 {@code onAgentStart} 监听。</li>
     * <li>3. <b>引擎求值：</b> 驱动 Solon Flow 引擎，根据协议在节点间跳转。</li>
     * <li>4. <b>结果聚合：</b> 协作结束后，根据协议策略提取最终答案（Final Answer）。</li>
     * <li>5. <b>状态同步：</b> 更新会话历史，同步 Context 快照，并触发 {@code onAgentEnd}。</li>
     * </ul>
     */
    @Override
    public AssistantMessage call(Prompt prompt, AgentSession session) throws Throwable {
        FlowContext context = session.getSnapshot();
        TeamTrace trace = context.computeIfAbsent(traceKey, k -> new TeamTrace(prompt));

        // 注入运行时配置与关联
        trace.prepare(config, session, name);

        if (prompt != null) {
            context.trace().recordNode(graph, null);
            trace.setPrompt(prompt);
            trace.resetIterationsCount();
        }

        // 触发协作启动拦截
        config.getInterceptorList().forEach(item -> item.target.onTeamStart(trace));

        try {
            // 历史消息归档
            if (prompt != null) {
                prompt.getMessages().forEach(m -> session.addHistoryMessage(this.name, m));
            }

            // 驱动 Flow 引擎执行（闭包内注入协议上下文）
            context.with(Agent.KEY_CURRENT_TRACE_KEY, traceKey, () -> {
                context.with(Agent.KEY_PROTOCOL, config.getProtocol(), () -> {
                    flowEngine.eval(graph, context);
                });
            });

            // 结果收敛逻辑：优先取明确标记的 Answer，兜底取最后一个专家的回复
            String result = trace.getFinalAnswer();
            if (result == null) {
                result = trace.getLastStepContent();
            }

            trace.setFinalAnswer(result);

            // 如果配置了 outputKey，则将结果写入全局上下文
            if (Assert.isNotEmpty(config.getOutputKey())) {
                context.put(config.getOutputKey(), result);
            }

            AssistantMessage assistantMessage = ChatMessage.ofAssistant(result);
            session.addHistoryMessage(this.name, assistantMessage);
            session.updateSnapshot(context);

            // 触发协作完成拦截
            config.getInterceptorList().forEach(item -> item.target.onTeamEnd(trace));

            return assistantMessage;

        } finally {
            // 释放或清理协议相关的运行时资源
            config.getProtocol().onTeamFinished(context, trace);
        }
    }

    /// ////////////////// Builder 模式实现 ///////////////////////////////

    /**
     * 开启 TeamAgent 构建流程，传入主管模型（若为 null 则需要手动编排图逻辑）
     */
    public static Builder of(@Nullable ChatModel chatModel) {
        return new Builder(chatModel);
    }

    /**
     * 团队智能体流式构建器
     */
    public static class Builder {
        private final TeamConfig config;

        public Builder(@Nullable ChatModel chatModel) {
            this.config = new TeamConfig(chatModel);
        }

        /**
         * 链式调用增强器
         */
        public Builder then(Consumer<Builder> consumer) {
            consumer.accept(this);
            return this;
        }

        /**
         * 设置团队逻辑标识
         */
        public Builder name(String name) {
            config.setName(name);
            return this;
        }

        /**
         * 设置团队能力简述
         */
        public Builder description(String description) {
            config.setDescription(description);
            return this;
        }

        /**
         * 批量注入团队成员（专家）
         */
        public Builder addAgent(Agent... agents) {
            for (Agent agent : agents) {
                config.addAgent(agent);
            }
            return this;
        }

        /**
         * 注册全局拦截器
         */
        public Builder addInterceptor(TeamInterceptor... interceptors) {
            for (TeamInterceptor interceptor : interceptors) {
                config.addInterceptor(interceptor);
            }
            return this;
        }

        /**
         * 注册带排序权重的拦截器
         */
        public Builder addInterceptor(TeamInterceptor interceptor, int index) {
            config.addInterceptor(interceptor, index);
            return this;
        }

        /**
         * 自定义系统指令（System Prompt）的生成模板
         */
        public Builder systemPrompt(TeamSystemPrompt promptProvider) {
            config.setTeamSystem(promptProvider);
            return this;
        }

        /**
         * 设置任务完结的语义标识符
         */
        public Builder finishMarker(String finishMarker) {
            config.setFinishMarker(finishMarker);
            return this;
        }

        /**
         * 设置结果输出的目标 Key
         */
        public Builder outputKey(String outputKey) {
            config.setOutputKey(outputKey);
            return this;
        }

        /**
         * 细粒度配置调度器的容错重试策略
         */
        public Builder retryConfig(int maxRetries, long retryDelayMs) {
            config.setRetryConfig(maxRetries, retryDelayMs);
            return this;
        }

        /**
         * 限制协作最大轮次，保护计算资源
         */
        public Builder maxTotalIterations(int maxTotalIterations) {
            config.setMaxTotalIterations(maxTotalIterations);
            return this;
        }

        /**
         * 核心配置：切换不同的团队协作协议（如 Swarm, Sequential 等）
         */
        public Builder protocol(TeamProtocolFactory protocolFactory) {
            config.setProtocol(protocolFactory);
            return this;
        }

        /**
         * 手动微调流图结构
         */
        public Builder graphAdjuster(Consumer<GraphSpec> graphBuilder) {
            config.setGraphAdjuster(graphBuilder);
            return this;
        }

        /**
         * 定制主管模型推理参数（如 Temperature 为 0 以获取稳定的调度逻辑）
         */
        public Builder chatOptions(Consumer<ChatOptions> chatOptions) {
            config.setChatOptions(chatOptions);
            return this;
        }

        /**
         * 校验并实例化 TeamAgent。
         * <p>注意：必须至少提供一名成员或具备自定义图调整逻辑，否则无法形成有效的协作链路。</p>
         */
        public TeamAgent build() {
            if (config.getName() == null) {
                config.setName("team_agent");
            }

            // 兜底描述处理
            if (config.getDescription() == null) {
                config.setDescription(config.getTitle() != null ? config.getTitle() : config.getName());
            }

            if (config.getAgentMap().isEmpty() && config.getGraphAdjuster() == null) {
                throw new IllegalStateException("The agent or graphAdjuster is required for a TeamAgent");
            }

            return new TeamAgent(config);
        }
    }
}