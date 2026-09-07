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
     * 总活跃执行耗时（毫秒，不包含任务挂起等待时间）
     */
    private volatile long totalDuration;

    /**
     * 提示语（输入）消耗令牌数，对应 AiUsage.promptTokens
     */
    private volatile long promptTokens;
    /**
     * 思考（思维链/推理）消耗令牌数，对应 AiUsage.thinkTokens
     */
    private volatile long thinkTokens;
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
    /**
     * 5 分钟 TTL 的缓存创建输入令牌数，对应 AiUsage.cacheCreation5mInputTokens
     */
    private volatile long cacheCreation5mInputTokens;
    /**
     * 1 小时 TTL 的缓存创建输入令牌数，对应 AiUsage.cacheCreation1hInputTokens
     */
    private volatile long cacheCreation1hInputTokens;
    /**
     * 服务端内置搜索工具的请求次数，对应 AiUsage.webSearchRequests
     */
    private volatile long webSearchRequests;
    /**
     * 服务端内置抓取工具的请求次数，对应 AiUsage.webFetchRequests
     */
    private volatile long webFetchRequests;


    // --- Setter & Accumulator Methods ---

    public void setTotalDuration(long totalDuration) {
        this.totalDuration = totalDuration;
    }

    /**
     * 累加一次活跃执行片段的耗时
     */
    public void addTotalDuration(long duration) {
        LOCK.lock();
        try {
            this.totalDuration += duration;
        } finally {
            LOCK.unlock();
        }
    }

    public void setPromptTokens(long promptTokens) {
        this.promptTokens = promptTokens;
    }

    public void setThinkTokens(long thinkTokens) {
        this.thinkTokens = thinkTokens;
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

    public void setCacheCreation5mInputTokens(long cacheCreation5mInputTokens) {
        this.cacheCreation5mInputTokens = cacheCreation5mInputTokens;
    }

    public void setCacheCreation1hInputTokens(long cacheCreation1hInputTokens) {
        this.cacheCreation1hInputTokens = cacheCreation1hInputTokens;
    }

    public void setWebSearchRequests(long webSearchRequests) {
        this.webSearchRequests = webSearchRequests;
    }

    public void setWebFetchRequests(long webFetchRequests) {
        this.webFetchRequests = webFetchRequests;
    }

    public void reset() {
        LOCK.lock();

        try {
            this.totalDuration = 0;
            this.promptTokens = 0;
            this.thinkTokens = 0;
            this.completionTokens = 0;
            this.totalTokens = 0;
            this.cacheCreationInputTokens = 0;
            this.cacheReadInputTokens = 0;
            this.cacheCreation5mInputTokens = 0;
            this.cacheCreation1hInputTokens = 0;
            this.webSearchRequests = 0;
            this.webFetchRequests = 0;
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * 汇总其它智能体的用量指标（不汇总耗时，父级耗时按自身活跃执行片段统计）
     */
    public void addMetrics(Metrics metrics) {
        Metrics source = metrics.snapshot();

        LOCK.lock();
        try {
            this.promptTokens += source.promptTokens;
            this.thinkTokens += source.thinkTokens;
            this.completionTokens += source.completionTokens;
            this.totalTokens += source.totalTokens;
            this.cacheCreationInputTokens += source.cacheCreationInputTokens;
            this.cacheReadInputTokens += source.cacheReadInputTokens;
            this.cacheCreation5mInputTokens += source.cacheCreation5mInputTokens;
            this.cacheCreation1hInputTokens += source.cacheCreation1hInputTokens;
            this.webSearchRequests += source.webSearchRequests;
            this.webFetchRequests += source.webFetchRequests;
        } finally {
            LOCK.unlock();
        }
    }

    public void addUsage(AiUsage usage) {
        LOCK.lock();

        try {
            this.promptTokens += usage.promptTokens();
            this.thinkTokens += usage.thinkTokens();
            this.completionTokens += usage.completionTokens();
            this.totalTokens += usage.totalTokens();
            this.cacheCreationInputTokens += usage.cacheCreationInputTokens();
            this.cacheReadInputTokens += usage.cacheReadInputTokens();
            this.cacheCreation5mInputTokens += usage.cacheCreation5mInputTokens();
            this.cacheCreation1hInputTokens += usage.cacheCreation1hInputTokens();
            this.webSearchRequests += usage.webSearchRequests();
            this.webFetchRequests += usage.webFetchRequests();
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * 创建当前指标的一致性快照
     */
    public Metrics snapshot() {
        Metrics snapshot = new Metrics();

        LOCK.lock();
        try {
            snapshot.totalDuration = this.totalDuration;
            snapshot.promptTokens = this.promptTokens;
            snapshot.thinkTokens = this.thinkTokens;
            snapshot.completionTokens = this.completionTokens;
            snapshot.totalTokens = this.totalTokens;
            snapshot.cacheCreationInputTokens = this.cacheCreationInputTokens;
            snapshot.cacheReadInputTokens = this.cacheReadInputTokens;
            snapshot.cacheCreation5mInputTokens = this.cacheCreation5mInputTokens;
            snapshot.cacheCreation1hInputTokens = this.cacheCreation1hInputTokens;
            snapshot.webSearchRequests = this.webSearchRequests;
            snapshot.webFetchRequests = this.webFetchRequests;
        } finally {
            LOCK.unlock();
        }

        return snapshot;
    }

    /**
     * 计算当前指标相对基线快照的增量
     */
    public Metrics deltaSince(Metrics baseline) {
        Metrics current = snapshot();
        Metrics base = baseline.snapshot();
        Metrics delta = new Metrics();
        delta.totalDuration = current.totalDuration - base.totalDuration;
        delta.promptTokens = current.promptTokens - base.promptTokens;
        delta.thinkTokens = current.thinkTokens - base.thinkTokens;
        delta.completionTokens = current.completionTokens - base.completionTokens;
        delta.totalTokens = current.totalTokens - base.totalTokens;
        delta.cacheCreationInputTokens = current.cacheCreationInputTokens - base.cacheCreationInputTokens;
        delta.cacheReadInputTokens = current.cacheReadInputTokens - base.cacheReadInputTokens;
        delta.cacheCreation5mInputTokens = current.cacheCreation5mInputTokens - base.cacheCreation5mInputTokens;
        delta.cacheCreation1hInputTokens = current.cacheCreation1hInputTokens - base.cacheCreation1hInputTokens;
        delta.webSearchRequests = current.webSearchRequests - base.webSearchRequests;
        delta.webFetchRequests = current.webFetchRequests - base.webFetchRequests;
        return delta;
    }


    // --- Getter Methods ---

    public long getTotalDuration() {
        return totalDuration;
    }

    public long getPromptTokens() {
        return promptTokens;
    }

    public long getThinkTokens() {
        return thinkTokens;
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

    public long getCacheCreation5mInputTokens() {
        return cacheCreation5mInputTokens;
    }

    public long getCacheCreation1hInputTokens() {
        return cacheCreation1hInputTokens;
    }

    public long getWebSearchRequests() {
        return webSearchRequests;
    }

    public long getWebFetchRequests() {
        return webFetchRequests;
    }

    /**
     * 获取缓存命中率（0-100 百分比，保留2位小数），即缓存读取输入令牌数占提示语输入令牌数的比例
     */
    public double getCacheRate() {
        long promptTokens;
        long cacheReadInputTokens;

        LOCK.lock();
        try {
            promptTokens = this.promptTokens;
            cacheReadInputTokens = this.cacheReadInputTokens;
        } finally {
            LOCK.unlock();
        }

        if (promptTokens <= 0)
            return 0.0D;

        double rate = (double) cacheReadInputTokens * 100.0D / promptTokens;
        rate = Math.max(0.0D, Math.min(100.0D, rate));
        return Math.round(rate * 100.0D) / 100.0D;
    }

    @Override
    public String toString() {
        return "Metrics{" +
                "totalDuration=" + totalDuration +
                ", promptTokens=" + promptTokens +
                ", thinkTokens=" + thinkTokens +
                ", completionTokens=" + completionTokens +
                ", totalTokens=" + totalTokens +
                ", cacheCreationInputTokens=" + cacheCreationInputTokens +
                ", cacheReadInputTokens=" + cacheReadInputTokens +
                ", cacheCreation5mInputTokens=" + cacheCreation5mInputTokens +
                ", cacheCreation1hInputTokens=" + cacheCreation1hInputTokens +
                ", webSearchRequests=" + webSearchRequests +
                ", webFetchRequests=" + webFetchRequests +
                '}';
    }
}
