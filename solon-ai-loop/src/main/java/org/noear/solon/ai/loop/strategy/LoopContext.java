package org.noear.solon.ai.loop.strategy;

import org.noear.solon.ai.loop.state.LoopState;
import org.noear.solon.ai.loop.validator.ValidationResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 循环上下文
 * 包含循环执行过程中需要的上下文信息
 * 
 * @author noear
 * @since 4.0.3
 */
public class LoopContext {
    
    private final String sessionId;
    private final String taskDescription;
    private final LoopState currentState;
    private final int iterationCount;
    private final int maxIterations;
    private final Instant startTime;
    private final Map<String, Object> parameters;
    private final List<ValidationResult> validationResults;
    private final Map<String, Object> contextData;
    
    public LoopContext(String sessionId, String taskDescription, LoopState currentState,
                      int iterationCount, int maxIterations, Instant startTime,
                      Map<String, Object> parameters, List<ValidationResult> validationResults,
                      Map<String, Object> contextData) {
        this.sessionId = sessionId;
        this.taskDescription = taskDescription;
        this.currentState = currentState;
        this.iterationCount = iterationCount;
        this.maxIterations = maxIterations;
        this.startTime = startTime;
        this.parameters = parameters;
        this.validationResults = validationResults;
        this.contextData = contextData;
    }
    
    public String getSessionId() {
        return sessionId;
    }
    
    public String getTaskDescription() {
        return taskDescription;
    }
    
    public LoopState getCurrentState() {
        return currentState;
    }
    
    public int getIterationCount() {
        return iterationCount;
    }
    
    public int getMaxIterations() {
        return maxIterations;
    }
    
    public Instant getStartTime() {
        return startTime;
    }
    
    public Map<String, Object> getParameters() {
        return parameters;
    }
    
    public List<ValidationResult> getValidationResults() {
        return validationResults;
    }
    
    public Map<String, Object> getContextData() {
        return contextData;
    }
    
    /**
     * 获取执行时间
     * 
     * @return 执行时间
     */
    public Duration getExecutionTime() {
        return Duration.between(startTime, Instant.now());
    }
    
    /**
     * 检查是否达到最大迭代次数
     * 
     * @return 是否达到最大迭代次数
     */
    public boolean isMaxIterationsReached() {
        return iterationCount >= maxIterations;
    }
    
    /**
     * 获取参数
     * 
     * @param key 键
     * @return 值
     */
    public Object getParameter(String key) {
        return parameters != null ? parameters.get(key) : null;
    }
    
    /**
     * 获取上下文数据
     * 
     * @param key 键
     * @return 值
     */
    public Object get(String key) {
        return contextData != null ? contextData.get(key) : null;
    }
    
    /**
     * 获取最新的验证结果
     * 
     * @return 最新的验证结果，如果没有返回null
     */
    public ValidationResult getLatestValidationResult() {
        if (validationResults == null || validationResults.isEmpty()) {
            return null;
        }
        return validationResults.get(validationResults.size() - 1);
    }
    
    @Override
    public String toString() {
        return "LoopContext{" +
                "sessionId='" + sessionId + '\'' +
                ", taskDescription='" + taskDescription + '\'' +
                ", currentState=" + currentState +
                ", iterationCount=" + iterationCount +
                ", maxIterations=" + maxIterations +
                '}';
    }
}