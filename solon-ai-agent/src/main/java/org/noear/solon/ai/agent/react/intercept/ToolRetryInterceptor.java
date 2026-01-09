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
 * 工具执行重试拦截器 (Tool Resilience Interceptor)
 * <p>该拦截器为 ReAct 模式下的工具调用提供韧性支持，具备物理重试与逻辑自愈双重机制。</p>
 *
 * <p><b>处理策略：</b></p>
 * <ul>
 * <li>1. <b>参数校验自愈</b>：捕获 {@link IllegalArgumentException}，将错误反馈给模型，促使模型修正参数并重新推理。</li>
 * <li>2. <b>指数退避重试</b>：针对网络抖动等非确定性异常，执行渐进式延迟重试（Exponential Backoff）。</li>
 * <li>3. <b>异常反馈循环</b>：当重试次数耗尽，将最终异常信息作为 Observation 返回，利用 LLM 的泛化能力处理错误。</li>
 * </ul>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public class ToolRetryInterceptor implements ReActInterceptor {
    private static final Logger LOG = LoggerFactory.getLogger(ToolRetryInterceptor.class);

    /** 最大重试次数（包括首次执行） */
    private final int maxRetries;
    /** 基础重试延迟时间（毫秒） */
    private final long retryDelayMs;

    /**
     * @param maxRetries   最小值为 1
     * @param retryDelayMs 最小值为 1000ms
     */
    public ToolRetryInterceptor(int maxRetries, long retryDelayMs) {
        this.maxRetries = Math.max(1, maxRetries);
        this.retryDelayMs = Math.max(1000L, retryDelayMs);
    }

    /**
     * 默认构造函数（重试 3 次，延迟 1 秒）
     */
    public ToolRetryInterceptor() {
        this(3, 1000L);
    }

    /**
     * 拦截工具执行请求
     */
    @Override
    public String interceptTool(ToolRequest req, ToolChain chain) throws Throwable {
        return callWithRetry(req, chain);
    }

    /**
     * 执行带重试与异常处理的调用逻辑
     */
    private String callWithRetry(ToolRequest req, ToolChain chain) throws Throwable {
        String toolName = chain.getTool().name();

        for (int i = 0; i < maxRetries; i++) {
            try {
                // 执行工具链中的下一个节点（或最终的工具方法）
                return chain.doIntercept(req);

            } catch (IllegalArgumentException e) {
                // 场景 A：参数格式或值非法
                // 这种异常通常由工具方法内部校验触发。直接将错误描述返回给模型，
                // 模型在 ReAct 循环中会看到这个结果，并尝试修正 Arguments 再次发起 Action。
                return "Invalid arguments for [" + toolName + "]: " + e.getMessage();

            } catch (Throwable e) {
                // 场景 B：物理环境异常或第三方服务故障
                if (i == maxRetries - 1) {
                    LOG.error("TeamAgent [{}] tool execution failed after {} retries", toolName, maxRetries, e);
                    // 耗尽重试机会后，将异常信息作为 Observation 反馈给模型。
                    // 这样模型可以决定是报错给用户，还是尝试调用其他替代工具。
                    return "Execution error in tool [" + toolName + "]: " + e.getMessage();
                }

                if (LOG.isDebugEnabled()) {
                    LOG.debug("Tool [{}] execution failed, retrying ({}/{}): {}", toolName, i + 1, maxRetries, e.getMessage());
                }

                try {
                    // 指数退避：每次重试的等待时间逐渐增加，缓解后端服务压力
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