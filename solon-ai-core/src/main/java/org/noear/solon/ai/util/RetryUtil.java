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

/**
 * 智能体辅助工具类
 *
 * @author noear
 * @since 3.9.0
 */
public class RetryUtil {
    public static <T, X extends Throwable> T callWithRetry(CallableTx<T, Throwable> callable) throws X {
        return callWithRetry(RetryTask.DEFAULT_MAX_RETRIES, RetryTask.DEFAULT_INITIAL_DELAY_MS, RetryTask.DEFAULT_MAX_DELAY_MS, callable);
    }

    public static <T, X extends Throwable> T callWithRetry(int maxRetries, CallableTx<T, Throwable> callable) throws X {
        return callWithRetry(maxRetries, RetryTask.DEFAULT_INITIAL_DELAY_MS, RetryTask.DEFAULT_MAX_DELAY_MS, callable);
    }

    /**
     * 带指数退避和随机抖动的重试实现
     *
     * @param maxRetries     最大重试次数
     * @param initialDelayMs 初始延迟毫秒数
     * @param maxDelayMs     最大延迟毫秒数（Cap）
     * @param callable       业务回调
     */
    public static <T, X extends Throwable> T callWithRetry(int maxRetries, long initialDelayMs, long maxDelayMs, CallableTx<T, Throwable> callable) throws X {
        return new RetryTask()
                .maxRetries(maxRetries)
                .initialDelayMs(initialDelayMs)
                .maxDelayMs(maxDelayMs)
                .callWithRetry(callable);
    }
}