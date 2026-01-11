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

import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.core.util.RankEntity;
import org.noear.solon.lang.NonSerializable;
import org.noear.solon.lang.Preview;

import java.util.*;

/**
 * ReAct 智能体选项（动态调整）
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class ReActOptions implements NonSerializable {
    /**
     * 挂载的功能工具集
     */
    private final Map<String, FunctionTool> tools = new LinkedHashMap<>();
    /**
     * 工具调用上下文
     */
    private final Map<String, Object> toolsContext = new LinkedHashMap<>();
    /**
     * 生命周期拦截器（监控 Thought, Action, Observation 等状态变化）
     */
    private final List<RankEntity<ReActInterceptor>> interceptors = new ArrayList<>();
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


    // --- 配置注入 (Protected) ---

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

    /**
     * 添加工具调用上下文
     */
    protected void addToolsContext(Map<String, Object> toolsContext) {
        this.toolsContext.putAll(toolsContext);
    }

    /**
     * 配置重试策略
     *
     * @param maxRetries   最大重试次数
     * @param retryDelayMs 重试延迟时间（毫秒）
     */
    protected void setRetryConfig(int maxRetries, long retryDelayMs) {
        this.maxRetries = Math.max(1, maxRetries);
        this.retryDelayMs = Math.max(1000, retryDelayMs);
    }

    protected void setOutputKey(String val) {
        this.outputKey = val;
    }

    protected void setOutputSchema(String val) {
        this.outputSchema = val;
    }

    /**
     * 设置历史消息窗口大小
     *
     * @param historyWindowSize 回溯的消息条数（建议设置为奇数以保持对话轮次完整）
     */
    protected void setHistoryWindowSize(int historyWindowSize) {
        this.historyWindowSize = Math.max(0, historyWindowSize);
    }

    protected void setMaxSteps(int val) {
        this.maxSteps = val;
    }

    /**
     * 添加拦截器
     */
    protected void addInterceptor(ReActInterceptor val) {
        addInterceptor(val, 0);
    }

    /**
     * 添加拦截器并指定优先级
     */
    protected void addInterceptor(ReActInterceptor val, int index) {
        this.interceptors.add(new RankEntity<>(val, index));

        if (interceptors.size() > 1) {
            Collections.sort(interceptors);
        }
    }

    // --- 参数获取 (Public) ---


    public Collection<FunctionTool> getTools() {
        return tools.values();
    }

    public FunctionTool getTool(String name) {
        return tools.get(name);
    }

    public Map<String, Object> getToolsContext() {
        return toolsContext;
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

    public String getOutputKey() {
        return outputKey;
    }

    public String getOutputSchema() {
        return outputSchema;
    }

    public int getHistoryWindowSize() {
        return historyWindowSize;
    }

    public List<RankEntity<ReActInterceptor>> getInterceptors() {
        return interceptors;
    }
}