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
        return callWithRetry(3, 1000L, callable);
    }

    public static <T, X extends Throwable> T callWithRetry(int maxRetries, long retryDelayMs, CallableTx<T, Throwable> callable) throws X {
        Throwable lastException = null;
        for (int i = 0; i < maxRetries; i++) { // 注意是 <，确保至少执行一次
            if(Thread.interrupted()){
                break;
            }

            try {
                return callable.call();
            } catch (Throwable e) {
                lastException = e;
                if (i < (maxRetries - 1)) {
                    try {
                        Thread.sleep(retryDelayMs * (i + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
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