package org.noear.solon.ai.agent.multi;

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.FlowEngine;
import org.noear.solon.flow.Graph;
import org.noear.solon.lang.Preview;
import java.util.Objects;

/**
 * 多智能体（协同容器）
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public class MultiAgent implements Agent {
    private String name = "multi_agent";
    private final FlowEngine flowEngine;
    private final Graph graph;

    public MultiAgent(Graph graph) {
        this.flowEngine = FlowEngine.newInstance();
        this.graph = Objects.requireNonNull(graph);
    }

    public MultiAgent nameAs(String name) {
        this.name = name;
        return this;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String ask(FlowContext context, String prompt) throws Throwable {
        String traceKey = "__" + name();
        context.put(Agent.KEY_CURRENT_TRACE_KEY, traceKey);

        // 初始化 TeamTrace
        TeamTrace trace = context.getAs(traceKey);
        if (trace == null || prompt != null) {
            trace = new TeamTrace();
            context.put(traceKey, trace);
            if (prompt != null) {
                context.put(Agent.KEY_PROMPT, prompt);
            }
            context.lastNode(null);
        }

        // 驱动图运行
        flowEngine.eval(graph, context.lastNodeId(), context);

        // 记录并返回最终结果
        String answer = context.getAs(Agent.KEY_ANSWER);
        trace.setFinalAnswer(answer);
        return answer;
    }
}