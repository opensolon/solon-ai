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
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;

import java.util.Objects;

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
    private final FlowEngine flowEngine;

    public TeamAgent(Graph graph) {
        this(graph, null);
    }

    public TeamAgent(Graph graph, String name) {
        this(graph, name, null);
    }

    public TeamAgent(Graph graph, String name, String description) {
        if (graph == null || graph.getNodes().isEmpty()) {
            throw new IllegalStateException("Missing graph definition");
        }

        this.flowEngine = FlowEngine.newInstance();
        this.graph = Objects.requireNonNull(graph);
        this.name = (name == null ? "team_agent" : name);
        this.description = description;
    }

    /**
     * 获取图
     *
     */
    public Graph getGraph() {
        return graph;
    }

    /**
     * 获取实例跟踪
     *
     */
    public @Nullable TeamTrace getTrace(FlowContext context) {
        return context.getAs("__" + name);
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

        if (tmpTrace == null) {
            tmpTrace = new TeamTrace();
            context.put(traceKey, tmpTrace);
        }

        if (prompt != null) {
            context.put(Agent.KEY_PROMPT, prompt);
            context.lastNode(null);
            tmpTrace.resetIterations();
            tmpTrace.setLastNode(null);
        } else {
            tmpTrace.resetIterations();
        }

        TeamTrace trace = tmpTrace;

        try {
            context.with(Agent.KEY_CURRENT_TRACE_KEY, traceKey, () -> {
                flowEngine.eval(graph, trace.getLastNodeId(), context);
            });
        } finally {
            trace.setLastNode(context.lastNode());
        }

        String result = trace.getFinalAnswer(); //context.getAs(Agent.KEY_RESULT);
        if (result == null && trace.getStepCount() > 0) {
            result = trace.getSteps().get(trace.getStepCount() - 1).getContent();
        }

        trace.setFinalAnswer(result);
        return result;
    }

    /// ///////////////////////////////

    public static TeamAgentBuilder builder(ChatModel chatModel) {
        return new TeamAgentBuilder(chatModel);
    }
}