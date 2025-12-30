package org.noear.solon.ai.agent.multi;

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.FlowEngine;
import org.noear.solon.flow.Graph;

import java.util.Objects;

/**
 * MultiAgent 实现
 * 基于图结构的智能体编排
 */
public class MultiAgent implements Agent {
    private String name = "multi_agent";
    private final FlowEngine flowEngine;
    private final Graph graph;

    public MultiAgent(Graph graph) {
        Objects.requireNonNull(graph, "graph");

        this.flowEngine = FlowEngine.newInstance();
        this.graph = graph;
    }

    public MultiAgent nameAs(String name) {
        Objects.requireNonNull(name, "name");

        this.name = name;
        return this;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String ask(FlowContext context, String prompt) throws Throwable {
        context.put(Agent.KEY_PROMPT, prompt);
        flowEngine.eval(graph, context.lastNodeId(), context);
        return context.getAs(Agent.KEY_ANSWER);
    }
}