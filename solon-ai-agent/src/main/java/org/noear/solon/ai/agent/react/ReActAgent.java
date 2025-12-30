package org.noear.solon.ai.agent.react;

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.core.util.LogUtil;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.FlowEngine;
import org.noear.solon.flow.Graph;
import org.noear.solon.flow.intercept.FlowInterceptor;

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

        //附加流拦截器
        for (FlowInterceptor interceptor : config.getFlowInterceptors()) {
            config.getFlowEngine().addInterceptor(interceptor);
        }
    }

    /**
     * 初始化图结构。定义节点跳转逻辑：
     * 1. node_model -> node_tools (当 status 为 call_tool)
     * 2. node_model -> end (当 status 为 finish)
     */
    private Graph initGraph() {
        return Graph.create("react_agent", spec -> {
            spec.addStart("start").linkAdd(ReActRecord.ROUTE_REASON);

            // 模型推理节点：决定是执行工具还是结束
            spec.addExclusive(ReActRecord.ROUTE_REASON)
                    .task(new ReActReasonTask(config))
                    .linkAdd(ReActRecord.ROUTE_ACTION, l -> l.when(ctx -> ReActRecord.ROUTE_ACTION.equals(ctx.<ReActRecord>getAs(ReActRecord.TAG).getRoute())))
                    .linkAdd(ReActRecord.ROUTE_END); // 默认跳转至结束

            // 工具执行节点：执行具体的函数调用
            spec.addActivity(ReActRecord.ROUTE_ACTION)
                    .task(new ReActActionTask(config))
                    .linkAdd(ReActRecord.ROUTE_REASON); // 执行完工具后返回模型节点进行下一轮思考

            spec.addEnd(ReActRecord.ROUTE_END);
        });
    }

    @Override
    public String ask(FlowContext context, String prompt) throws Throwable {
        if (config.isEnableLogging()) {
            LogUtil.global().info("Starting ReActAgent: " + prompt);
        }

        // 初始化流程上下文，携带对话历史与迭代计数
        ReActRecord state = context.getAs(ReActRecord.TAG);
        if (state == null) {
            state = new ReActRecord(prompt);
            context.put(ReActRecord.TAG, state);
        }

        // 运行前清空路由，确保由本次推理决定去向
        state.setRoute("");

        // 执行图引擎
        config.getFlowEngine().eval(graph, context.lastNodeId(), context);

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

        public Builder maxTokens(int val) {
            config.maxTokens(val);
            return this;
        }

        public Builder flowEngine(FlowEngine val) {
            config.flowEngine(val);
            return this;
        }

        public Builder addFlowInterceptor(FlowInterceptor val) {
            config.addFlowInterceptor(val);
            return this;
        }

        public Builder systemPromptProvider(ReActPromptProvider val) {
            config.promptProvider(val);
            return this;
        }

        public ReActAgent build() {
            return new ReActAgent(config);
        }
    }
}