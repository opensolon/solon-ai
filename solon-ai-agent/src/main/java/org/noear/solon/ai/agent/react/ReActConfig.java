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

import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.core.util.RankEntity;
import org.noear.solon.flow.GraphSpec;
import org.noear.solon.lang.Preview;

import java.util.*;
import java.util.function.Consumer;

/**
 * ReAct 智能体配置
 *
 * <p>用于定义智能体运行时的核心行为参数，包括模型引用、工具集、迭代限制、追溯深度及提示词模板。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
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
    /**
     * 执行推理的基础大模型
     */
    private final ChatModel chatModel;
    /**
     * 推理阶段的特定 ChatOptions 配置（如温度、TopP 等）
     */
    private Consumer<ChatOptions> chatOptions;
    /**
     * 挂载的功能工具集
     */
    private final Map<String, FunctionTool> toolMap = new LinkedHashMap<>();
    /**
     * 图结构微调器（支持对执行图链路进行自定义修改）
     */
    private Consumer<GraphSpec> graphAdjuster;
    /**
     * 最大思考步数（防止推理死循环，默认 10 步）
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
     * 任务完成标识符（模型输出该字符时循环终止）
     */
    private String finishMarker;

    /**
     * 结果输出 Key
     */
    private String outputKey;

    /**
     * 期望的输出 Schema（例如 JSON Schema 字符串或描述）
     */
    private String outputSchema;

    /**
     * 历史消息窗口大小（从上下文中回溯并注入到当前执行过程的消息条数）
     */
    private int historyWindowSize = 5;

    /**
     * 生命周期拦截器（监控 Thought, Action, Observation 等状态变化）
     */
    private final List<RankEntity<ReActInterceptor>> interceptorList = new ArrayList<>();
    /**
     * 提示词模板提供者
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

    public void setTitle(String title) {
        this.title = title;
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
     * @param maxRetries   最大重试次数
     * @param retryDelayMs 重试延迟时间（毫秒）
     */
    public void setRetryConfig(int maxRetries, long retryDelayMs) {
        this.maxRetries = Math.max(1, maxRetries);
        this.retryDelayMs = Math.max(1000, retryDelayMs);
    }

    public void setGraphAdjuster(Consumer<GraphSpec> graphAdjuster) {
        this.graphAdjuster = graphAdjuster;
    }

    public void setFinishMarker(String val) {
        this.finishMarker = val;
    }

    public void setOutputKey(String val) {
        this.outputKey = val;
    }

    public void setOutputSchema(String val) {
        this.outputSchema = val;
    }

    public String getOutputSchema() {
        return outputSchema;
    }

    /**
     * 设置历史消息窗口大小
     *
     * @param historyWindowSize 回溯的消息条数（建议设置为奇数以保持对话轮次完整）
     */
    public void setHistoryWindowSize(int historyWindowSize) {
        this.historyWindowSize = Math.max(0, historyWindowSize);
    }

    public void setMaxSteps(int val) {
        this.maxSteps = val;
    }

    /**
     * 添加拦截器
     */
    public void addInterceptor(ReActInterceptor val) {
        addInterceptor(val, 0);
    }

    /**
     * 添加拦截器并指定优先级
     */
    public void addInterceptor(ReActInterceptor val, int index) {
        this.interceptorList.add(new RankEntity<>(val, index));

        if (interceptorList.size() > 1) {
            Collections.sort(interceptorList);
        }
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

    public String getTitle() {
        return title;
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

    public Consumer<GraphSpec> getGraphAdjuster() {
        return graphAdjuster;
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

    public String getOutputKey() {
        return outputKey;
    }

    public int getHistoryWindowSize() {
        return historyWindowSize;
    }

    public List<RankEntity<ReActInterceptor>> getInterceptorList() {
        return interceptorList;
    }

    /**
     * 获取提示词提供者
     */
    public ReActPromptProvider getPromptProvider() {
        return promptProvider;
    }

    public Locale getLocale() {
        return promptProvider.getLocale();
    }
}