package org.noear.solon.ai.loop.state;

import java.io.Serializable;
import java.time.Instant;
import java.util.Map;

/**
 * 循环状态数据
 * 用于持久化循环状态
 * 
 * @author noear
 * @since 4.0.3
 */
public class LoopStateData implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    private String sessionId;
    private LoopState state;
    private int iterationCount;
    private int successfulIterations;
    private Instant startTime;
    private Instant lastUpdateTime;
    private Map<String, Object> contextData;
    private Map<String, Object> metadata;
    
    public LoopStateData() {
    }
    
    public LoopStateData(String sessionId, LoopState state, int iterationCount, 
                        int successfulIterations, Instant startTime, Instant lastUpdateTime,
                        Map<String, Object> contextData, Map<String, Object> metadata) {
        this.sessionId = sessionId;
        this.state = state;
        this.iterationCount = iterationCount;
        this.successfulIterations = successfulIterations;
        this.startTime = startTime;
        this.lastUpdateTime = lastUpdateTime;
        this.contextData = contextData;
        this.metadata = metadata;
    }
    
    public String getSessionId() {
        return sessionId;
    }
    
    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }
    
    public LoopState getState() {
        return state;
    }
    
    public void setState(LoopState state) {
        this.state = state;
    }
    
    public int getIterationCount() {
        return iterationCount;
    }
    
    public void setIterationCount(int iterationCount) {
        this.iterationCount = iterationCount;
    }
    
    public int getSuccessfulIterations() {
        return successfulIterations;
    }
    
    public void setSuccessfulIterations(int successfulIterations) {
        this.successfulIterations = successfulIterations;
    }
    
    public Instant getStartTime() {
        return startTime;
    }
    
    public void setStartTime(Instant startTime) {
        this.startTime = startTime;
    }
    
    public Instant getLastUpdateTime() {
        return lastUpdateTime;
    }
    
    public void setLastUpdateTime(Instant lastUpdateTime) {
        this.lastUpdateTime = lastUpdateTime;
    }
    
    public Map<String, Object> getContextData() {
        return contextData;
    }
    
    public void setContextData(Map<String, Object> contextData) {
        this.contextData = contextData;
    }
    
    public Map<String, Object> getMetadata() {
        return metadata;
    }
    
    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }
    
    /**
     * 更新最后更新时间
     */
    public void updateLastUpdateTime() {
        this.lastUpdateTime = Instant.now();
    }
    
    @Override
    public String toString() {
        return "LoopStateData{" +
                "sessionId='" + sessionId + '\'' +
                ", state=" + state +
                ", iterationCount=" + iterationCount +
                ", successfulIterations=" + successfulIterations +
                ", startTime=" + startTime +
                ", lastUpdateTime=" + lastUpdateTime +
                '}';
    }
}