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

import org.noear.solon.core.util.RankEntity;
import org.noear.solon.lang.NonSerializable;
import org.noear.solon.lang.Preview;

import java.util.*;

/**
 * ReAct 智能体选项（可动态调整）
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class ReActOptions implements NonSerializable {
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
     * 历史消息窗口大小（从上下文中回溯并注入到当前执行过程的消息条数）
     */
    private int historyWindowSize = 5;


    /**
     * 复制
     */
    protected ReActOptions copy() {
        ReActOptions tmp = new ReActOptions();
        tmp.toolsContext.putAll( toolsContext);
        tmp.interceptors.addAll( interceptors);
        tmp.maxSteps = maxSteps;
        tmp.maxRetries = maxRetries;
        tmp.retryDelayMs = retryDelayMs;
        tmp.historyWindowSize = historyWindowSize;
        return tmp;
    }


    // --- 配置注入 (Protected) ---

    /**
     * 添加工具调用上下文
     */
    protected void putToolsContext(Map<String, Object> toolsContext) {
        this.toolsContext.putAll(toolsContext);
    }

    protected void putToolsContext(String key, Object value) {
        this.toolsContext.put(key, value);
    }

    /**
     * 配置重试策略
     *
     * @param maxRetries   最大重试次数
     * @param retryDelayMs 重试延迟时间（毫秒）
     */
    protected void setRetryConfig(int maxRetries, long retryDelayMs) {
        this.maxRetries = Math.max(1, maxRetries);
        this.retryDelayMs = Math.max(500, retryDelayMs);
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
     * 添加拦截器并指定优先级
     */
    protected void addInterceptor(ReActInterceptor val, int index) {
        this.interceptors.add(new RankEntity<>(val, index));

        if (interceptors.size() > 1) {
            Collections.sort(interceptors);
        }
    }

    // --- 参数获取 (Public) ---


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

    public int getHistoryWindowSize() {
        return historyWindowSize;
    }

    public List<RankEntity<ReActInterceptor>> getInterceptors() {
        return interceptors;
    }
}