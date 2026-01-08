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
import org.noear.solon.ai.agent.react.ReActInterceptor;
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
 * 团队协作智能体 (Agent Team)
 * <p>基于 Solon Flow 实现的多智能体协作容器。它负责管理团队成员、执行协作协议（如顺序、蜂群等）、
 * 并在流上下文中维护任务的执行路径（Trace）。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public class TeamAgent implements Agent {
    private static final Logger LOG = LoggerFactory.getLogger(TeamAgent.class);

    private final String name;
    private final String title;
    private final String description;
    private final String traceKey;

    private final TeamConfig config;
    private final Graph graph;
    private final FlowEngine flowEngine;


    public TeamAgent(TeamConfig config) {
        Objects.requireNonNull(config, "Missing config!");

        this.config = config;

        this.name = config.getName();
        this.title = config.getTitle();
        this.description = config.getDescription();

        this.traceKey = "__" + name; // 用于在 FlowContext 中存储 TeamTrace 的唯一标识
        this.flowEngine = FlowEngine.newInstance(true);

        // 1. 挂载流拦截器（用于全局监控或审计）
        for (RankEntity<TeamInterceptor> item : config.getInterceptorList()) {
            flowEngine.addInterceptor(item.target, item.index);
        }

        // 2. 构建执行计算图
        this.graph = buildGraph();
    }

    /**
     * 构建计算图（可重写）
     */
    protected Graph buildGraph() {
        return Graph.create(this.name(), spec -> {
            // 1. 根据协议构建基础骨架 (如 Swarm 的 Supervisor 节点)
            if (config.getChatModel() != null) {
                config.getProtocol().buildGraph(config, spec);
            }

            // 2. 应用用户自定义的微调逻辑
            if (config.getGraphAdjuster() != null) {
                config.getGraphAdjuster().accept(spec);
            }
        });
    }

    /**
     * 获取协作流程图定义
     */
    public Graph getGraph() {
        return graph;
    }

    /**
     * 获取配置（方便重写使用）
     */
    protected TeamConfig getConfig() {
        return config;
    }

    /**
     * 从上下文中获取当前团队的执行追踪实例
     *
     * @param session 会话
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
     * 提示语
     */
    public AgentRequest prompt(Prompt prompt) {
        return new TeamRequestImpl(this, prompt);
    }

    /**
     * 提示语
     */
    public AgentRequest prompt(String prompt) {
        return prompt(Prompt.of(prompt));
    }

    /**
     * 触发团队协作调用
     * <p>流程：初始化/更新状态 -> 执行流图 -> 提取结果 -> 资源清理/回调通知</p>
     */
    public AssistantMessage call(Prompt prompt, AgentSession session) throws Throwable {
        // [阶段1：状态初始化] 尝试复用或创建新的执行追踪实例
        FlowContext context = session.getSnapshot();
        TeamTrace trace = context.getAs(traceKey);

        if (trace == null) {
            trace = new TeamTrace(prompt);
            context.put(traceKey, trace);
        }

        trace.prepare(config, session, name);

        if (prompt != null) {
            // 记录流节点的进入，支持多级嵌套追踪
            context.trace().recordNode(graph, null);

            trace.setPrompt(prompt);
            trace.resetIterationsCount();
        } else {
            prompt = trace.getPrompt();
            trace.resetIterationsCount();
        }

        Objects.requireNonNull(prompt, "Missing prompt!");

        //开始事件
        for (RankEntity<TeamInterceptor> item : config.getInterceptorList()) {
            item.target.onAgentStart(trace);
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("TeamAgent [{}] starting: {}", this.name, prompt.getUserContent());
        }

        try {
            if (prompt != null) {
                for (ChatMessage message : prompt.getMessages()) {
                    session.addHistoryMessage(this.name, message);
                }
            }

            // [阶段2：流图执行] 在特定的上下文范围内驱动 FlowEngine 执行协作逻辑
            context.with(Agent.KEY_CURRENT_TRACE_KEY, traceKey, () -> {
                flowEngine.eval(graph, context);
            });

            // [阶段3：结果提取] 优先获取明确的最终答案，否则取最后一个执行步骤的内容
            String result = trace.getFinalAnswer();
            if (result == null && trace.getStepCount() > 0) {
                result = trace.getSteps().get(trace.getStepCount() - 1).getContent();
            }

            trace.setFinalAnswer(result);

            if (Assert.isNotEmpty(config.getOutputKey())) {
                context.put(config.getOutputKey(), result);
            }

            AssistantMessage assistantMessage = ChatMessage.ofAssistant(result);

            session.addHistoryMessage(this.name, assistantMessage);
            session.updateSnapshot(context);

            //结束事件
            for (RankEntity<TeamInterceptor> item : config.getInterceptorList()) {
                item.target.onAgentEnd(trace);
            }

            return assistantMessage;
        } finally {
            // [阶段4：生命周期销毁] 无论成功失败，触发拦截器和协议的清理回调
            if (config != null) {
                try {
//                    if (config.getInterceptor() != null) {
//                        config.getInterceptor().onCallEnd(context, prompt);
//                    }

                    config.getProtocol().onFinished(context, trace);
                } catch (Throwable e) {
                    LOG.warn("TeamAgent [{}] finalization failed", name, e);
                }
            }
        }
    }

    /// ///////////////////////////////

    /**
     * 创建基于指定聊天模型的构建器
     *
     * @param chatModel 用于主管(Supervisor)决策的基础大语言模型。如果 null 则为自由模式
     */
    public static Builder of(@Nullable ChatModel chatModel) {
        return new Builder(chatModel);
    }

    /**
     * 团队智能体构建器
     */
    public static class Builder {
        private final TeamConfig config;

        public Builder(@Nullable ChatModel chatModel) {
            this.config = new TeamConfig(chatModel);
        }

        /**
         * 然后（构建自己）
         */
        public Builder then(Consumer<Builder> consumer) {
            consumer.accept(this);
            return this;
        }


        /**
         * 设置团队名称
         */
        public Builder name(String name) {
            config.setName(name);
            return this;
        }

        /**
         * 设置团队描述
         */
        public Builder description(String description) {
            config.setDescription(description);
            return this;
        }

        /**
         * 向团队中添加执行成员(Agent)
         */
        public Builder addAgent(Agent agent) {
            config.addAgent(agent);
            return this;
        }

        /**
         * 设置 Team 生命周期拦截器
         */
        public Builder addInterceptor(TeamInterceptor interceptor) {
            config.addInterceptor(interceptor);
            return this;
        }

        /**
         * 设置 Team 生命周期拦截器
         */
        public Builder addInterceptor(TeamInterceptor interceptor, int index) {
            config.addInterceptor(interceptor, index);
            return this;
        }

        /**
         * 设置提示词提供者（用于自定义系统指令模板）
         */
        public Builder promptProvider(TeamPromptProvider promptProvider) {
            config.setPromptProvider(promptProvider);
            return this;
        }

        /**
         * 设置任务完成标识符（大模型输出此内容时视为任务结束）
         */
        public Builder finishMarker(String finishMarker) {
            config.setFinishMarker(finishMarker);
            return this;
        }

        public Builder outputKey(String outputKey) {
            config.setOutputKey(outputKey);
            return this;
        }

        /**
         * 配置 LLM 调用的重试策略
         *
         * @param maxRetries   最大重试次数
         * @param retryDelayMs 重试延迟时间（毫秒）
         */
        public Builder retryConfig(int maxRetries, long retryDelayMs) {
            config.setRetryConfig(maxRetries, retryDelayMs);
            return this;
        }

        /**
         * 设置团队协作的最大迭代步数（防止死循环）
         */
        public Builder maxTotalIterations(int maxTotalIterations) {
            config.setMaxTotalIterations(maxTotalIterations);
            return this;
        }

        /**
         * 设置协作协议（核心：决定了团队的拓扑结构和运行模式）
         */
        public Builder protocol(TeamProtocol protocol) {
            config.setProtocol(protocol);
            return this;
        }

        /**
         * 设置图结构微调器（允许在协议自动构建图后进行手动干预）
         */
        public Builder graphAdjuster(Consumer<GraphSpec> graphBuilder) {
            config.setGraphAdjuster(graphBuilder);
            return this;
        }

        /**
         * 设置主管(Supervisor)角色的聊天模型配置选项
         */
        public Builder chatOptions(Consumer<ChatOptions> chatOptions) {
            config.setChatOptions(chatOptions);
            return this;
        }

        /**
         * 构建并实例化 TeamAgent
         */
        public TeamAgent build() {
            if (config.getName() == null) {
                config.setName("team_agent");
            }

            if (config.getDescription() == null) {
                if (config.getTitle() != null) {
                    config.setDescription(config.getTitle());
                } else {
                    config.setDescription(config.getName());
                }
            }

            // 基础合法性校验：必须有成员或显式的图构建逻辑
            if (config.getAgentMap().isEmpty() && config.getGraphAdjuster() == null) {
                throw new IllegalStateException("The agent or graphBuilder is required");
            }

            return new TeamAgent(config);
        }
    }
}