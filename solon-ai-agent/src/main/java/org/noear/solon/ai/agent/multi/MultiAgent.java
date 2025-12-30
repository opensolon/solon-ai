package org.noear.solon.ai.agent.multi;

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.FlowEngine;
import org.noear.solon.flow.Graph;
import java.util.Objects;

public class MultiAgent implements Agent {
    private String name = "multi_agent";
    private final FlowEngine flowEngine;
    private final Graph graph;

    public MultiAgent(Graph graph) {
        this.flowEngine = FlowEngine.newInstance(); // 建议单例或注入
        this.graph = Objects.requireNonNull(graph);
    }

    public MultiAgent nameAs(String name) {
        this.name = name;
        return this;
    }

    @Override
    public String name() { return name; }

    @Override
    public String ask(FlowContext context, String prompt) throws Throwable {
        // 如果是首次运行，初始化 prompt
        if (context.getAs(Agent.KEY_PROMPT) == null) {
            context.put(Agent.KEY_PROMPT, prompt);
        }

        // 核心：利用 flowEngine 的 eval，支持从上次的 nodeId 继续运行（回溯支持）
        flowEngine.eval(graph, context.lastNodeId(), context);

        return context.getAs(Agent.KEY_ANSWER);
    }
}