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
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工具执行重试拦截器 (Tool Resilience)
 * <p>为工具调用提供韧性支持：包含物理层面的线性退避重试，以及逻辑层面的参数错误自愈反馈。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class ToolRetryInterceptor implements ReActInterceptor {
    private static final Logger LOG = LoggerFactory.getLogger(ToolRetryInterceptor.class);

    private final int maxRetries;
    private final long retryDelayMs;

    public ToolRetryInterceptor(int maxRetries, long retryDelayMs) {
        this.maxRetries = Math.max(1, maxRetries);
        this.retryDelayMs = Math.max(500L, retryDelayMs);
    }

    public ToolRetryInterceptor() {
        this(3, 1000L);
    }

    @Override
    public ToolResult interceptTool(ToolRequest req, ToolChain chain) throws Throwable {
        String toolName = chain.getTool().name();

        for (int i = 0; i < maxRetries; i++) {
            try {
                return chain.doIntercept(req);

            } catch (IllegalArgumentException e) {
                // 1. 参数自愈：逻辑错误。直接回馈给模型，提示其修正参数。
                // 这种错误重试物理链路没有意义，直接返回 Observation
                if (LOG.isWarnEnabled()) {
                    LOG.warn("Tool [{}] invalid arguments: {}", toolName, e.getMessage());
                }
                return new ToolResult("Invalid arguments for tool [" + toolName + "]: " + e.getMessage() + ". Please fix the arguments and try again.");

            } catch (Throwable e) {
                // 2. 物理异常：执行退避重试
                if (i == maxRetries - 1) {
                    LOG.error("Tool [{}] failed after {} attempts", toolName, maxRetries, e);
                    return new ToolResult("Execution error in tool [" + toolName + "]: " + e.getMessage());
                }

                if (LOG.isDebugEnabled()) {
                    LOG.debug("Tool [{}] failed, retrying ({}/{}): {}", toolName, i + 1, maxRetries, e.getMessage());
                }

                try {
                    // 线性退避，与 ReasonTask 保持一致
                    Thread.sleep(retryDelayMs * (i + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ie;
                }
            }
        }

        return new ToolResult("Tool [" + toolName + "] failed with unknown error.");
    }
}