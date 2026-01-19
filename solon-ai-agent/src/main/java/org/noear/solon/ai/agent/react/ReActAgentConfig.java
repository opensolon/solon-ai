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

import org.noear.solon.ai.agent.AgentProfile;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.GraphSpec;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * ReAct 智能体配置
 * * <p>定义 ReAct (Reasoning + Acting) 模式的核心参数，控制推理迭代与工具执行行为</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class ReActAgentConfig {
    private static final Logger LOG = LoggerFactory.getLogger(ReActAgentConfig.class);

    /** 智能体唯一标识名 */
    private String name = "react_agent";
    /** 链路追踪 Key (用于在 FlowContext 中存储 Trace 状态) */
    private volatile String traceKey;
    /** 智能体标题 */
    private String title;
    /** 智能体职责描述（用于模型识别角色任务） */
    private String description;
    /** 智能体画像 */
    private AgentProfile profile;
    /** 执行推理的基础模型 */
    private final ChatModel chatModel;
    /** 模型推理选项（温度、TopP 等） */
    private Consumer<ChatOptions> chatOptions;
    /** 挂载的可调用工具集 */
    private final Map<String, FunctionTool> tools = new LinkedHashMap<>();
    /** 计算图微调器（自定义执行链路） */
    private Consumer<GraphSpec> graphAdjuster;
    /** 提示词模板（默认中文） */
    private ReActSystemPrompt systemPrompt = ReActSystemPromptCn.getDefault();
    /** 终止标识符（模型输出此词时停止思考循环） */
    private String finishMarker;
    /** 结果回填 Key */
    private String outputKey;
    /** 输出格式约束 (JSON Schema) */
    private String outputSchema;


    private boolean enablePlanning = false; // 是否启用规划环节
    private Function<ReActTrace, String> planInstructionProvider; // 规划专用指令


    /** 默认运行选项（限流、重试、窗口等） */
    private final ReActOptions defaultOptions = new ReActOptions();

    /**
     * @param chatModel 执行推理的模型，不能为空
     */
    public ReActAgentConfig(ChatModel chatModel) {
        Objects.requireNonNull(chatModel, "chatModel is required");
        this.chatModel = chatModel;
    }

    // --- 配置注入 (Protected) ---

    protected void setName(String name) { this.name = name; }

    protected void setTitle(String title) { this.title = title; }

    protected void setDescription(String description) { this.description = description; }

    protected void setProfile(AgentProfile profile) { this.profile = profile; }

    protected void setGraphAdjuster(Consumer<GraphSpec> graphAdjuster) { this.graphAdjuster = graphAdjuster; }

    protected void setFinishMarker(String val) { this.finishMarker = val; }

    protected void setSystemPrompt(ReActSystemPrompt val) {
        this.systemPrompt = val;

        String role = systemPrompt.getRole();
        if (role != null && description == null) {
            description = role;
        }
    }

    protected void setChatOptions(Consumer<ChatOptions> chatOptions) { this.chatOptions = chatOptions; }

    /** 注册工具 */
    protected void addTool(FunctionTool... tools) {
        for (FunctionTool tool : tools) {
            if (LOG.isDebugEnabled()) LOG.debug("ReActAgent [{}] register tool: {}", name, tool.name());
            this.tools.put(tool.name(), tool);
        }
    }

    protected void addTool(Collection<FunctionTool> tools) {
        for (FunctionTool tool : tools) addTool(tool);
    }

    protected void addTool(ToolProvider toolProvider) {
        addTool(toolProvider.getTools());
    }

    protected void setOutputKey(String val) { this.outputKey = val; }

    protected void setOutputSchema(String val) { this.outputSchema = val; }

    protected void setEnablePlanning(boolean enablePlanning) { this.enablePlanning = enablePlanning; }

    public void setPlanInstructionProvider(Function<ReActTrace, String> provider) {
        this.planInstructionProvider = provider;
    }

    // --- 参数获取 (Public) ---

    public String getName() { return name; }

    public String getTraceKey() {
        if (traceKey == null) {
            traceKey = "__" + this.name;
        }
        return traceKey;
    }

    public String getTitle() { return title; }

    public String getDescription() { return description; }

    public AgentProfile getProfile() {
        if (profile == null) profile = new AgentProfile();
        return profile;
    }

    public ChatModel getChatModel() { return chatModel; }

    public Consumer<ChatOptions> getChatOptions() { return chatOptions; }

    public Collection<FunctionTool> getTools() { return tools.values(); }

    public FunctionTool getTool(String name) { return tools.get(name); }

    public Consumer<GraphSpec> getGraphAdjuster() { return graphAdjuster; }

    public ReActOptions getDefaultOptions() { return defaultOptions; }

    /**
     * 获取完成标记（若未设置，默认生成 [NAME_FINISH]）
     */
    public String getFinishMarker() {
        if (finishMarker == null) {
            finishMarker = "[" + name.toUpperCase() + "_FINISH]";
        }
        return finishMarker;
    }

    /**
     * 根据当前上下文获取动态渲染的系统提示词
     */
    public String getSystemPromptFor(ReActTrace trace, FlowContext context) {
        return systemPrompt.getSystemPromptFor(trace, context);
    }

    public Locale getLocale() { return systemPrompt.getLocale(); }

    public String getOutputKey() { return outputKey; }

    public String getOutputSchema() { return outputSchema; }

    public boolean isEnablePlanning() { return enablePlanning; }

    public String getPlanInstruction(ReActTrace trace) {
        if (planInstructionProvider != null) {
            return planInstructionProvider.apply(trace);
        }

        // 默认规划指令
        if (Locale.CHINESE.getLanguage().equals(getLocale().getLanguage())) {
            return "请根据用户目标，将其拆解为 3-5 个逻辑清晰的待办步骤（Plans）。\n" +
                    "输出要求：每行一个步骤，以数字开头。不要输出任何多余的解释。";
        } else {
            return "Please break down the user's goal into 3-5 logical steps (Plans).\n" +
                    "Requirements: One step per line, starting with a number. Do not output any extra explanation.";
        }
    }
}