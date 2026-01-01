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

import java.io.Serializable;

/**
 * ReAct 智能体执行指标
 *
 * @author noear
 * @since 3.8.1
 */
public class ReActMetrics implements Serializable {
    /**
     * 总执行时间（毫秒）
     */
    private long totalDuration;

    /**
     * 工具调用次数
     */
    private int toolCallCount;

    /**
     * 执行步数（思考迭代次数）
     */
    private int stepCount;

    /**
     * 使用的令牌总数
     */
    private long tokenUsage;

    // Setter 方法
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

    // Getter 方法
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