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
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.flow.*;
import org.noear.solon.lang.Preview;

import java.util.LinkedHashMap;
import java.util.Map;
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
    private String name;
    private String description;
    private final Graph graph;
    private final boolean resetOnNewPrompt;
    private final FlowEngine flowEngine;

    public TeamAgent(Graph graph) {
        this(graph, null);
    }

    public TeamAgent(Graph graph, String name) {
        this(graph, name, null, false);
    }

    public TeamAgent(Graph graph, String name, String description, boolean resetOnNewPrompt) {
        this.flowEngine = FlowEngine.newInstance();
        this.graph = Objects.requireNonNull(graph);
        this.name = (name == null ? "team_agent" : name);
        this.description = description;
        this.resetOnNewPrompt = resetOnNewPrompt;
    }

    public Graph getGraph() {
        return graph;
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
        String traceKey = "__" + name();
        TeamTrace tmpTrace = context.getAs(traceKey);

        boolean isNewPrompt = (prompt != null);

        if (tmpTrace == null) {
            tmpTrace = new TeamTrace();
            context.put(traceKey, tmpTrace);
        } else if (isNewPrompt && resetOnNewPrompt) {
            // 如果配置为重置，则清空轨迹重新开始
            tmpTrace = new TeamTrace();
            context.put(traceKey, tmpTrace);
        }

        // 更新上下文
        if (prompt != null) {
            context.put(Agent.KEY_PROMPT, prompt);
            context.put(Agent.KEY_ITERATIONS, 0);

            // 关键：如果是新任务且需要重置，清空 lastNode
            if (resetOnNewPrompt) {
                context.lastNode(null);
                tmpTrace.setLastNode((NodeTrace) null);
            }
        } else {
            context.put(Agent.KEY_ITERATIONS, 0);
        }

        TeamTrace trace = tmpTrace;

        try {
            context.with(Agent.KEY_CURRENT_TRACE_KEY, traceKey, () -> {
                flowEngine.eval(graph, trace.getLastNodeId(), context);
            });
        } finally {
            trace.setLastNode(context.lastNode());
        }

        String answer = context.getAs(Agent.KEY_ANSWER);
        if (answer == null && trace.getStepCount() > 0) {
            answer = trace.getSteps().get(trace.getStepCount() - 1).getContent();
        }

        trace.setFinalAnswer(answer);
        return answer;
    }

    /// ///////////////////////////////

    public static Builder builder(ChatModel chatModel) {
        return new Builder(chatModel);
    }

    public static class Builder {

        private String name;
        private String description;
        private boolean resetOnNewPrompt;
        private final ChatModel chatModel;
        private final Map<String, Agent> agentMap = new LinkedHashMap<>();
        private Consumer<GraphSpec> graphBuilder;
        private int maxTotalIterations = 8;
        private TeamPromptProvider promptProvider = TeamPromptProviderEn.getInstance();

        public Builder(ChatModel chatModel) {
            this.chatModel = chatModel;
        }

        public Builder addAgent(Agent agent) {
            Objects.requireNonNull(agent.name(), "agent.name");
            Objects.requireNonNull(agent.description(), "agent.description");

            agentMap.put(agent.name(), agent);
            return this;
        }

        public Builder resetOnNewPrompt(boolean resetOnNewPrompt) {
            this.resetOnNewPrompt = resetOnNewPrompt;
            return this;
        }

        public Builder promptProvider(TeamPromptProvider promptProvider) {
            this.promptProvider = promptProvider;
            return this;
        }

        public Builder maxTotalIterations(int maxTotalIterations) {
            this.maxTotalIterations = Math.max(1, maxTotalIterations);
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder graph(Consumer<GraphSpec> graphBuilder) {
            this.graphBuilder = graphBuilder;
            return this;
        }

        public TeamAgent build() {
            Graph graph = initGraph();

            TeamAgent agent = new TeamAgent(graph, name, description, resetOnNewPrompt);

            return agent;
        }

        private Graph initGraph() {
            if (agentMap.isEmpty()) {
                //需要自己构图
                return Graph.create(this.name, spec -> {
                    if (graphBuilder != null) {
                        graphBuilder.accept(spec);
                    }
                });
            } else {
                TeamSupervisorTask task = new TeamSupervisorTask(this.name, chatModel, agentMap, maxTotalIterations, promptProvider);

                //自动管家模式
                return Graph.create(this.name, spec -> {
                    spec.addStart(Agent.ID_START).linkAdd(Agent.ID_ROUTER);

                    spec.addExclusive(Agent.ID_ROUTER).task(task).then(ns -> {
                        for (Agent agent : agentMap.values()) {
                            ns.linkAdd(agent.name(), l -> l.when(ctx ->
                                    agent.name().equalsIgnoreCase(ctx.getAs(Agent.KEY_NEXT_AGENT))));
                        }
                    }).linkAdd(Agent.ID_END);


                    for (Agent agent : agentMap.values()) {
                        spec.addActivity(agent).linkAdd(Agent.ID_ROUTER);
                    }

                    spec.addEnd(Agent.ID_END);

                    if (graphBuilder != null) {
                        graphBuilder.accept(spec);
                    }
                });
            }
        }
    }
}