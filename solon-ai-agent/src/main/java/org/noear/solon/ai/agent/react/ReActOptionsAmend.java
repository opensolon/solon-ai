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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Map;

/**
 * ReAct 运行选项修正器（提供链式调用支持）
 *
 * @author noear
 * @since 3.8.1
 */
public class ReActOptionsAmend {
    private static final Logger LOG = LoggerFactory.getLogger(ReActOptionsAmend.class);
    private final ReActOptions options;

    public ReActOptionsAmend(ReActOptions options) {
        this.options = options;
    }

    // --- Setter Methods (Fluent) ---

    /**
     * 批量注入工具调用上下文
     */
    public ReActOptionsAmend toolsContextPut(Map<String, Object> toolsContext) {
        options.putToolsContext(toolsContext);
        return this;
    }

    /**
     * 注入单个工具调用上下文
     */
    public ReActOptionsAmend toolsContextPut(String key, Object value) {
        options.putToolsContext(key, value);
        return this;
    }

    /**
     * 配置容错策略
     *
     * @param maxRetries   最大重试次数
     * @param retryDelayMs 重试延迟（毫秒）
     */
    public ReActOptionsAmend retryConfig(int maxRetries, long retryDelayMs) {
        options.setRetryConfig(maxRetries, retryDelayMs);
        return this;
    }

    /**
     * 设置消息回溯窗口大小（控制短期记忆深度）
     */
    public ReActOptionsAmend historyWindowSize(int historyWindowSize) {
        options.setSessionWindowSize(historyWindowSize);
        return this;
    }

    /**
     * 设置单次任务最大推理步数
     */
    public ReActOptionsAmend maxSteps(int val) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("ReAct maxSteps amended to: {}", val);
        }
        options.setMaxSteps(val);
        return this;
    }

    /**
     * 添加生命周期拦截器
     */
    public ReActOptionsAmend interceptorAdd(ReActInterceptor val) {
        options.addInterceptor(val, 0);
        return this;
    }

    /**
     * 添加生命周期拦截器（带排序优先级）
     */
    public ReActOptionsAmend interceptorAdd(ReActInterceptor val, int index) {
        options.addInterceptor(val, index);
        return this;
    }
}