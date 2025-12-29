package org.noear.solon.ai.agent.react;

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.core.util.LogUtil;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.FlowEngine;
import org.noear.solon.flow.Graph;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 优化后的 ReActAgent
 */
public class ReActAgent implements Agent {
    private final ReActConfig config;
    private final FlowEngine flowEngine;
    private Graph graph;

    public ReActAgent(ChatModel chatModel) {
        this(new ReActConfig(chatModel));
    }

    public ReActAgent(ReActConfig config) {
        this.config = config;
        this.flowEngine = FlowEngine.newInstance();
        this.initGraph();
    }

    private synchronized void initGraph() {
        if (graph != null) return;

        // 构建符合 LangGraph 逻辑的状态转移图
        graph = Graph.create("react_agent", "ReAct 流程", spec -> {
            spec.addStart("start").linkAdd("node_model");

            // 模型节点：推理并决定下一步
            spec.addExclusive("node_model")
                    .task(new ReActModelTask(config))
                    .linkAdd("node_tools", l -> l.when(ctx -> "call_tool".equals(ctx.get("status"))))
                    .linkAdd("end"); // 默认回退：结束

            // 工具节点：执行副作用并返回观察结果
            spec.addActivity("node_tools")
                    .task(new ReActToolTask(config))
                    .linkAdd("node_model");

            spec.addEnd("end");
        });
    }

    @Override
    public String run(String prompt) throws Throwable {
        if (config.isEnableLogging()) {
            LogUtil.global().info("Starting ReActAgent: " + prompt);
        }

        FlowContext context = FlowContext.of()
                .put("prompt", prompt)
                .put("current_iteration", new AtomicInteger(0))
                .put("conversation_history", new ArrayList<ChatMessage>())
                .put("status", "continue");

        flowEngine.eval(graph, context);

        String result = context.getOrDefault("final_answer", "").toString();
        if (config.isEnableLogging()) {
            LogUtil.global().info("Final Answer: " + result);
        }
        return result;
    }
}