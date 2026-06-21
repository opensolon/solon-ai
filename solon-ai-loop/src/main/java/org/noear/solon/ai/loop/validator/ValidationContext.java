package org.noear.solon.ai.loop.validator;

import java.util.Map;

/**
 * 验证上下文
 * 包含验证过程中需要的上下文信息
 * 
 * @author noear
 * @since 4.0.3
 */
public class ValidationContext {
    
    private final String sessionId;
    private final int iterationNumber;
    private final String taskDescription;
    private final Map<String, Object> contextData;
    private final Map<String, Object> metadata;
    
    public ValidationContext(String sessionId, int iterationNumber, String taskDescription,
                            Map<String, Object> contextData, Map<String, Object> metadata) {
        this.sessionId = sessionId;
        this.iterationNumber = iterationNumber;
        this.taskDescription = taskDescription;
        this.contextData = contextData;
        this.metadata = metadata;
    }
    
    public String getSessionId() {
        return sessionId;
    }
    
    public int getIterationNumber() {
        return iterationNumber;
    }
    
    public String getTaskDescription() {
        return taskDescription;
    }
    
    public Map<String, Object> getContextData() {
        return contextData;
    }
    
    public Map<String, Object> getMetadata() {
        return metadata;
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
     * 获取元数据
     * 
     * @param key 键
     * @return 值
     */
    public Object getMetadata(String key) {
        return metadata != null ? metadata.get(key) : null;
    }
    
    @Override
    public String toString() {
        return "ValidationContext{" +
                "sessionId='" + sessionId + '\'' +
                ", iterationNumber=" + iterationNumber +
                ", taskDescription='" + taskDescription + '\'' +
                '}';
    }
}