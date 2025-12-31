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
    private final FlowEngine flowEngine;
    private final Graph graph;

    public TeamAgent(Graph graph) {
        this(graph, null, null);
    }

    public TeamAgent(Graph graph, String name) {
        this(graph, name, null);
    }

    public TeamAgent(Graph graph, String name, String description) {
        this.flowEngine = FlowEngine.newInstance();
        this.graph = Objects.requireNonNull(graph);
        this.name = (name == null ? "team_agent" : name);
        this.description = description;
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
        // 1. 统一 TraceKey 规范
        String traceKey = "__" + name();

        // 2. 初始化或恢复 TeamTrace
        TeamTrace tmpTrace = context.getAs(traceKey);
        if (tmpTrace == null || prompt != null) {
            tmpTrace = new TeamTrace();
            context.put(traceKey, tmpTrace);

            if (prompt != null) {
                // 开启新任务时，清理上下文残留的迭代状态
                context.put(Agent.KEY_PROMPT, prompt);
                context.put(Agent.KEY_ITERATIONS, 0);
                context.remove(Agent.KEY_HISTORY);
                context.remove(Agent.KEY_ANSWER);
                // 确保从图的起点开始
                context.lastNode(null);
            }
        }

        TeamTrace trace = tmpTrace;

        try {
            //采用变量域的思想传递 KEY_CURRENT_TRACE_KEY
            context.with(Agent.KEY_CURRENT_TRACE_KEY, traceKey, () -> {
                // 3. 驱动图运行（支持断点续运行）
                flowEngine.eval(graph, trace.getLastNodeId(), context);
            });
        } finally {
            // 4. 无论成功与否，持久化最后执行的节点快照
            trace.setLastNode(context.lastNode());
        }

        // 5. 记录并返回结果
        String answer = context.getAs(Agent.KEY_ANSWER);

        if (answer == null && trace.getStepCount() > 0) {
            //如果 Context 里没拿到 answer（比如开发者在 Join 节点漏写了），则从 Trace 历史中取最后一位 Agent 的内容
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

            TeamAgent agent = new TeamAgent(graph, name, description);

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