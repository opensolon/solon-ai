package org.noear.solon.ai.agent.team;

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
public class TeamAgent implements Agent {
    private String name = "multi_agent";
    private final FlowEngine flowEngine;
    private final Graph graph;

    public TeamAgent(Graph graph) {
        this.flowEngine = FlowEngine.newInstance();
        this.graph = Objects.requireNonNull(graph);
    }

    public TeamAgent nameAs(String name) {
        this.name = name;
        return this;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String ask(FlowContext context, String prompt) throws Throwable {
        // 1. 统一 TraceKey 规范
        String traceKey = "__" + name();
        context.put(Agent.KEY_CURRENT_TRACE_KEY, traceKey);

        // 2. 初始化或恢复 TeamTrace
        TeamTrace trace = context.getAs(traceKey);
        if (trace == null || prompt != null) {
            trace = new TeamTrace();
            context.put(traceKey, trace);

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

        try {
            // 3. 驱动图运行（支持断点续运行）
            flowEngine.eval(graph, trace.getLastNodeId(), context);
        } finally {
            // 4. 无论成功与否，持久化最后执行的节点快照
            trace.setLastNode(context.lastNode());
        }

        // 5. 记录并返回结果
        String answer = context.getAs(Agent.KEY_ANSWER);
        trace.setFinalAnswer(answer);
        return answer;
    }
}