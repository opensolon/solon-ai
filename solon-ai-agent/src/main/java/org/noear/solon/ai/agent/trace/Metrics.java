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

/**
 * 智能体执行指标统计
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class Metrics implements Serializable {
    /**
     * 总耗时（毫秒）
     */
    private long totalDuration;

    private long promptTokens;
    private long completionTokens;
    private long totalTokens;


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

    public void reset() {
        this.totalDuration = 0;
        this.promptTokens = 0;
        this.completionTokens = 0;
        this.totalTokens = 0;
    }

    public void addMetrics(Metrics metrics) {
        this.promptTokens += metrics.promptTokens;
        this.completionTokens += metrics.completionTokens;
        this.totalTokens += metrics.totalTokens;
    }

    public void addUsage(AiUsage usage) {
        this.promptTokens += usage.promptTokens();
        this.completionTokens += usage.completionTokens();
        this.totalTokens += usage.totalTokens();
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

    @Override
    public String toString() {
        return "Metrics{" +
                "totalDuration=" + totalDuration +
                ", promptTokens=" + promptTokens +
                ", completionTokens=" + completionTokens +
                ", totalTokens=" + totalTokens +
                '}';
    }
}