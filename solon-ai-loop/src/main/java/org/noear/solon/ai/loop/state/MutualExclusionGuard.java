package org.noear.solon.ai.loop.state;

import org.noear.solon.ai.loop.state.LoopStateData;
import org.noear.solon.ai.loop.state.disk.DiskStateManager;

/**
 * 策略互斥守卫 —— 对标 oh-my-claudecode 的 ralph/loop.ts:isUltraQAActive / ultraqa/index.ts:isRalphLoopActive
 *
 * <p>确保 Ralph 循环和 UltraQA 循环不会同时运行。
 * 同时对 Team Pipeline 提供基本互斥保护。</p>
 *
 * <p>互斥规则：</p>
 * <ul>
 *   <li>Ralph 启动前：检查 UltraQA 是否活跃</li>
 *   <li>UltraQA 启动前：检查 Ralph 是否活跃</li>
 *   <li>TeamPipeline 启动前：检查 Ralph 和 UltraQA 都不活跃</li>
 *   <li>强制释放：允许 /cancel 操作强制解锁</li>
 * </ul>
 *
 * @since 4.0.3
 */
public class MutualExclusionGuard {

    private static final String MODE_RALPH = "ralph";
    private static final String MODE_TEAM = "team";
    private static final String MODE_ULTRAQA = "ultraqa";

    private final DiskStateManager stateManager;

    /**
     * 当前运行的策略信息（用于互斥检测）。
     * key: sessionId, value: mode
     */
    private final java.util.concurrent.ConcurrentHashMap<String, String> activeModes =
            new java.util.concurrent.ConcurrentHashMap<>();

    public MutualExclusionGuard(DiskStateManager stateManager) {
        this.stateManager = stateManager;
    }

    /**
     * Ralph 启动前检查：UltraQA 是否活跃。
     *
     * @param sessionId 会话 ID
     * @return true 可以启动 Ralph
     */
    public boolean canStartRalph(String sessionId) {
        // 检查 UltraQA 是否活跃（含当前 session，同 session 互斥）
        for (java.util.Map.Entry<String, String> entry : activeModes.entrySet()) {
            if (MODE_ULTRAQA.equals(entry.getValue())) {
                return false;
            }
        }
        // 检查磁盘状态中是否有 UltraQA 活跃记录
        if (stateManager.hasState(MODE_ULTRAQA, sessionId)) {
            return false;
        }
        return true;
    }

    /**
     * UltraQA 启动前检查：Ralph 是否活跃。
     *
     * @param sessionId 会话 ID
     * @return true 可以启动 UltraQA
     */
    public boolean canStartUltraQA(String sessionId) {
        // 检查任何 session 是否有 Ralph 活跃（含当前 session，同 session 互斥）
        for (java.util.Map.Entry<String, String> entry : activeModes.entrySet()) {
            if (MODE_RALPH.equals(entry.getValue())) {
                return false;
            }
        }
        if (stateManager.hasState(MODE_RALPH, sessionId)) {
            return false;
        }
        return true;
    }

    /**
     * TeamPipeline 启动前检查：Ralph 和 UltraQA 都不活跃。
     */
    public boolean canStartTeam(String sessionId) {
        for (java.util.Map.Entry<String, String> entry : activeModes.entrySet()) {
            String mode = entry.getValue();
            if (MODE_RALPH.equals(mode) || MODE_ULTRAQA.equals(mode)) {
                return false;
            }
        }
        // 也检查磁盘状态
        if (stateManager.hasState(MODE_RALPH, sessionId)) {
            return false;
        }
        if (stateManager.hasState(MODE_ULTRAQA, sessionId)) {
            return false;
        }
        return true;
    }

    /**
     * 获取当前活跃的策略名称。
     *
     * @param sessionId 会话 ID
     * @return 活跃的策略名，无活跃返回 null
     */
    public String getActiveMode(String sessionId) {
        return activeModes.get(sessionId);
    }

    /**
     * 获取会话中活跃的所有模式（用于错误提示）。
     *
     * @param excludeSessionId 排除的会话 ID
     * @return 活跃模式列表的描述
     */
    public String getActiveModesDescription(String excludeSessionId) {
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> entry : activeModes.entrySet()) {
            if (!entry.getKey().equals(excludeSessionId)) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(entry.getValue()).append("(session=").append(entry.getKey()).append(")");
            }
        }
        return sb.toString();
    }

    /**
     * 注册策略为活跃状态（加锁）。
     *
     * @param sessionId 会话 ID
     * @param mode      模式名
     * @return 是否成功注册
     */
    public synchronized boolean acquire(String sessionId, String mode) {
        // 先检查互斥
        switch (mode) {
            case MODE_RALPH:
                if (!canStartRalph(sessionId)) return false;
                break;
            case MODE_ULTRAQA:
                if (!canStartUltraQA(sessionId)) return false;
                break;
            case MODE_TEAM:
                if (!canStartTeam(sessionId)) return false;
                break;
            default:
                return false;
        }
        activeModes.put(sessionId, mode);
        return true;
    }

    /**
     * 释放策略锁。
     *
     * @param sessionId 会话 ID
     */
    public synchronized void release(String sessionId) {
        activeModes.remove(sessionId);
    }

    /**
     * 强制释放某个策略（用于 /cancel 操作）。
     *
     * @param mode      模式名
     * @param sessionId 会话 ID
     * @return 是否成功释放
     */
    public synchronized boolean forceRelease(String mode, String sessionId) {
        String current = activeModes.get(sessionId);
        if (current != null && current.equals(mode)) {
            activeModes.remove(sessionId);
            // 同时清除磁盘状态
            stateManager.clearState(mode, sessionId);
            return true;
        }
        // 如果不是精确匹配，尝试强制移除
        activeModes.remove(sessionId);
        // 同时清除磁盘状态
        stateManager.clearState(mode, sessionId);
        return true;
    }

    /**
     * 检测并清理 stale 锁。
     * 如果磁盘状态文件存在但内存中无对应活跃记录，且状态文件超过指定时间未更新，则视为 stale。
     *
     * @param maxAgeMs 最大年龄（毫秒）
     * @return 清理的 stale 锁数量
     */
    public synchronized int cleanupStaleLocks(long maxAgeMs) {
        int cleaned = 0;
        for (String mode : new String[]{MODE_RALPH, MODE_ULTRAQA, MODE_TEAM}) {
            for (String sid : stateManager.getAllActiveSessionIds()) {
                if (!activeModes.containsKey(sid)) {
                    // 内存中无记录，检查磁盘状态是否 stale
                    if (stateManager.hasState(mode, sid)) {
                        // 尝试读取状态，检查最后更新时间
                        LoopStateData data = stateManager.readState(mode, sid);
                        if (data != null && data.getLastUpdateTime() != null) {
                            long age = System.currentTimeMillis() - data.getLastUpdateTime().toEpochMilli();
                            if (age > maxAgeMs) {
                                stateManager.clearState(mode, sid);
                                cleaned++;
                            }
                        } else if (data == null) {
                            // 状态文件存在但无法读取，清理
                            stateManager.clearState(mode, sid);
                            cleaned++;
                        }
                    }
                }
            }
        }
        return cleaned;
    }

    /**
     * 检查某个模式是否活跃。
     */
    public boolean isActive(String mode, String sessionId) {
        return mode.equals(activeModes.get(sessionId));
    }

    /**
     * 检查任何策略是否活跃（用于全局状态查询）。
     */
    public boolean isAnyActive() {
        return !activeModes.isEmpty();
    }

    /**
     * 获取活跃的会话数量。
     */
    public int getActiveCount() {
        return activeModes.size();
    }
}
