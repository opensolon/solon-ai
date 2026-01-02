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
import org.noear.solon.ai.agent.react.task.ActionTask;
import org.noear.solon.ai.agent.react.task.ReasonTask;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.FlowEngine;
import org.noear.solon.flow.Graph;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;

/**
 * 自省反思智能体
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public class ReActAgent implements Agent {
    private static final Logger LOG = LoggerFactory.getLogger(ReActAgent.class);

    private final String name;
    private final String description;
    private final ReActConfig config;
    private final Graph graph;
    private final FlowEngine flowEngine;
    private final String traceKey;

    public ReActAgent(ReActConfig config) {
        this.name = config.getName();
        this.description = config.getDescription();
        this.config = config;
        this.flowEngine = FlowEngine.newInstance();
        this.traceKey = "__" + name;

        //附加流拦截器
        if (config.getInterceptor() != null) {
            flowEngine.addInterceptor(config.getInterceptor());
        }

        //构建计算图
        this.graph = Graph.create(this.name, spec -> {
            spec.addStart(Agent.ID_START).linkAdd(Agent.ID_REASON);

            spec.addExclusive(Agent.ID_REASON)
                    .task(new ReasonTask(config))
                    .linkAdd(Agent.ID_ACTION, l -> l.when(ctx ->
                            Agent.ID_ACTION.equals(ctx.<ReActTrace>getAs(traceKey).getRoute())))
                    .linkAdd(Agent.ID_END);

            spec.addActivity(Agent.ID_ACTION)
                    .task(new ActionTask(config))
                    .linkAdd(Agent.ID_REASON);

            spec.addEnd(Agent.ID_END);
        });
    }

    /**
     * 获取图
     *
     */
    public Graph getGraph() {
        return graph;
    }

    /**
     * 获取跟踪实例
     *
     */
    public @Nullable ReActTrace getTrace(FlowContext context) {
        return context.getAs("__" + name);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public String call(FlowContext context, Prompt prompt) throws Throwable {
        if (LOG.isDebugEnabled()) {
            LOG.debug("ReActAgent [{}] starting: {}", this.name, prompt);
        }

        ReActTrace tmpTrace = context.getAs(traceKey);

        if (tmpTrace == null) {
            tmpTrace = new ReActTrace(config, prompt);
            context.put(traceKey, tmpTrace);
        } else {
            tmpTrace.setConfig(config);
        }

        if(prompt != null){
            context.lastNode(null);

            tmpTrace.setPrompt(prompt);
            tmpTrace.setLastNode(null);
        }

        final ReActTrace trace = tmpTrace;
        long startTime = System.currentTimeMillis();

        try {
            //采用变量域的思想传递 KEY_CURRENT_TRACE_KEY
            context.with(Agent.KEY_CURRENT_TRACE_KEY, traceKey, () -> {
                flowEngine.eval(graph, trace.getLastNodeId(), context);
            });
        } finally {
            //同步节点状态
            trace.setLastNode(context.lastNode());

            long duration = System.currentTimeMillis() - startTime;
            trace.getMetrics().setTotalDuration(duration);
            trace.getMetrics().setStepCount(trace.getStepCount());
            trace.getMetrics().setToolCallCount(trace.getToolCallCount());

            if (LOG.isDebugEnabled()) {
                LOG.debug("ReActAgent [{}] completed in {}ms, {} steps, {} tool calls",
                        this.name, duration, trace.getStepCount(), trace.getMetrics().getToolCallCount());
            }
        }

        String result = trace.getFinalAnswer();
        if (LOG.isDebugEnabled()) {
            LOG.debug("ReActAgent [{}] final Answer: {}", this.name, result);
        }

        if(config.getInterceptor() != null){
            config.getInterceptor().onCallEnd(context, prompt);
        }

        return result;
    }

    /// ////////////

    public static Builder builder(ChatModel chatModel) {
        return new Builder(chatModel);
    }

    public static class Builder {
        private ReActConfig config;

        public Builder(ChatModel chatModel) {
            this.config = new ReActConfig(chatModel);
        }

        /**
         * 智能体名字
         */
        public Builder name(String val) {
            config.setName(val);
            return this;
        }

        /**
         * 智能体描述（团队协作时需要）
         */
        public Builder description(String val) {
            config.setDescription(val);
            return this;
        }

        /**
         * 添加工具
         */
        public Builder addTool(FunctionTool tool) {
            config.addTool(tool);
            return this;
        }

        /**
         * 添加工具
         */
        public Builder addTool(List<FunctionTool> tools) {
            config.addTool(tools);
            return this;
        }

        /**
         * 添加工具
         */
        public Builder addTool(ToolProvider toolProvider) {
            config.addTool(toolProvider);
            return this;
        }

        /**
         * 温度
         */
        public Builder temperature(float val) {
            config.setTemperature(val);
            return this;
        }

        /**
         * 重试配置
         *
         * @param maxRetries   最大重试次数
         * @param retryDelayMs 重试延迟时间
         *
         */
        public Builder retryConfig(int maxRetries, long retryDelayMs) {
            config.setRetryConfig(maxRetries, retryDelayMs);
            return this;
        }

        /**
         * 最大步数
         */
        public Builder maxSteps(int val) {
            config.setMaxSteps(val);
            return this;
        }

        /**
         * 完成标记
         */
        public Builder finishMarker(String val) {
            config.setFinishMarker(val);
            return this;
        }

        /**
         * 最大令牌数
         */
        public Builder maxTokens(int val) {
            config.setMaxTokens(val);
            return this;
        }

        /**
         * 提示语提供者
         */
        public Builder promptProvider(ReActPromptProvider val) {
            config.setPromptProvider(val);
            return this;
        }

        /**
         * 拦截器
         */
        public Builder interceptor(ReActInterceptor val) {
            config.setInterceptor(val);
            return this;
        }

        public ReActAgent build() {
            if (config.getName() == null) {
                config.setName("react_agent");
            }

            if (config.getDescription() == null) {
                config.setDescription(config.getName());
            }

            return new ReActAgent(config);
        }
    }
}