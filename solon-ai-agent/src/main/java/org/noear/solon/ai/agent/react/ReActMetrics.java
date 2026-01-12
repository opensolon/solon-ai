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
package org.noear.solon.ai.agent.react;

import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;

/**
 * ReAct 智能体执行指标统计
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class ReActMetrics implements Serializable {
    private static final Logger log = LoggerFactory.getLogger(ReActMetrics.class);

    /** 总耗时（毫秒） */
    private long totalDuration;

    /** 外部工具调用总次数 */
    private int toolCallCount;

    /** 推理迭代步数 (Reasoning Loops) */
    private int stepCount;

    /** 累计消耗的 Token 总量 */
    private long tokenUsage;


    // --- Setter & Accumulator Methods ---

    public void setTotalDuration(long totalDuration) {
        this.totalDuration = totalDuration;
    }

    public void setToolCallCount(int toolCallCount) {
        this.toolCallCount = toolCallCount;
    }

    public void setStepCount(int stepCount) {
        this.stepCount = stepCount;
    }

    public void setTokenUsage(long tokenUsage) {
        this.tokenUsage = tokenUsage;
    }

    /**
     * 累加 Token 使用量
     */
    public void addTokenUsage(long tokenUsage) {
        this.tokenUsage += tokenUsage;
        if (log.isTraceEnabled()) {
            log.trace("Token usage incremented by {}, total: {}", tokenUsage, this.tokenUsage);
        }
    }


    // --- Getter Methods ---

    public long getTotalDuration() {
        return totalDuration;
    }

    public int getToolCallCount() {
        return toolCallCount;
    }

    public int getStepCount() {
        return stepCount;
    }

    public long getTokenUsage() {
        return tokenUsage;
    }
}