package org.noear.solon.ai.loop.monitor;

import org.noear.solon.ai.loop.engine.LoopEngine;
import org.noear.solon.ai.loop.engine.LoopSession;
import org.noear.solon.ai.loop.engine.IterationResult;
import org.noear.solon.ai.loop.state.LoopState;
import org.noear.solon.ai.loop.validator.ValidationResult;

import java.time.Instant;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 循环调试器
 * 提供循环执行的调试和日志功能
 * 
 * @author noear
 * @since 4.0.3
 */
public class LoopDebugger {
    
    private final LoopEngine engine;
    private final boolean enabled;
    private final Map<String, List<DebugEvent>> sessionEvents = new ConcurrentHashMap<>();
    private final Map<String, DebugSession> debugSessions = new ConcurrentHashMap<>();
    
    public LoopDebugger(LoopEngine engine) {
        this(engine, true);
    }
    
    public LoopDebugger(LoopEngine engine, boolean enabled) {
        this.engine = engine;
        this.enabled = enabled;
    }
    
    /**
     * 开始调试会话
     * 
     * @param sessionId 会话ID
     */
    public void startDebugSession(String sessionId) {
        if (!enabled) {
            return;
        }
        
        LoopSession session = engine.getSession(sessionId);
        if (session == null) {
            return;
        }
        
        DebugSession debugSession = new DebugSession(sessionId);
        debugSessions.put(sessionId, debugSession);
        
        // 添加监听器
        session.onStateChange(state -> {
            addEvent(sessionId, DebugEvent.Type.STATE_CHANGE, 
                    "State changed to: " + state, state);
        });
        
        session.onIterationComplete(result -> {
            addEvent(sessionId, DebugEvent.Type.ITERATION_COMPLETE, 
                    "Iteration " + result.getNumber() + " completed: " + 
                    (result.isSuccess() ? "SUCCESS" : "FAILED"), result);
        });
        
        session.onValidationResult(result -> {
            addEvent(sessionId, DebugEvent.Type.VALIDATION_RESULT, 
                    "Validation " + (result.isPassed() ? "PASSED" : "FAILED") + 
                    ": " + result.getMessage(), result);
        });
    }
    
    /**
     * 添加调试事件
     * 
     * @param sessionId 会话ID
     * @param type 事件类型
     * @param message 事件消息
     * @param data 事件数据
     */
    private void addEvent(String sessionId, DebugEvent.Type type, String message, Object data) {
        if (!enabled) {
            return;
        }
        
        List<DebugEvent> events = sessionEvents.computeIfAbsent(
            sessionId, 
            id -> Collections.synchronizedList(new ArrayList<>())
        );
        
        events.add(new DebugEvent(type, message, data, Instant.now()));
    }
    
    /**
     * 获取会话事件
     * 
     * @param sessionId 会话ID
     * @return 事件列表
     */
    public List<DebugEvent> getSessionEvents(String sessionId) {
        List<DebugEvent> events = sessionEvents.get(sessionId);
        return events != null ? Collections.unmodifiableList(events) : Collections.emptyList();
    }
    
    /**
     * 获取所有会话事件
     * 
     * @return 所有会话事件
     */
    public Map<String, List<DebugEvent>> getAllSessionEvents() {
        return Collections.unmodifiableMap(sessionEvents);
    }
    
    /**
     * 获取调试会话
     * 
     * @param sessionId 会话ID
     * @return 调试会话，如果不存在返回null
     */
    public DebugSession getDebugSession(String sessionId) {
        return debugSessions.get(sessionId);
    }
    
    /**
     * 获取所有调试会话
     * 
     * @return 所有调试会话
     */
    public Collection<DebugSession> getAllDebugSessions() {
        return Collections.unmodifiableCollection(debugSessions.values());
    }
    
    /**
     * 结束调试会话
     * 
     * @param sessionId 会话ID
     */
    public void endDebugSession(String sessionId) {
        DebugSession debugSession = debugSessions.get(sessionId);
        if (debugSession != null) {
            debugSession.end();
        }
    }
    
    /**
     * 获取调试报告
     * 
     * @param sessionId 会话ID
     * @return 调试报告
     */
    public String getDebugReport(String sessionId) {
        StringBuilder report = new StringBuilder();
        report.append("=== Debug Report for Session: ").append(sessionId).append(" ===\n");
        
        DebugSession debugSession = debugSessions.get(sessionId);
        if (debugSession != null) {
            report.append("Session Duration: ").append(debugSession.getDuration()).append("\n");
            report.append("Event Count: ").append(debugSession.getEventCount()).append("\n");
        }
        
        List<DebugEvent> events = getSessionEvents(sessionId);
        if (!events.isEmpty()) {
            report.append("\nEvents:\n");
            for (DebugEvent event : events) {
                report.append("  [").append(event.getTimestamp()).append("] ");
                report.append(event.getType()).append(": ");
                report.append(event.getMessage()).append("\n");
            }
        }
        
        return report.toString();
    }
    
    /**
     * 获取摘要报告
     * 
     * @return 摘要报告
     */
    public String getSummaryReport() {
        StringBuilder report = new StringBuilder();
        report.append("=== Loop Debugger Summary ===\n");
        report.append("Active Debug Sessions: ").append(debugSessions.size()).append("\n");
        report.append("Total Events: ").append(
            sessionEvents.values().stream().mapToInt(List::size).sum()
        ).append("\n");
        
        report.append("\nSession Details:\n");
        for (DebugSession session : debugSessions.values()) {
            report.append("  Session ").append(session.getSessionId()).append(":\n");
            report.append("    Duration: ").append(session.getDuration()).append("\n");
            report.append("    Events: ").append(session.getEventCount()).append("\n");
            report.append("    Ended: ").append(session.isEnded()).append("\n");
        }
        
        return report.toString();
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
     * 清除调试数据
     * 
     * @param sessionId 会话ID
     */
    public void clearDebugData(String sessionId) {
        sessionEvents.remove(sessionId);
        debugSessions.remove(sessionId);
    }
    
    /**
     * 清除所有调试数据
     */
    public void clearAllDebugData() {
        sessionEvents.clear();
        debugSessions.clear();
    }
    
    /**
     * 调试事件
     */
    public static class DebugEvent {
        
        public enum Type {
            STATE_CHANGE,
            ITERATION_COMPLETE,
            VALIDATION_RESULT,
            ERROR,
            WARNING,
            INFO
        }
        
        private final Type type;
        private final String message;
        private final Object data;
        private final Instant timestamp;
        
        public DebugEvent(Type type, String message, Object data, Instant timestamp) {
            this.type = type;
            this.message = message;
            this.data = data;
            this.timestamp = timestamp;
        }
        
        public Type getType() {
            return type;
        }
        
        public String getMessage() {
            return message;
        }
        
        public Object getData() {
            return data;
        }
        
        public Instant getTimestamp() {
            return timestamp;
        }
        
        @Override
        public String toString() {
            return "DebugEvent{" +
                    "type=" + type +
                    ", message='" + message + '\'' +
                    ", timestamp=" + timestamp +
                    '}';
        }
    }
    
    /**
     * 调试会话
     */
    public static class DebugSession {
        
        private final String sessionId;
        private final Instant startTime;
        private volatile Instant endTime;
        private final List<DebugEvent> events = Collections.synchronizedList(new ArrayList<>());
        
        public DebugSession(String sessionId) {
            this.sessionId = sessionId;
            this.startTime = Instant.now();
        }
        
        public String getSessionId() {
            return sessionId;
        }
        
        public Instant getStartTime() {
            return startTime;
        }
        
        public Instant getEndTime() {
            return endTime;
        }
        
        public boolean isEnded() {
            return endTime != null;
        }
        
        public Duration getDuration() {
            Instant end = endTime != null ? endTime : Instant.now();
            return Duration.between(startTime, end);
        }
        
        public int getEventCount() {
            return events.size();
        }
        
        public void end() {
            this.endTime = Instant.now();
        }
        
        @Override
        public String toString() {
            return "DebugSession{" +
                    "sessionId='" + sessionId + '\'' +
                    ", duration=" + getDuration() +
                    ", eventCount=" + events.size() +
                    ", ended=" + isEnded() +
                    '}';
        }
    }
}