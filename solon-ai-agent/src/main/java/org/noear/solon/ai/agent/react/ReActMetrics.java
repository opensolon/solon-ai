package org.noear.solon.ai.agent.react;

import java.io.Serializable;

/**
 *
 * @author noear 2026/1/1 created
 *
 */
public class ReActMetrics implements Serializable {
    private long totalDuration;
    private int toolCallCount;
    private int stepCount;
    private long tokenUsage;

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