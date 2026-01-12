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

import java.util.Map;

/**
 *
 * @author noear
 * @since 3.8.1
 */
public class ReActOptionsAmend {
    private final ReActOptions options;

    public ReActOptionsAmend(ReActOptions options) {
        this.options = options;
    }


    /**
     * 添加工具调用上下文
     */
    public ReActOptionsAmend toolsContextPut(Map<String, Object> toolsContext) {
        options.putToolsContext(toolsContext);
        return this;
    }

    public ReActOptionsAmend toolsContextPut(String key, Object value) {
        options.putToolsContext(key, value);
        return this;
    }

    /**
     * 配置重试策略
     *
     * @param maxRetries   最大重试次数
     * @param retryDelayMs 重试延迟时间（毫秒）
     */
    public ReActOptionsAmend retryConfig(int maxRetries, long retryDelayMs) {
        options.setRetryConfig(maxRetries, retryDelayMs);
        return this;
    }

    /**
     * 设置历史消息窗口大小
     *
     * @param historyWindowSize 回溯的消息条数（建议设置为奇数以保持对话轮次完整）
     */
    public ReActOptionsAmend historyWindowSize(int historyWindowSize) {
        options.setHistoryWindowSize(historyWindowSize);
        return this;
    }

    public ReActOptionsAmend maxSteps(int val) {
        options.setMaxSteps(val);
        return this;
    }

    /**
     * 添加拦截器
     */
    public ReActOptionsAmend interceptorAdd(ReActInterceptor val) {
        options.addInterceptor(val, 0);
        return this;
    }

    /**
     * 添加拦截器并指定优先级
     */
    public ReActOptionsAmend interceptorAdd(ReActInterceptor val, int index) {
        options.addInterceptor(val, index);
        return this;
    }
}