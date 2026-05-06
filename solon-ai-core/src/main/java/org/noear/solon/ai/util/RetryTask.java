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
package org.noear.solon.ai.util;

import org.noear.solon.util.CallableTx;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 重试任务
 *
 * @author noear
 * @since 3.10.5
 */
public class RetryTask {
    public static final int DEFAULT_MAX_RETRIES = 3;
    public static final long DEFAULT_INITIAL_DELAY_MS = 1000L;
    public static final long DEFAULT_MAX_DELAY_MS = 30 * 1000L; // 默认最大等待 30 秒

    public interface RetryListener {
        void onRetry(int attempt, Throwable e);
    }

    //最大重试次数
    private int maxRetries = DEFAULT_MAX_RETRIES;
    //初始延迟毫秒数
    private long initialDelayMs = DEFAULT_INITIAL_DELAY_MS;
    //最大延迟毫秒数（Cap）
    private long maxDelayMs = DEFAULT_MAX_DELAY_MS;
    //重试监听
    private RetryListener retryListener;

    public RetryTask maxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
        return this;
    }

    public RetryTask initialDelayMs(long initialDelayMs) {
        this.initialDelayMs = initialDelayMs;
        return this;
    }

    public RetryTask maxDelayMs(long maxDelayMs) {
        this.maxDelayMs = maxDelayMs;
        return this;
    }


    public RetryTask onRetry(RetryListener retryListener) {
        this.retryListener = retryListener;
        return this;
    }

    /**
     * 带指数退避和随机抖动的重试实现
     *
     * @param callable 业务回调
     */
    public <T, X extends Throwable> T callWithRetry(CallableTx<T, Throwable> callable) throws X, InterruptedException {
        Throwable lastException = null;

        for (int i = 0; i < maxRetries; i++) {
            if (Thread.interrupted()) {
                break;
            }

            try {
                return callable.call();
            } catch (Throwable e) {
                lastException = e;

                if (e instanceof InterruptedException) {
                    throw (InterruptedException) e;
                }

                if (e.getCause() instanceof InterruptedException) {
                    throw (InterruptedException) e.getCause();
                }

                // 如果还没到最后一次，执行等待逻辑
                if (i < (maxRetries - 1)) {
                    if (retryListener != null) {
                        retryListener.onRetry(i + 1, e);
                    }

                    // 1. 计算基础指数延迟：initialDelay * 2^i
                    long exponentialDelay = initialDelayMs * (1L << i);

                    // 2. 限制在最大延迟范围内
                    long cappedDelay = Math.min(exponentialDelay, maxDelayMs);

                    // 3. 引入随机抖动 (Full Jitter 策略)
                    // 在 0 到 cappedDelay 之间取随机值，能有效平滑瞬时压力
                    long actualDelay = ThreadLocalRandom.current().nextLong(0, cappedDelay + 1);

                    try {
                        if (actualDelay > 0) {
                            Thread.sleep(actualDelay);
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        // 统一异常抛出逻辑
        if (lastException == null) {
            // 线程被中断退出，没有业务异常（Thread.interrupted() 为 true 导致 break，未进入 catch）
            throw new InterruptedException("Retry aborted: thread interrupted before any attempt");
        }

        if (lastException instanceof RuntimeException) {
            throw (RuntimeException) lastException;
        } else if (lastException instanceof Error) {
            throw (Error) lastException;
        } else {
            throw (X) lastException;
        }
    }
}