package org.noear.solon.ai.agent.multi;

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.FlowEngine;
import org.noear.solon.flow.Graph;

import java.util.HashMap;
import java.util.Map;

/**
 * MultiAgent 实现
 * 基于图结构的智能体编排
 */
public class MultiAgent implements Agent {
    private final Map<String, Agent> agents = new HashMap<>();
    private final FlowEngine flowEngine;
    private final Graph graph;
    private final String startNode;

    public MultiAgent(Graph graph) {
        this.flowEngine = FlowEngine.newInstance();
        this.graph = graph;
        this.startNode = "start"; // 默认起始点
    }

    @Override
    public String ask(FlowContext context, String prompt) throws Throwable {
        // 1. 将初始 prompt 放入上下文
        context.put("prompt", prompt);

        // 2. 执行流引擎（会触发各个节点上的 Agent）
        flowEngine.eval(graph, startNode, context);

        // 3. 从上下文中获取最终结果（通常由最后一个节点产生）
        return context.getAs("answer");
    }

    /**
     * 辅助构建器类
     */
    public static class Builder {
        private Graph graph;

        public Builder graph(Graph graph) {
            this.graph = graph;
            return this;
        }

        public MultiAgent build() {
            return new MultiAgent(graph);
        }
    }
}