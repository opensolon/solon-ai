package org.noear.solon.ai.loop.state;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存状态管理器
 * 基于内存的状态持久化实现。
 *
 * <p>增强后支持 Session 身份验证追踪。</p>
 *
 * @author noear
 * @since 4.0.3
 */
public class InMemoryStateManager implements StateManager {

    private final Map<String, LoopStateData> stateStore = new ConcurrentHashMap<>();
    private final Map<String, String> sessionProjectMap = new ConcurrentHashMap<>(); // sessionId -> projectPath

    @Override
    public void saveState(String sessionId, LoopStateData state) {
        if (sessionId == null || state == null) {
            throw new IllegalArgumentException("SessionId and state cannot be null");
        }

        state.setLastUpdateTime(Instant.now());
        stateStore.put(sessionId, state);
    }

    @Override
    public LoopStateData loadState(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        return stateStore.get(sessionId);
    }

    @Override
    public void clearState(String sessionId) {
        if (sessionId != null) {
            stateStore.remove(sessionId);
            sessionProjectMap.remove(sessionId);
        }
    }

    @Override
    public boolean hasState(String sessionId) {
        return sessionId != null && stateStore.containsKey(sessionId);
    }

    @Override
    public List<String> getAllSessionIds() {
        return new ArrayList<>(stateStore.keySet());
    }

    @Override
    public int cleanupExpiredStates(long maxAge) {
        Instant cutoff = Instant.now().minusMillis(maxAge);
        List<String> expiredSessions = new ArrayList<>();

        for (Map.Entry<String, LoopStateData> entry : stateStore.entrySet()) {
            LoopStateData state = entry.getValue();
            if (state.getLastUpdateTime() != null && state.getLastUpdateTime().isBefore(cutoff)) {
                expiredSessions.add(entry.getKey());
            }
        }

        expiredSessions.forEach(sessionId -> {
            stateStore.remove(sessionId);
            sessionProjectMap.remove(sessionId);
        });
        return expiredSessions.size();
    }

    /**
     * 注册 Session 所属项目（用于身份验证）。
     *
     * @param sessionId   会话 ID
     * @param projectPath 项目路径
     */
    public void registerSessionProject(String sessionId, String projectPath) {
        if (sessionId != null && projectPath != null) {
            sessionProjectMap.put(sessionId, projectPath);
        }
    }

    /**
     * 验证 Session 是否属于指定项目。
     *
     * @param sessionId   会话 ID
     * @param projectPath 项目路径
     * @return true 如果 session 属于该项目
     */
    public boolean belongsToProject(String sessionId, String projectPath) {
        String registered = sessionProjectMap.get(sessionId);
        return registered != null && registered.equals(projectPath);
    }

    /**
     * 获取存储大小。
     */
    public int size() {
        return stateStore.size();
    }

    /**
     * 清空所有状态。
     */
    public void clearAll() {
        stateStore.clear();
        sessionProjectMap.clear();
    }

    /**
     * 获取已注册 Session 的数量（含关联项目）。
     */
    public int sessionCount() {
        return sessionProjectMap.size();
    }
}
