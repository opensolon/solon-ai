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
package org.noear.solon.ai.agent.react;

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.FlowEngine;
import org.noear.solon.flow.Graph;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 自省反思自能体
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public class ReActAgent implements Agent {
    private static final Logger LOG = LoggerFactory.getLogger(ReActAgent.class);

    private final String name;
    private final ReActConfig config;
    private final Graph graph;
    private final FlowEngine flowEngine;
    private final String traceKey;

    public ReActAgent(ReActConfig config) {
        this.name = config.getName() == null ? "react_agent" : config.getName();
        this.config = config;
        this.flowEngine = FlowEngine.newInstance();
        this.graph = initGraph();
        this.traceKey = "__" + name;

        //附加流拦截器
        if (config.getInterceptor() != null) {
            flowEngine.addInterceptor(config.getInterceptor());
        }
    }

    /**
     * 初始化图结构。定义节点跳转逻辑：
     */
    private Graph initGraph() {
        return Graph.create(this.name, spec -> {
            spec.addStart("start").linkAdd(ReActTrace.ROUTE_REASON);

            spec.addExclusive(ReActTrace.ROUTE_REASON)
                    .task(new ReActReasonTask(config))
                    .linkAdd(ReActTrace.ROUTE_ACTION, l -> l.when(ctx -> ReActTrace.ROUTE_ACTION.equals(ctx.<ReActTrace>getAs(traceKey).getRoute())))
                    .linkAdd(ReActTrace.ROUTE_END);

            spec.addActivity(ReActTrace.ROUTE_ACTION)
                    .task(new ReActActionTask(config))
                    .linkAdd(ReActTrace.ROUTE_REASON);

            spec.addEnd(ReActTrace.ROUTE_END);
        });
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String ask(FlowContext context, String prompt) throws Throwable {
        if (config.isEnableLogging()) {
            LOG.info("Starting ReActAgent: {}", prompt);
        }

        ReActTrace tmpTrace = context.getAs(traceKey);
        if (tmpTrace == null || prompt != null) {
            tmpTrace = new ReActTrace(prompt);
            context.put(traceKey, tmpTrace);
            context.lastNode(null);
        }

        final ReActTrace trace = tmpTrace;

        try {
            //采用变量域的思想传递 KEY_CURRENT_TRACE_KEY
            context.with(Agent.KEY_CURRENT_TRACE_KEY, traceKey, () -> {
                flowEngine.eval(graph, trace.getLastNodeId(), context);
            });
        } finally {
            //同步节点状态
            trace.setLastNode(context.lastNode());
        }


        String result = trace.getFinalAnswer();
        if (config.isEnableLogging()) {
            LOG.info("Final Answer: {}", result);
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

        public Builder nameAs(String val) {
            config.name(val);
            return this;
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

        public Builder maxSteps(int val) {
            config.maxSteps(val);
            return this;
        }

        public Builder maxTokens(int val) {
            config.maxTokens(val);
            return this;
        }

        public Builder promptProvider(ReActPromptProvider val) {
            config.promptProvider(val);
            return this;
        }

        public Builder interceptor(ReActInterceptor val) {
            config.interceptor(val);
            return this;
        }

        public ReActAgent build() {
            return new ReActAgent(config);
        }
    }
}