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
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.flow.*;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

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

        this.config = config;
        this.flowEngine = FlowEngine.newInstance();
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
        if (LOG.isDebugEnabled()) {
            LOG.debug("TeamAgent [{}] starting: {}", this.name, prompt);
        }

        TeamTrace tmpTrace = context.getAs(traceKey);

        if (tmpTrace == null) {
            tmpTrace = new TeamTrace(config, prompt);
            context.put(traceKey, tmpTrace);
        } else  {
            tmpTrace.setConfig(config);
        }

        if (prompt != null) {
            context.lastNode(null);

            tmpTrace.setPrompt(prompt);
            tmpTrace.setLastNode(null);
            tmpTrace.resetIterations();
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

        String result = trace.getFinalAnswer();
        if (result == null && trace.getStepCount() > 0) {
            result = trace.getSteps().get(trace.getStepCount() - 1).getContent();
        }

        trace.setFinalAnswer(result);


        if(config != null && config.getInterceptor() != null){
            config.getInterceptor().onCallEnd(context, prompt);
        }

        return result;
    }

    /// ///////////////////////////////

    public static TeamAgentBuilder builder(ChatModel chatModel) {
        return new TeamAgentBuilder(chatModel);
    }
}