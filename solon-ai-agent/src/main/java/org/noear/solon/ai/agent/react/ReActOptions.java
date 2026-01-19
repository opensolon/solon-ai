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

import org.noear.solon.ai.chat.skill.Skill;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolProvider;
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

    /** 挂载的可调用工具集 */
    private final Map<String, FunctionTool> tools = new LinkedHashMap<>();
    /** 工具调用上下文（透传给 FunctionTool） */
    private final Map<String, Object> toolsContext = new LinkedHashMap<>();
    private final List<RankEntity<Skill>> skills = new ArrayList<>();
    private String skillInstruction;

    /** 生命周期拦截器（监控 Thought, Action, Observation） */
    private final List<RankEntity<ReActInterceptor>> interceptors = new ArrayList<>();
    /** 最大推理步数（防止死循环） */
    private int maxSteps = 10;
    /** 最大重试次数 */
    private int maxRetries = 3;
    /** 重试延迟基础时间（毫秒） */
    private long retryDelayMs = 1000L;
    /** 会话回溯窗口大小 */
    private int sessionWindowSize = 5;
    /** 输出格式约束 (JSON Schema) */
    private String outputSchema;

    private boolean enablePlanning = false; // 是否启用规划环节
    private Function<ReActTrace, String> planInstructionProvider; // 规划专用指令


    /** 浅拷贝选项实例 */
    protected ReActOptions copy() {
        ReActOptions tmp = new ReActOptions();
        tmp.tools.putAll(tools);
        tmp.toolsContext.putAll(toolsContext);
        tmp.skills.addAll(skills);
        tmp.interceptors.addAll(interceptors);
        tmp.maxSteps = maxSteps;
        tmp.maxRetries = maxRetries;
        tmp.retryDelayMs = retryDelayMs;
        tmp.sessionWindowSize = sessionWindowSize;
        tmp.outputSchema = outputSchema;

        tmp.enablePlanning = enablePlanning;
        tmp.planInstructionProvider = planInstructionProvider;

        return tmp;
    }


    // --- 配置注入 (Protected) ---


    /** 注册工具 */
    protected void addTool(FunctionTool... tools) {
        for (FunctionTool tool : tools) {
            this.tools.put(tool.name(), tool);
        }
    }

    protected void addTool(Collection<FunctionTool> tools) {
        for (FunctionTool tool : tools) addTool(tool);
    }

    protected void addTool(ToolProvider toolProvider) {
        addTool(toolProvider.getTools());
    }

    protected void putToolsContext(Map<String, Object> toolsContext) {
        this.toolsContext.putAll(toolsContext);
    }

    protected void putToolsContext(String key, Object value) {
        this.toolsContext.put(key, value);
    }

    /** 设置容错策略 */
    protected void setRetryConfig(int maxRetries, long retryDelayMs) {
        this.maxRetries = Math.max(1, maxRetries);
        this.retryDelayMs = Math.max(500, retryDelayMs);
    }

    /** 设置短期记忆回溯深度 */
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


    protected void addSkill(Skill skill, int index) {
        this.skills.add(new RankEntity<>(skill, index));
        if (skills.size() > 1) {
            Collections.sort(skills);
        }
    }

    protected void setSkillInstruction(String skillInstruction) {
        this.skillInstruction = skillInstruction;
    }

    /** 添加拦截器并自动重排序 */
    protected void addInterceptor(ReActInterceptor val, int index) {
        this.interceptors.add(new RankEntity<>(val, index));
        if (interceptors.size() > 1) {
            Collections.sort(interceptors);
        }
    }

    protected void setEnablePlanning(boolean enablePlanning) { this.enablePlanning = enablePlanning; }

    protected void setPlanInstructionProvider(Function<ReActTrace, String> provider) {
        this.planInstructionProvider = provider;
    }

    // --- 参数获取 (Public) ---

    public Collection<FunctionTool> getTools() { return tools.values(); }

    public FunctionTool getTool(String name) { return tools.get(name); }

    public Map<String, Object> getToolsContext() {
        return toolsContext;
    }

    public List<RankEntity<Skill>> getSkills() {
        return skills;
    }

    public String getSkillInstruction() {
        return skillInstruction;
    }

    public List<RankEntity<ReActInterceptor>> getInterceptors() {
        return interceptors;
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