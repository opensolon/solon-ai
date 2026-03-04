/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.agent.react;

import org.noear.solon.ai.agent.AgentChunk;
import org.noear.solon.ai.chat.ModelOptionsAmend;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.core.util.RankEntity;
import org.noear.solon.lang.NonSerializable;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.FluxSink;

import java.util.*;
import java.util.function.Function;

/**
 * ReAct 智能体运行选项
 * <p>用于动态控制推理过程中的深度、重试策略及拦截行为</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class ReActOptions implements NonSerializable {
    private static final Logger LOG = LoggerFactory.getLogger(ReActOptions.class);

    private transient FluxSink<AgentChunk> streamSink;

    /**
     * 工具调用上下文（透传给 FunctionTool）
     */
    private final ModelOptionsAmend<?, ReActInterceptor> modelOptions = new ModelOptionsAmend<>();

    private String skillInstruction;

    /**
     * 最大推理步数（防止死循环）
     */
    private int maxSteps = 8;

    private int maxStepsLimit = 100;

    private boolean maxStepsExtensible = false;

    /**
     * 最大重试次数
     */
    private int maxRetries = 3;
    /**
     * 重试延迟基础时间（毫秒）
     */
    private long retryDelayMs = 1000L;
    /**
     * 会话回溯窗口大小
     */
    private int sessionWindowSize = 8;
    /**
     * 输出格式约束 (JSON Schema)
     */
    private String outputSchema;

    /**
     * 反馈模式（允许主动寻求外部帮助/反馈）
     */
    private boolean feedbackMode = false;
    private Function<ReActTrace, String> feedbackDescriptionProvider;
    private Function<ReActTrace, String> feedbackReasonDescriptionProvider;
    /**
     * 规划模式（推理前先制定计划）
     */
    private boolean planningMode = false;
    private Function<ReActTrace, String> planningInstructionProvider;


    /**
     * 浅拷贝选项实例
     */
    protected ReActOptions copy() {
        ReActOptions tmp = new ReActOptions();
        tmp.modelOptions.putAll(modelOptions);
        tmp.maxSteps = maxSteps;
        tmp.maxStepsLimit = maxStepsLimit;
        tmp.maxStepsExtensible = maxStepsExtensible;
        tmp.maxRetries = maxRetries;
        tmp.retryDelayMs = retryDelayMs;
        tmp.sessionWindowSize = sessionWindowSize;
        tmp.outputSchema = outputSchema;

        tmp.feedbackMode = feedbackMode;
        tmp.planningMode = planningMode;
        tmp.planningInstructionProvider = planningInstructionProvider;

        //tmp.streamSink = streamSink;

        return tmp;
    }

    protected void setStreamSink(FluxSink<AgentChunk> streamSink) {
        this.streamSink = streamSink;
    }

    public FluxSink<AgentChunk> getStreamSink() {
        return streamSink;
    }


    // --- 配置注入 (Protected) ---


    /**
     * 设置容错策略
     */
    protected void setRetryConfig(int maxRetries, long retryDelayMs) {
        this.maxRetries = Math.max(1, maxRetries);
        this.retryDelayMs = Math.max(500, retryDelayMs);
    }

    /**
     * 设置会话回溯深度
     */
    protected void setSessionWindowSize(int sessionWindowSize) {
        this.sessionWindowSize = Math.max(0, sessionWindowSize);
    }

    public void setMaxSteps(int val) {
        if (val > this.maxStepsLimit) {
            this.maxSteps = this.maxStepsLimit; // 自动对齐到硬限
            LOG.warn("maxSteps ({}) exceeded maxStepsLimit ({}), capped.", val, maxStepsLimit);
        } else {
            this.maxSteps = val;
        }
    }

    public void setMaxStepsLimit(int val) {
        this.maxStepsLimit = val;
    }

    protected void setMaxStepsExtensible(boolean maxStepsExtensible) {
        this.maxStepsExtensible = maxStepsExtensible;
    }

    protected void setOutputSchema(String val) {
        this.outputSchema = val;
    }


    protected void setSkillInstruction(String skillInstruction) {
        this.skillInstruction = skillInstruction;
    }

    protected void setPlanningMode(boolean planningMode) {
        this.planningMode = planningMode;
    }

    protected void setPlanningInstructionProvider(Function<ReActTrace, String> provider) {
        this.planningInstructionProvider = provider;
    }

    protected void setFeedbackMode(boolean feedbackMode) {
        this.feedbackMode = feedbackMode;
    }

    protected void setFeedbackDescriptionProvider(Function<ReActTrace, String> provider) {
        this.feedbackDescriptionProvider = provider;
    }

    protected void setFeedbackReasonDescriptionProvider(Function<ReActTrace, String> provider) {
        this.feedbackReasonDescriptionProvider = provider;
    }

    // --- 参数获取 (Public) ---

    public FunctionTool getTool(String name) {
        return modelOptions.tool(name);
    }

    public Collection<FunctionTool> getTools() {
        return modelOptions.tools();
    }

    public Map<String, Object> getToolContext() {
        return modelOptions.toolContext();
    }

    public ModelOptionsAmend<?, ReActInterceptor> getModelOptions() {
        return modelOptions;
    }

    public String getSkillInstruction() {
        return skillInstruction;
    }

    public Collection<RankEntity<ReActInterceptor>> getInterceptors() {
        return modelOptions.interceptors();
    }

    public int getMaxSteps() {
        return maxSteps;
    }

    public int getMaxStepsLimit() {
        return maxStepsLimit;
    }

    public boolean isMaxStepsExtensible() {
        return maxStepsExtensible;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public long getRetryDelayMs() {
        return retryDelayMs;
    }

    public int getSessionWindowSize() {
        return sessionWindowSize;
    }

    public String getOutputSchema() {
        return outputSchema;
    }

    public boolean isPlanningMode() {
        return planningMode;
    }

    public String getPlanningInstruction(ReActTrace trace) {
        if (planningInstructionProvider != null) {
            return planningInstructionProvider.apply(trace);
        } else {
            return null;
        }
    }


    public boolean isFeedbackMode() {
        return feedbackMode;
    }

    public String getFeedbackDescription(ReActTrace trace) {
        if (feedbackDescriptionProvider == null) {
            return null;
        }

        return feedbackDescriptionProvider.apply(trace);
    }

    public String getFeedbackReasonDescription(ReActTrace trace) {
        if (feedbackReasonDescriptionProvider == null) {
            return null;
        }

        return feedbackReasonDescriptionProvider.apply(trace);
    }
}