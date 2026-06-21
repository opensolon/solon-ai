package org.noear.solon.ai.loop.config;

import org.noear.solon.ai.loop.strategy.LoopStrategy;
import org.noear.solon.ai.loop.validator.Validator;

import java.util.Map;

/**
 * 循环配置
 * 
 * @author noear
 * @since 4.0.3
 */
public class LoopConfig {
    
    private final String taskDescription;
    private final LoopStrategy strategy;
    private final Validator validator;
    private final int maxIterations;
    private final boolean verificationRequired;
    private final boolean statePersistenceEnabled;
    private final Map<String, Object> parameters;
    
    public LoopConfig(String taskDescription, LoopStrategy strategy, Validator validator,
                     int maxIterations, boolean verificationRequired, 
                     boolean statePersistenceEnabled, Map<String, Object> parameters) {
        this.taskDescription = taskDescription;
        this.strategy = strategy;
        this.validator = validator;
        this.maxIterations = maxIterations;
        this.verificationRequired = verificationRequired;
        this.statePersistenceEnabled = statePersistenceEnabled;
        this.parameters = parameters;
    }
    
    public String getTaskDescription() {
        return taskDescription;
    }
    
    public LoopStrategy getStrategy() {
        return strategy;
    }
    
    public Validator getValidator() {
        return validator;
    }
    
    public int getMaxIterations() {
        return maxIterations;
    }
    
    public boolean isVerificationRequired() {
        return verificationRequired;
    }
    
    public boolean isStatePersistenceEnabled() {
        return statePersistenceEnabled;
    }
    
    public Map<String, Object> getParameters() {
        return parameters;
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
     * 创建构建器
     * 
     * @return 构建器
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * 构建器
     */
    public static class Builder {
        private String taskDescription;
        private LoopStrategy strategy;
        private Validator validator;
        private int maxIterations = 100;
        private boolean verificationRequired = true;
        private boolean statePersistenceEnabled = true;
        private Map<String, Object> parameters;
        
        public Builder taskDescription(String taskDescription) {
            this.taskDescription = taskDescription;
            return this;
        }
        
        public Builder strategy(LoopStrategy strategy) {
            this.strategy = strategy;
            return this;
        }
        
        public Builder validator(Validator validator) {
            this.validator = validator;
            return this;
        }
        
        public Builder maxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
            return this;
        }
        
        public Builder verificationRequired(boolean verificationRequired) {
            this.verificationRequired = verificationRequired;
            return this;
        }
        
        public Builder statePersistenceEnabled(boolean statePersistenceEnabled) {
            this.statePersistenceEnabled = statePersistenceEnabled;
            return this;
        }
        
        public Builder parameters(Map<String, Object> parameters) {
            this.parameters = parameters;
            return this;
        }
        
        public LoopConfig build() {
            return new LoopConfig(taskDescription, strategy, validator, maxIterations,
                                verificationRequired, statePersistenceEnabled, parameters);
        }
    }
    
    @Override
    public String toString() {
        return "LoopConfig{" +
                "taskDescription='" + taskDescription + '\'' +
                ", strategy=" + (strategy != null ? strategy.getName() : "null") +
                ", maxIterations=" + maxIterations +
                ", verificationRequired=" + verificationRequired +
                '}';
    }
}