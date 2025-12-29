package org.noear.solon.ai.agent.react;

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatSessionFactory;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.core.util.LogUtil;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.FlowEngine;
import org.noear.solon.flow.Graph;

/**
 * 优化后的 ReActAgent
 * 基于 Solon Flow 流引擎实现 Thought -> Action -> Observation 循环
 */
public class ReActAgent implements Agent {
    private final ReActConfig config;
    private final Graph graph;

    public ReActAgent(ReActConfig config) {
        this.config = config;
        this.graph = initGraph();
    }

    /**
     * 初始化图结构。定义节点跳转逻辑：
     * 1. node_model -> node_tools (当 status 为 call_tool)
     * 2. node_model -> end (当 status 为 finish)
     */
    private Graph initGraph() {
        return Graph.create("react_agent", "ReAct 流程", spec -> {
            spec.addStart("start").linkAdd("node_model");

            // 模型推理节点：决定是执行工具还是结束
            spec.addExclusive("node_model")
                    .task(new ReActModelTask(config))
                    .linkAdd("node_tools", l -> l.when(ctx -> "call_tool".equals(ctx.<ReActState>getAs("state").getStatus())))
                    .linkAdd("end"); // 默认跳转至结束

            // 工具执行节点：执行具体的函数调用
            spec.addActivity("node_tools")
                    .task(new ReActToolTask(config))
                    .linkAdd("node_model"); // 执行完工具后返回模型节点进行下一轮思考

            spec.addEnd("end");
        });
    }

    @Override
    public String run(FlowContext context, String prompt) throws Throwable {
        if (config.isEnableLogging()) {
            LogUtil.global().info("Starting ReActAgent: " + prompt);
        }

        // 初始化流程上下文，携带对话历史与迭代计数
        ReActState state = context.getAs(ReActState.TAG);
        if (state == null) {
            state = new ReActState(prompt, config.getSessionFactory().getSession(context.getInstanceId()));
            context.put(ReActState.TAG, state);
        }

        // 执行图引擎
        FlowContext.SCOPE.with(context, () -> {
            //允许工具代码，可获取 FlowContext
            config.getFlowEngine().eval(graph, context);
        });

        // 获取最终答案
        String result = state.getFinalAnswer();
        if (config.isEnableLogging()) {
            LogUtil.global().info("Final Answer: " + result);
        }
        return result;
    }

    /// ////////////

    public static Builder builder(ChatModel chatModel) {
        return new Builder(new ReActConfig(chatModel));
    }

    public static Builder builder(ReActConfig config) {
        return new Builder(config);
    }

    public static class Builder {
        private ReActConfig config;

        public Builder(ReActConfig config) {
            this.config = config;
        }

        public Builder addTool(FunctionTool tool) {
            config.addTool(tool);
            return this;
        }

        public Builder addTool(ToolProvider toolProvider) {
            config.addTool(toolProvider);
            return this;
        }

        public Builder enableLogging(boolean val) {
            config.enableLogging(val);
            return this;
        }

        public Builder temperature(float val) {
            config.temperature(val);
            return this;
        }

        public Builder maxIterations(int val) {
            config.maxIterations(val);
            return this;
        }

        public Builder sessionFactory(ChatSessionFactory val) {
            config.sessionFactory(val);
            return this;
        }

        public Builder flowEngine(FlowEngine val) {
            config.flowEngine(val);
            return this;
        }

        public Builder systemPromptProvider(ReActSystemPromptProvider val) {
            config.systemPromptProvider(val);
            return this;
        }

        public ReActAgent build() {
            return new ReActAgent(config);
        }
    }
}