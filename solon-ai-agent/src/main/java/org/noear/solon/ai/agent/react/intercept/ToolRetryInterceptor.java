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
package org.noear.solon.ai.agent.react.intercept;

import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.chat.interceptor.ToolChain;
import org.noear.solon.ai.chat.interceptor.ToolRequest;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工具执行重试拦截器 (Tool Resilience)
 * <p>为工具调用提供韧性支持：包含物理层面的指数退避重试，以及逻辑层面的参数错误自愈反馈。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class ToolRetryInterceptor implements ReActInterceptor {
    private static final Logger LOG = LoggerFactory.getLogger(ToolRetryInterceptor.class);

    private final int maxRetries;
    private final long retryDelayMs;

    /**
     * @param maxRetries   最大尝试次数 (首次执行 + 重试次数)
     * @param retryDelayMs 基础延迟（毫秒），实际延迟采用线性或指数增长
     */
    public ToolRetryInterceptor(int maxRetries, long retryDelayMs) {
        this.maxRetries = Math.max(1, maxRetries);
        this.retryDelayMs = Math.max(1000L, retryDelayMs);
    }

    public ToolRetryInterceptor() {
        this(3, 1000L);
    }

    @Override
    public String interceptTool(ToolRequest req, ToolChain chain) throws Throwable {
        return callWithRetry(req, chain);
    }

    /**
     * 执行带重试与自愈逻辑的调用
     */
    private String callWithRetry(ToolRequest req, ToolChain chain) throws Throwable {
        String toolName = chain.getTool().name();

        for (int i = 0; i < maxRetries; i++) {
            try {
                return chain.doIntercept(req);

            } catch (IllegalArgumentException e) {
                // 1. 参数自愈：直接将错误喂给模型，促使 LLM 修正 Action 参数并重试
                if (LOG.isWarnEnabled()) {
                    LOG.warn("Tool [{}] invalid arguments: {}", toolName, e.getMessage());
                }
                return "Invalid arguments for [" + toolName + "]: " + e.getMessage();

            } catch (Throwable e) {
                // 2. 环境异常处理：执行退避重试
                if (i == maxRetries - 1) {
                    LOG.error("Tool [{}] execution failed after {} attempts", toolName, maxRetries, e);
                    // 最终失败作为 Observation 返回，交由模型判断下一步（报错或换工具）
                    return "Execution error in tool [" + toolName + "]: " + e.getMessage();
                }

                if (LOG.isDebugEnabled()) {
                    LOG.debug("Tool [{}] failed, retrying ({}/{}): {}", toolName, i + 1, maxRetries, e.getMessage());
                }

                try {
                    // 指数退避：i=0(1s), i=1(2s), i=2(3s)...
                    Thread.sleep(retryDelayMs * (i + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Tool retry interrupted", ie);
                }
            }
        }

        throw new RuntimeException("Unexpected state in ToolRetryInterceptor");
    }
}