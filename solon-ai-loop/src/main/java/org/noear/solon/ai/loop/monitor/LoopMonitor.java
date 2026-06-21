package org.noear.solon.ai.loop.monitor;

import org.noear.solon.ai.loop.engine.LoopEngine;
import org.noear.solon.ai.loop.engine.LoopResult;
import org.noear.solon.ai.loop.engine.LoopSession;
import org.noear.solon.ai.loop.state.LoopState;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 循环监控器
 * 提供循环执行的监控和统计功能
 * 
 * @author noear
 * @since 4.0.3
 */
public class LoopMonitor {
    
    private final LoopEngine engine;
    private final ScheduledExecutorService scheduler;
    private final Map<String, SessionMetrics> sessionMetrics = new ConcurrentHashMap<>();
    private final boolean enabled;
    
    public LoopMonitor(LoopEngine engine) {
        this(engine, true);
    }
    
    public LoopMonitor(LoopEngine engine, boolean enabled) {
        this.engine = engine;
        this.enabled = enabled;
        this.scheduler = enabled ? Executors.newScheduledThreadPool(1) : null;
        
        if (enabled) {
            startMonitoring();
        }
    }
    
    /**
     * 开始监控
     */
    private void startMonitoring() {
        scheduler.scheduleAtFixedRate(this::collectMetrics, 0, 10, TimeUnit.SECONDS);
    }
    
    /**
     * 收集指标
     */
    private void collectMetrics() {
        if (!enabled) {
            return;
        }
        
        for (LoopSession session : engine.getActiveSessions()) {
            SessionMetrics metrics = sessionMetrics.computeIfAbsent(
                session.getId(), 
                id -> new SessionMetrics(id)
            );
            
            metrics.update(session);
        }
    }
    
    /**
     * 获取会话指标
     * 
     * @param sessionId 会话ID
     * @return 会话指标，如果不存在返回null
     */
    public SessionMetrics getSessionMetrics(String sessionId) {
        return sessionMetrics.get(sessionId);
    }
    
    /**
     * 获取所有会话指标
     * 
     * @return 所有会话指标
     */
    public Collection<SessionMetrics> getAllSessionMetrics() {
        return Collections.unmodifiableCollection(sessionMetrics.values());
    }
    
    /**
     * 获取活跃会话数量
     * 
     * @return 活跃会话数量
     */
    public int getActiveSessionCount() {
        return engine.getActiveSessions().size();
    }
    
    /**
     * 获取总迭代次数
     * 
     * @return 总迭代次数
     */
    public int getTotalIterations() {
        return sessionMetrics.values().stream()
                .mapToInt(SessionMetrics::getIterationCount)
                .sum();
    }
    
    /**
     * 获取平均成功率
     * 
     * @return 平均成功率
     */
    public double getAverageSuccessRate() {
        return sessionMetrics.values().stream()
                .mapToDouble(SessionMetrics::getSuccessRate)
                .average()
                .orElse(0.0);
    }
    
    /**
     * 获取监控报告
     * 
     * @return 监控报告
     */
    public String getReport() {
        StringBuilder report = new StringBuilder();
        report.append("=== Loop Engine Monitor Report ===\n");
        report.append("Active Sessions: ").append(getActiveSessionCount()).append("\n");
        report.append("Total Iterations: ").append(getTotalIterations()).append("\n");
        report.append("Average Success Rate: ").append(String.format("%.2f%%", getAverageSuccessRate() * 100)).append("\n");
        
        report.append("\nSession Details:\n");
        for (SessionMetrics metrics : sessionMetrics.values()) {
            report.append("  Session ").append(metrics.getSessionId()).append(":\n");
            report.append("    State: ").append(metrics.getState()).append("\n");
            report.append("    Iterations: ").append(metrics.getIterationCount()).append("\n");
            report.append("    Success Rate: ").append(String.format("%.2f%%", metrics.getSuccessRate() * 100)).append("\n");
            report.append("    Duration: ").append(metrics.getDuration()).append("\n");
        }
        
        return report.toString();
    }
    
    /**
     * 停止监控
     */
    public void stop() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
        }
    }
    
    /**
     * 检查是否启用
     * 
     * @return 是否启用
     */
    public boolean isEnabled() {
        return enabled;
    }
    
    /**
     * 会话指标
     */
    public static class SessionMetrics {
        
        private final String sessionId;
        private volatile LoopState state;
        private volatile int iterationCount;
        private volatile double successRate;
        private volatile Duration duration;
        private volatile Instant lastUpdate;
        
        public SessionMetrics(String sessionId) {
            this.sessionId = sessionId;
            this.lastUpdate = Instant.now();
        }
        
        /**
         * 更新指标
         * 
         * @param session 会话
         */
        public void update(LoopSession session) {
            this.state = session.getState();
            this.iterationCount = session.getIterationCount();
            this.successRate = session.getSuccessRate();
            this.duration = session.getExecutionTime();
            this.lastUpdate = Instant.now();
        }
        
        public String getSessionId() {
            return sessionId;
        }
        
        public LoopState getState() {
            return state;
        }
        
        public int getIterationCount() {
            return iterationCount;
        }
        
        public double getSuccessRate() {
            return successRate;
        }
        
        public Duration getDuration() {
            return duration;
        }
        
        public Instant getLastUpdate() {
            return lastUpdate;
        }
        
        @Override
        public String toString() {
            return "SessionMetrics{" +
                    "sessionId='" + sessionId + '\'' +
                    ", state=" + state +
                    ", iterationCount=" + iterationCount +
                    ", successRate=" + successRate +
                    ", duration=" + duration +
                    '}';
        }
    }
}