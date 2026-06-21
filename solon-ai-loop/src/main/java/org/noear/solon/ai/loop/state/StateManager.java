package org.noear.solon.ai.loop.state;

/**
 * 状态管理器接口
 * 负责循环状态的持久化和恢复
 * 
 * @author noear
 * @since 4.0.3
 */
public interface StateManager {
    
    /**
     * 保存状态
     * 
     * @param sessionId 会话ID
     * @param state 循环状态
     */
    void saveState(String sessionId, LoopStateData state);
    
    /**
     * 加载状态
     * 
     * @param sessionId 会话ID
     * @return 循环状态，如果不存在返回null
     */
    LoopStateData loadState(String sessionId);
    
    /**
     * 清理状态
     * 
     * @param sessionId 会话ID
     */
    void clearState(String sessionId);
    
    /**
     * 检查状态是否存在
     * 
     * @param sessionId 会话ID
     * @return 是否存在
     */
    boolean hasState(String sessionId);
    
    /**
     * 获取所有会话ID
     * 
     * @return 会话ID列表
     */
    java.util.List<String> getAllSessionIds();
    
    /**
     * 清理过期状态
     * 
     * @param maxAge 最大年龄（毫秒）
     * @return 清理的状态数量
     */
    int cleanupExpiredStates(long maxAge);
}