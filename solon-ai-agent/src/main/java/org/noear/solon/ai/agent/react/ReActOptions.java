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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * ReAct 智能体运行选项
 * <p>用于动态控制推理过程中的深度、重试策略及拦截行为</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class ReActOptions implements NonSerializable {
    private static final Logger log = LoggerFactory.getLogger(ReActOptions.class);

    /** 工具调用上下文（透传给 FunctionTool） */
    private final Map<String, Object> toolsContext = new LinkedHashMap<>();
    /** 生命周期拦截器（监控 Thought, Action, Observation） */
    private final List<RankEntity<ReActInterceptor>> interceptors = new ArrayList<>();
    /** 最大推理步数（防止死循环） */
    private int maxSteps = 10;
    /** 最大重试次数 */
    private int maxRetries = 3;
    /** 重试延迟基础时间（毫秒） */
    private long retryDelayMs = 1000L;
    /** 历史记忆回溯窗口大小 */
    private int historyWindowSize = 5;


    /** 浅拷贝选项实例 */
    protected ReActOptions copy() {
        ReActOptions tmp = new ReActOptions();
        tmp.toolsContext.putAll(toolsContext);
        tmp.interceptors.addAll(interceptors);
        tmp.maxSteps = maxSteps;
        tmp.maxRetries = maxRetries;
        tmp.retryDelayMs = retryDelayMs;
        tmp.historyWindowSize = historyWindowSize;
        return tmp;
    }


    // --- 配置注入 (Protected) ---

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
    protected void setHistoryWindowSize(int historyWindowSize) {
        this.historyWindowSize = Math.max(0, historyWindowSize);
    }

    protected void setMaxSteps(int val) {
        if (log.isDebugEnabled() && val > 20) {
            log.debug("High maxSteps ({}) might increase token costs.", val);
        }
        this.maxSteps = val;
    }

    /** 添加拦截器并自动重排序 */
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