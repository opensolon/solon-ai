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
package org.noear.solon.ai.agent.trace;

import org.noear.solon.ai.AiUsage;
import org.noear.solon.lang.Preview;

import java.io.Serializable;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 智能体执行指标统计
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class Metrics implements Serializable {
    private transient final ReentrantLock LOCK = new ReentrantLock();

    /**
     * 总耗时（毫秒）
     */
    private volatile long totalDuration;

    /**
     * 提示语（输入）消耗令牌数，对应 AiUsage.promptTokens
     */
    private volatile long promptTokens;
    /**
     * 完成（输出）消耗令牌数，对应 AiUsage.completionTokens
     */
    private volatile long completionTokens;
    /**
     * 总消耗令牌数（通常为输入 + 输出），对应 AiUsage.totalTokens
     */
    private volatile long totalTokens;
    /**
     * 缓存创建输入令牌数（Claude Prompt Caching 首次写入），对应 AiUsage.cacheCreationInputTokens
     */
    private volatile long cacheCreationInputTokens;
    /**
     * 缓存读取输入令牌数（Prompt Caching 命中），对应 AiUsage.cacheReadInputTokens
     */
    private volatile long cacheReadInputTokens;


    // --- Setter & Accumulator Methods ---

    public void setTotalDuration(long totalDuration) {
        this.totalDuration = totalDuration;
    }

    public void setPromptTokens(long promptTokens) {
        this.promptTokens = promptTokens;
    }

    public void setCompletionTokens(long completionTokens) {
        this.completionTokens = completionTokens;
    }

    public void setTotalTokens(long totalTokens) {
        this.totalTokens = totalTokens;
    }

    public void setCacheCreationInputTokens(long cacheCreationInputTokens) {
        this.cacheCreationInputTokens = cacheCreationInputTokens;
    }

    public void setCacheReadInputTokens(long cacheReadInputTokens) {
        this.cacheReadInputTokens = cacheReadInputTokens;
    }

    public void reset() {
        LOCK.lock();

        try {
            this.totalDuration = 0;
            this.promptTokens = 0;
            this.completionTokens = 0;
            this.totalTokens = 0;
            this.cacheCreationInputTokens = 0;
            this.cacheReadInputTokens = 0;
        } finally {
            LOCK.unlock();
        }
    }

    public void addMetrics(Metrics metrics) {
        LOCK.lock();

        try {
            this.promptTokens += metrics.promptTokens;
            this.completionTokens += metrics.completionTokens;
            this.totalTokens += metrics.totalTokens;
            this.cacheCreationInputTokens += metrics.cacheCreationInputTokens;
            this.cacheReadInputTokens += metrics.cacheReadInputTokens;
        } finally {
            LOCK.unlock();
        }
    }

    public void addUsage(AiUsage usage) {
        LOCK.lock();

        try {
            this.promptTokens += usage.promptTokens();
            this.completionTokens += usage.completionTokens();
            this.totalTokens += usage.totalTokens();
            this.cacheCreationInputTokens += usage.cacheCreationInputTokens();
            this.cacheReadInputTokens += usage.cacheReadInputTokens();
        } finally {
            LOCK.unlock();
        }
    }


    // --- Getter Methods ---

    public long getTotalDuration() {
        return totalDuration;
    }

    public long getPromptTokens() {
        return promptTokens;
    }

    public long getCompletionTokens() {
        return completionTokens;
    }

    public long getTotalTokens() {
        return totalTokens;
    }

    public long getCacheCreationInputTokens() {
        return cacheCreationInputTokens;
    }

    public long getCacheReadInputTokens() {
        return cacheReadInputTokens;
    }

    /**
     * 获取缓存命中率（0-100 整数百分比），即缓存读取输入令牌数占提示语输入令牌数的比例
     */
    public int getCacheRate() {
        if (promptTokens <= 0)
            return 0;

        double rate = (double) cacheReadInputTokens * 100.0D / promptTokens;
        return (int) Math.min(100, Math.floor(rate));
    }

    @Override
    public String toString() {
        return "Metrics{" +
                "totalDuration=" + totalDuration +
                ", promptTokens=" + promptTokens +
                ", completionTokens=" + completionTokens +
                ", totalTokens=" + totalTokens +
                ", cacheCreationInputTokens=" + cacheCreationInputTokens +
                ", cacheReadInputTokens=" + cacheReadInputTokens +
                '}';
    }
}
