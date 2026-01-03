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

import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.lang.Preview;

import java.util.*;
import java.util.function.Consumer;

/**
 * ReAct 智能体配置类
 * <p>定义了智能体运行时的核心行为参数，包括模型引用、工具集、迭代限制及提示词模板。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public class ReActConfig {
    /**
     * 智能体唯一标识名称
     */
    private String name;
    /**
     * 智能体职责描述（用于团队协作场景下的角色识别）
     */
    private String description;
    /**
     * 执行推理的基础大语言模型
     */
    private final ChatModel chatModel;
    /**
     * 推理阶段的特定 ChatOptions 配置（如温度、TopP 等）
     */
    private Consumer<ChatOptions> chatOptions;
    /**
     * 挂载的功能工具集，使用 LinkedHashMap 确保工具展示顺序与添加顺序一致
     */
    private final Map<String, FunctionTool> toolMap = new LinkedHashMap<>();
    /**
     * 最大思考步数，超出后强制终止以防陷入逻辑死循环（默认 10 步）
     */
    private int maxSteps = 10;
    /**
     * 模型调用失败后的最大重试次数
     */
    private int maxRetries = 3;
    /**
     * 重试延迟时间（毫秒）
     */
    private long retryDelayMs = 1000L;
    /**
     * 任务完成的标识符，模型输出此字符串后 ReAct 循环将停止
     */
    private String finishMarker;
    /**
     * 生命周期拦截器，用于监控思考（Thought）、行动（Action）和观察（Observation）
     */
    private ReActInterceptor interceptor;
    /**
     * 提示词模板提供者，默认为英文模板
     */
    private ReActPromptProvider promptProvider = ReActPromptProviderEn.getInstance();


    /**
     * 核心构造函数
     *
     * @param chatModel 执行推理的模型，不能为空
     */
    public ReActConfig(ChatModel chatModel) {
        Objects.requireNonNull(chatModel, "chatModel");
        this.chatModel = chatModel;
    }


    // --- Setter / 配置项注入 ---

    public void setName(String name) {
        this.name = name;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * 添加单个功能工具
     */
    public void addTool(FunctionTool tool) {
        this.toolMap.put(tool.name(), tool);
    }

    /**
     * 批量添加功能工具
     */
    public void addTool(Collection<FunctionTool> tools) {
        for (FunctionTool tool : tools) {
            addTool(tool);
        }
    }

    /**
     * 通过 ToolProvider 注入工具集
     */
    public void addTool(ToolProvider toolProvider) {
        addTool(toolProvider.getTools());
    }

    /**
     * 配置重试策略
     *
     * @param maxRetries   至少 1 次
     * @param retryDelayMs 至少 1000ms
     */
    public void setRetryConfig(int maxRetries, long retryDelayMs) {
        this.maxRetries = Math.max(1, maxRetries);
        this.retryDelayMs = Math.max(1000, retryDelayMs);
    }


    public void setFinishMarker(String val) {
        this.finishMarker = val;
    }

    public void setMaxSteps(int val) {
        this.maxSteps = val;
    }

    public void setInterceptor(ReActInterceptor val) {
        this.interceptor = val;
    }

    public void setPromptProvider(ReActPromptProvider val) {
        this.promptProvider = val;
    }

    public void setChatOptions(Consumer<ChatOptions> chatOptions) {
        this.chatOptions = chatOptions;
    }

    // --- Getters / 运行期参数获取 ---


    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Collection<FunctionTool> getTools() {
        return toolMap.values();
    }

    public FunctionTool getTool(String name) {
        return toolMap.get(name);
    }

    public ChatModel getChatModel() {
        return chatModel;
    }

    public Consumer<ChatOptions> getChatOptions() {
        return chatOptions;
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

    public ReActInterceptor getInterceptor() {
        return interceptor;
    }

    /**
     * 获取生成的系统提示词
     *
     * @param trace 当前执行轨迹状态
     */
    public String getSystemPrompt(ReActTrace trace) {
        return promptProvider.getSystemPrompt(trace);
    }

}