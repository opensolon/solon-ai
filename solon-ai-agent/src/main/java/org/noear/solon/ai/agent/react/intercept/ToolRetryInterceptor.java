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
package org.noear.solon.ai.agent.react.intercept;

import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.chat.interceptor.ToolChain;
import org.noear.solon.ai.chat.interceptor.ToolRequest;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工具重试拦截器
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public class ToolRetryInterceptor implements ReActInterceptor {
    private static final Logger LOG = LoggerFactory.getLogger(ToolRetryInterceptor.class);

    /**
     * 模型调用失败后的最大重试次数
     */
    private final int maxRetries;
    /**
     * 重试延迟时间（毫秒）
     */
    private final long retryDelayMs;

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
     * 重试调用封装
     */
    private String callWithRetry(ToolRequest req, ToolChain chain) throws Throwable {
        String toolName = chain.getTool().name();

        for (int i = 0; i < maxRetries; i++) {
            try {
                return chain.doIntercept(req);
            } catch (IllegalArgumentException e) {
                //参数校验异常，喂给模型进行自愈修复
                return "Invalid arguments for [" + toolName + "]: " + e.getMessage();
            } catch (Throwable e) {
                if (i == maxRetries - 1) {
                    LOG.error("Error executing [{}] tool failed after {} retries", toolName, maxRetries, e);
                    // 返回异常信息给模型，模型通常能识别错误并尝试修复参数后重试
                    return "Execution error in tool [" + toolName + "]: " + e.getMessage();
                }

                if (LOG.isDebugEnabled()) {
                    LOG.error("Error executing [{}] tool failed (retry: {}): {}", toolName, i, e.getMessage());
                }

                try {
                    // 退避重试
                    Thread.sleep(retryDelayMs * (i + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during retry", ie);
                }
            }
        }

        throw new RuntimeException("Should not reach here");
    }
}