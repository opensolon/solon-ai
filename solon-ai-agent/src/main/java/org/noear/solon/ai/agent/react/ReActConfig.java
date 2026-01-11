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

import java.util.*;
import java.util.function.Consumer;

/**
 * ReAct 智能体配置（默认配置）
 *
 * <p>用于定义智能体运行时的核心行为参数，包括模型引用、工具集、迭代限制、追溯深度及提示词模板。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class ReActConfig {
    /**
     * 智能体唯一标识名
     */
    private String name;
    /**
     * 智能体标题（用于 UI 可视化展示）
     */
    private String title;
    /**
     * 智能体职责描述（用于团队协作及角色识别）
     */
    private String description;
    private AgentProfile profile;
    /**
     * 执行推理的基础大模型
     */
    private final ChatModel chatModel;
    /**
     * 挂载的功能工具集
     */
    private final Map<String, FunctionTool> tools = new LinkedHashMap<>();
    /**
     * 推理阶段的特定 ChatOptions 配置（如温度、TopP 等）
     */
    private Consumer<ChatOptions> chatOptions;
    /**
     * 图结构微调器（支持对执行图链路进行自定义修改）
     */
    private Consumer<GraphSpec> graphAdjuster;
    /**
     * 提示词模板提供者
     */
    private ReActSystemPrompt systemPrompt = ReActSystemPromptCn.getDefault();
    /**
     * 任务完成标识符（模型输出该字符时循环终止）
     */
    private String finishMarker;

    /**
     * 默认选项
     */
    private final ReActOptions defaultOptions = new ReActOptions();

    //----------------------

    /**
     * 核心构造函数
     *
     * @param chatModel 执行推理的模型，不能为空
     */
    public ReActConfig(ChatModel chatModel) {
        Objects.requireNonNull(chatModel, "chatModel");
        this.chatModel = chatModel;
    }

    // --- 配置注入 (Protected) ---

    protected void setName(String name) {
        this.name = name;
    }

    protected void setTitle(String title) {
        this.title = title;
    }

    protected void setDescription(String description) {
        this.description = description;
    }

    protected void setProfile(AgentProfile profile) {
        this.profile = profile;
    }

    protected void setGraphAdjuster(Consumer<GraphSpec> graphAdjuster) {
        this.graphAdjuster = graphAdjuster;
    }

    protected void setFinishMarker(String val) {
        this.finishMarker = val;
    }

    protected void setSystemPrompt(ReActSystemPrompt val) {
        this.systemPrompt = val;
    }

    protected void setChatOptions(Consumer<ChatOptions> chatOptions) {
        this.chatOptions = chatOptions;
    }
    /**
     * 添加单个功能工具
     */
    protected void addTool(FunctionTool... tools) {
        for (FunctionTool tool : tools) {
            this.tools.put(tool.name(), tool);
        }
    }

    /**
     * 批量添加功能工具
     */
    protected void addTool(Collection<FunctionTool> tools) {
        for (FunctionTool tool : tools) {
            addTool(tool);
        }
    }

    /**
     * 通过 ToolProvider 注入工具集
     */
    protected void addTool(ToolProvider toolProvider) {
        addTool(toolProvider.getTools());
    }

    // --- 参数获取 (Public) ---


    public String getName() {
        return name;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public AgentProfile getProfile() {
        if (profile == null) {
            profile = new AgentProfile();
        }
        return profile;
    }

    public ChatModel getChatModel() {
        return chatModel;
    }

    public Consumer<ChatOptions> getChatOptions() {
        return chatOptions;
    }

    public Collection<FunctionTool> getTools() {
        return tools.values();
    }

    public FunctionTool getTool(String name) {
        return tools.get(name);
    }

    public Consumer<GraphSpec> getGraphAdjuster() {
        return graphAdjuster;
    }

    public ReActOptions getDefaultOptions() {
        return defaultOptions;
    }

    /**
     * 获取完成标记
     * <p>若未显式设置，则基于名称生成：[AGENTNAME_FINISH]</p>
     */
    public String getFinishMarker() {
        if (finishMarker == null) {
            finishMarker = "[" + name.toUpperCase() + "_FINISH]";
        }

        return finishMarker;
    }

    /**
     * 获取提示词提供者
     */
    public String getSystemPromptFor(ReActTrace trace, FlowContext context) {
        return systemPrompt.getSystemPromptFor(trace, context);
    }

    public Locale getLocale() {
        return systemPrompt.getLocale();
    }
}