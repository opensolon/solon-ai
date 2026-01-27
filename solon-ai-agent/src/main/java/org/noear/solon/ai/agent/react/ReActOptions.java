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

import org.noear.solon.ai.chat.ModelOptionsAmend;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.core.util.RankEntity;
import org.noear.solon.lang.NonSerializable;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    /** 工具调用上下文（透传给 FunctionTool） */
    private final ModelOptionsAmend<?, ReActInterceptor> modelOptions = new ModelOptionsAmend<>();

    private String skillInstruction;

    /** 最大推理步数（防止死循环） */
    private int maxSteps = 10;
    /** 最大重试次数 */
    private int maxRetries = 3;
    /** 重试延迟基础时间（毫秒） */
    private long retryDelayMs = 1000L;
    /** 会话回溯窗口大小 */
    private int sessionWindowSize = 8;
    /** 输出格式约束 (JSON Schema) */
    private String outputSchema;

    private boolean enableSuspension = true; // 是否启用挂起环节
    private boolean enablePlanning = false; // 是否启用规划环节
    private Function<ReActTrace, String> planInstructionProvider; // 规划专用指令


    /** 浅拷贝选项实例 */
    protected ReActOptions copy() {
        ReActOptions tmp = new ReActOptions();
        tmp.modelOptions.putAll(modelOptions);
        tmp.maxSteps = maxSteps;
        tmp.maxRetries = maxRetries;
        tmp.retryDelayMs = retryDelayMs;
        tmp.sessionWindowSize = sessionWindowSize;
        tmp.outputSchema = outputSchema;

        tmp.enableSuspension = enableSuspension;
        tmp.enablePlanning = enablePlanning;
        tmp.planInstructionProvider = planInstructionProvider;

        return tmp;
    }


    // --- 配置注入 (Protected) ---


    /** 设置容错策略 */
    protected void setRetryConfig(int maxRetries, long retryDelayMs) {
        this.maxRetries = Math.max(1, maxRetries);
        this.retryDelayMs = Math.max(500, retryDelayMs);
    }

    /** 设置会话回溯深度 */
    protected void setSessionWindowSize(int sessionWindowSize) {
        this.sessionWindowSize = Math.max(0, sessionWindowSize);
    }

    protected void setMaxSteps(int val) {
        if (LOG.isDebugEnabled() && val > 20) {
            LOG.debug("High maxSteps ({}) might increase token costs.", val);
        }
        this.maxSteps = val;
    }

    protected void setOutputSchema(String val) { this.outputSchema = val; }


    protected void setSkillInstruction(String skillInstruction) {
        this.skillInstruction = skillInstruction;
    }

    protected void setEnableSuspension(boolean enableSuspension) { this.enableSuspension = enableSuspension; }

    protected void setEnablePlanning(boolean enablePlanning) { this.enablePlanning = enablePlanning; }

    protected void setPlanInstructionProvider(Function<ReActTrace, String> provider) {
        this.planInstructionProvider = provider;
    }

    // --- 参数获取 (Public) ---

    public FunctionTool getTool(String name) { return modelOptions.tool(name); }

    public Collection<FunctionTool> getTools() { return modelOptions.tools(); }

    public Map<String,Object> getToolContext() { return modelOptions.toolContext(); }

    public ModelOptionsAmend<?, ReActInterceptor> getModelOptions() {
        return modelOptions;
    }

    public String getSkillInstruction() {
        return skillInstruction;
    }

    public List<RankEntity<ReActInterceptor>> getInterceptors() {
        return modelOptions.interceptors();
    }

    public int getMaxSteps() {
        return maxSteps;
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

    public String getOutputSchema() { return outputSchema; }

    public boolean isEnableSuspension() {
        return enableSuspension;
    }

    public boolean isEnablePlanning() { return enablePlanning; }

    public String getPlanInstruction(ReActTrace trace) {
        if (planInstructionProvider != null) {
            return planInstructionProvider.apply(trace);
        }

        // 默认规划指令
        if (Locale.CHINESE.getLanguage().equals(trace.getConfig().getLocale().getLanguage())) {
            return "请根据用户目标，将其拆解为 3-5 个逻辑清晰的待办步骤（Plans）。\n" +
                    "输出要求：每行一个步骤，以数字开头。不要输出任何多余的解释。";
        } else {
            return "Please break down the user's goal into 3-5 logical steps (Plans).\n" +
                    "Requirements: One step per line, starting with a number. Do not output any extra explanation.";
        }
    }
}