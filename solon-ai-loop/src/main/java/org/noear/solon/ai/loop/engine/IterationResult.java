package org.noear.solon.ai.loop.engine;

import org.noear.solon.ai.loop.state.LoopState;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * 迭代结果
 * 代表一次循环迭代的结果
 * 
 * @author noear
 * @since 4.0.3
 */
public class IterationResult {
    
    private final int number;
    private final int total;
    private final LoopState state;
    private final Object result;
    private final boolean success;
    private final String message;
    private final Duration duration;
    private final Instant startTime;
    private final Instant endTime;
    private final Map<String, Object> metadata;
    
    public IterationResult(int number, int total, LoopState state, Object result, 
                          boolean success, String message, Duration duration,
                          Instant startTime, Instant endTime, Map<String, Object> metadata) {
        this.number = number;
        this.total = total;
        this.state = state;
        this.result = result;
        this.success = success;
        this.message = message;
        this.duration = duration;
        this.startTime = startTime;
        this.endTime = endTime;
        this.metadata = metadata;
    }
    
    public int getNumber() {
        return number;
    }
    
    public int getTotal() {
        return total;
    }
    
    public LoopState getState() {
        return state;
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
    
    public Duration getDuration() {
        return duration;
    }
    
    public Instant getStartTime() {
        return startTime;
    }
    
    public Instant getEndTime() {
        return endTime;
    }
    
    public Map<String, Object> getMetadata() {
        return metadata;
    }
    
    @Override
    public String toString() {
        return "IterationResult{" +
                "number=" + number +
                ", total=" + total +
                ", state=" + state +
                ", success=" + success +
                ", message='" + message + '\'' +
                ", duration=" + duration +
                '}';
    }
}