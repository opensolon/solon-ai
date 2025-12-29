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

public class ReActAgent implements Agent {
    private final ReActConfig config;
    private final FlowEngine flowEngine;

    public ReActAgent(ChatModel chatModel) {
        this(new ReActConfig(chatModel));
    }

    public ReActAgent(ReActConfig config) {
        this.config = config;
        this.flowEngine = FlowEngine.newInstance();
    }

    @Override
    public String run(String prompt) throws Throwable {
        if (config.isEnableLogging()) {
            LogUtil.global().info("Starting ReActAgent with prompt: " + prompt);
        }

        // 使用最新 spec 模式构建图
        Graph graph = Graph.create("react_agent", "ReAct 流程", spec -> {
            // 开始节点
            spec.addStart("start").linkAdd("node_model");

            // 模型思考节点
            spec.addExclusive("node_model")
                    .task(new ReActModelTask(config))
                    // 如果 status 为 call_tool，则流向工具节点
                    .linkAdd("node_tools", ls -> ls.when((ctx) -> {
                        return "call_tool".equals(ctx.get("status"));
                    }))
                    // 默认（或 status 为 finish）流向结束
                    .linkAdd("end");

            // 工具执行节点
            spec.addActivity("node_tools")
                    .task(new ReActToolTask(config))
                    // 执行完工具后回到模型思考
                    .linkAdd("node_model");

            spec.addEnd("end");
        });

        // 初始化上下文状态
        FlowContext context = FlowContext.of()
                .put("prompt", prompt)
                .put("current_iteration", new AtomicInteger(0))
                .put("conversation_history", new ArrayList<ChatMessage>())
                .put("status", "continue"); // 控制流程状态

        flowEngine.eval(graph, context);

        return context.getOrDefault("final_answer", "").toString();
    }
}