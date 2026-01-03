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
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.flow.*;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 团队协作智能体
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public class TeamAgent implements Agent {
    private static final Logger LOG = LoggerFactory.getLogger(TeamAgent.class);

    private final String name;
    private final String description;
    private final String traceKey;

    private final TeamConfig config;
    private final Graph graph;
    private final FlowEngine flowEngine;


    public TeamAgent(Graph graph) {
        this(graph, null);
    }

    public TeamAgent(Graph graph, String name) {
        this(graph, name, null);
    }

    public TeamAgent(Graph graph, String name, String description) {
        this(graph, name, description, null);
    }

    public TeamAgent(Graph graph, String name, String description, TeamConfig config) {
        if (graph == null || graph.getNodes().isEmpty()) {
            throw new IllegalStateException("Missing graph definition");
        }

        this.name = (name == null ? "team_agent" : name);
        this.traceKey = "__" + name;
        this.description = description;

        if (config == null) {
            config = new TeamConfig(null);
        }

        this.config = config;

        this.flowEngine = FlowEngine.newInstance(true);
        this.graph = Objects.requireNonNull(graph);

        if (config != null && config.getInterceptor() != null) {
            flowEngine.addInterceptor(config.getInterceptor());
        }
    }

    /**
     * 获取图
     */
    public Graph getGraph() {
        return graph;
    }

    /**
     * 获取跟踪实例
     */
    public @Nullable TeamTrace getTrace(FlowContext context) {
        return context.getAs(traceKey);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }


    @Override
    public String call(FlowContext context, Prompt prompt) throws Throwable {
        if (LOG.isDebugEnabled()) {
            LOG.debug("TeamAgent [{}] starting: {}", this.name, prompt.getUserContent());
        }

        TeamTrace trace = context.getAs(traceKey);

        if (trace == null) {
            trace = new TeamTrace(config, prompt);
            context.put(traceKey, trace);
        } else {
            trace.setConfig(config);
        }

        if (prompt != null) {
            context.trace().recordNode(graph, null);

            trace.setPrompt(prompt);
            trace.resetIterations();
        } else {
            trace.resetIterations();
        }

        try {
            context.with(Agent.KEY_CURRENT_TRACE_KEY, traceKey, () -> {
                flowEngine.eval(graph, context);
            });

            String result = trace.getFinalAnswer();
            if (result == null && trace.getStepCount() > 0) {
                result = trace.getSteps().get(trace.getStepCount() - 1).getContent();
            }

            trace.setFinalAnswer(result);
            return result;
        } finally {
            if (config != null) {
                try {
                    if (config.getInterceptor() != null) {
                        config.getInterceptor().onCallEnd(context, prompt);
                    }
                    config.getProtocol().onFinished(context, trace);
                } catch (Throwable e) {
                    LOG.warn("TeamAgent [{}] finalization failed", name, e);
                }
            }
        }
    }

    /// ///////////////////////////////

    public static Builder of(ChatModel chatModel) {
        return new Builder(chatModel);
    }

    public static class Builder {
        private final TeamConfig config;

        public Builder(ChatModel chatModel) {
            this.config = new TeamConfig(chatModel);
        }

        public Builder name(String name) {
            config.setName(name);
            return this;
        }

        public Builder description(String description) {
            config.setDescription(description);
            return this;
        }

        public Builder addAgent(Agent agent) {
            config.addAgent(agent);
            return this;
        }

        public Builder interceptor(TeamInterceptor interceptor) {
            config.setInterceptor(interceptor);
            return this;
        }

        public Builder promptProvider(TeamPromptProvider promptProvider) {
            config.setPromptProvider(promptProvider);
            return this;
        }

        public Builder finishMarker(String finishMarker) {
            config.setFinishMarker(finishMarker);
            return this;
        }

        /**
         * 重试配置
         *
         * @param maxRetries   最大重试次数
         * @param retryDelayMs 重试延迟时间
         *
         */
        public Builder retryConfig(int maxRetries, long retryDelayMs) {
            config.setRetryConfig(maxRetries, retryDelayMs);
            return this;
        }

        public Builder maxTotalIterations(int maxTotalIterations) {
            config.setMaxTotalIterations(maxTotalIterations);
            return this;
        }

        public Builder protocol(TeamProtocol protocol) {
            config.setProtocol(protocol);
            return this;
        }

        public Builder graphAdjuster(Consumer<GraphSpec> graphBuilder) {
            config.setGraphAdjuster(graphBuilder);
            return this;
        }

        public Builder supervisorOptions(Consumer<ChatOptions> supervisorOptions) {
            config.setSupervisorOptions(supervisorOptions);
            return this;
        }

        public TeamAgent build() {
            if (config.getName() == null) {
                config.setName("team_agent");
            }

            if (config.getDescription() == null) {
                config.setDescription(config.getName());
            }

            if (config.getAgentMap().isEmpty() && config.getGraphAdjuster() == null) {
                throw new IllegalStateException("The agent or graphBuilder is required");
            }

            return new TeamAgent(createGraph(),
                    config.getName(),
                    config.getDescription(),
                    config);
        }

        private Graph createGraph() {
            return Graph.create(config.getName(), spec -> {
                config.getProtocol().buildGraph(config, spec);

                if (config.getGraphAdjuster() != null) {
                    config.getGraphAdjuster().accept(spec);
                }
            });
        }
    }
}