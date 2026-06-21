package org.noear.solon.ai.loop.config;

import org.noear.solon.ai.loop.state.StateManager;
import org.noear.solon.ai.loop.state.InMemoryStateManager;

/**
 * 循环引擎配置
 * 
 * @author noear
 * @since 4.0.3
 */
public class LoopEngineConfig {
    
    private StateManager stateManager;
    private boolean monitoringEnabled;
    private boolean debuggingEnabled;
    private int cleanupInterval;
    private long stateExpirationTime;
    
    public LoopEngineConfig() {
        this.stateManager = new InMemoryStateManager();
        this.monitoringEnabled = true;
        this.debuggingEnabled = false;
        this.cleanupInterval = 300; // 5 minutes
        this.stateExpirationTime = 3600000; // 1 hour
    }
    
    public StateManager getStateManager() {
        return stateManager;
    }
    
    public void setStateManager(StateManager stateManager) {
        this.stateManager = stateManager;
    }
    
    public boolean isMonitoringEnabled() {
        return monitoringEnabled;
    }
    
    public void setMonitoringEnabled(boolean monitoringEnabled) {
        this.monitoringEnabled = monitoringEnabled;
    }
    
    public boolean isDebuggingEnabled() {
        return debuggingEnabled;
    }
    
    public void setDebuggingEnabled(boolean debuggingEnabled) {
        this.debuggingEnabled = debuggingEnabled;
    }
    
    public int getCleanupInterval() {
        return cleanupInterval;
    }
    
    public void setCleanupInterval(int cleanupInterval) {
        this.cleanupInterval = cleanupInterval;
    }
    
    public long getStateExpirationTime() {
        return stateExpirationTime;
    }
    
    public void setStateExpirationTime(long stateExpirationTime) {
        this.stateExpirationTime = stateExpirationTime;
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
        private StateManager stateManager = new InMemoryStateManager();
        private boolean monitoringEnabled = true;
        private boolean debuggingEnabled = false;
        private int cleanupInterval = 300;
        private long stateExpirationTime = 3600000;
        
        public Builder stateManager(StateManager stateManager) {
            this.stateManager = stateManager;
            return this;
        }
        
        public Builder monitoringEnabled(boolean monitoringEnabled) {
            this.monitoringEnabled = monitoringEnabled;
            return this;
        }
        
        public Builder debuggingEnabled(boolean debuggingEnabled) {
            this.debuggingEnabled = debuggingEnabled;
            return this;
        }
        
        public Builder cleanupInterval(int cleanupInterval) {
            this.cleanupInterval = cleanupInterval;
            return this;
        }
        
        public Builder stateExpirationTime(long stateExpirationTime) {
            this.stateExpirationTime = stateExpirationTime;
            return this;
        }
        
        public LoopEngineConfig build() {
            LoopEngineConfig config = new LoopEngineConfig();
            config.setStateManager(stateManager);
            config.setMonitoringEnabled(monitoringEnabled);
            config.setDebuggingEnabled(debuggingEnabled);
            config.setCleanupInterval(cleanupInterval);
            config.setStateExpirationTime(stateExpirationTime);
            return config;
        }
    }
    
    @Override
    public String toString() {
        return "LoopEngineConfig{" +
                "stateManager=" + stateManager.getClass().getSimpleName() +
                ", monitoringEnabled=" + monitoringEnabled +
                ", debuggingEnabled=" + debuggingEnabled +
                ", cleanupInterval=" + cleanupInterval +
                ", stateExpirationTime=" + stateExpirationTime +
                '}';
    }
}