package org.noear.solon.ai.loop.engine;

import org.noear.solon.ai.loop.state.LoopState;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 循环结果
 * 代表整个循环执行的结果
 * 
 * @author noear
 * @since 4.0.3
 */
public class LoopResult {
    
    private final String sessionId;
    private final LoopState finalState;
    private final Object result;
    private final boolean success;
    private final String message;
    private final Duration totalDuration;
    private final int totalIterations;
    private final int successfulIterations;
    private final double successRate;
    private final Instant startTime;
    private final Instant endTime;
    private final List<IterationResult> iterationHistory;
    private final Map<String, Object> metadata;
    
    public LoopResult(String sessionId, LoopState finalState, Object result, 
                     boolean success, String message, Duration totalDuration,
                     int totalIterations, int successfulIterations, double successRate,
                     Instant startTime, Instant endTime, 
                     List<IterationResult> iterationHistory, Map<String, Object> metadata) {
        this.sessionId = sessionId;
        this.finalState = finalState;
        this.result = result;
        this.success = success;
        this.message = message;
        this.totalDuration = totalDuration;
        this.totalIterations = totalIterations;
        this.successfulIterations = successfulIterations;
        this.successRate = successRate;
        this.startTime = startTime;
        this.endTime = endTime;
        this.iterationHistory = iterationHistory;
        this.metadata = metadata;
    }
    
    public String getSessionId() {
        return sessionId;
    }
    
    public LoopState getFinalState() {
        return finalState;
    }
    
    public Object getResult() {
        return result;
    }
    
    public boolean isSuccess() {
        return success;
    }
    
    public String getMessage() {
        return message;
    }
    
    public Duration getTotalDuration() {
        return totalDuration;
    }
    
    public int getTotalIterations() {
        return totalIterations;
    }
    
    public int getSuccessfulIterations() {
        return successfulIterations;
    }
    
    public double getSuccessRate() {
        return successRate;
    }
    
    public Instant getStartTime() {
        return startTime;
    }
    
    public Instant getEndTime() {
        return endTime;
    }
    
    public List<IterationResult> getIterationHistory() {
        return iterationHistory;
    }
    
    public Map<String, Object> getMetadata() {
        return metadata;
    }
    
    @Override
    public String toString() {
        return "LoopResult{" +
                "sessionId='" + sessionId + '\'' +
                ", finalState=" + finalState +
                ", success=" + success +
                ", message='" + message + '\'' +
                ", totalDuration=" + totalDuration +
                ", totalIterations=" + totalIterations +
                ", successRate=" + successRate +
                '}';
    }
}