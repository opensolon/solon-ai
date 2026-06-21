package org.noear.solon.ai.loop.engine;

import org.noear.solon.ai.loop.state.LoopState;
import org.noear.solon.ai.loop.config.LoopConfig;

/**
 * 循环引擎核心接口
 * 借鉴oh-my-claudecode的循环引擎设计，提供持久化执行能力
 * 
 * @author noear
 * @since 4.0.3
 */
public interface LoopEngine {
    
    /**
     * 启动循环
     * 
     * @param config 循环配置
     * @return 循环会话
     */
    LoopSession start(LoopConfig config);
    
    /**
     * 暂停循环
     * 
     * @param sessionId 会话ID
     */
    void pause(String sessionId);
    
    /**
     * 恢复循环
     * 
     * @param sessionId 会话ID
     */
    void resume(String sessionId);
    
    /**
     * 停止循环
     * 
     * @param sessionId 会话ID
     */
    void stop(String sessionId);
    
    /**
     * 获取循环状态
     * 
     * @param sessionId 会话ID
     * @return 循环状态
     */
    LoopState getState(String sessionId);
    
    /**
     * 获取循环会话
     * 
     * @param sessionId 会话ID
     * @return 循环会话
     */
    LoopSession getSession(String sessionId);
    
    /**
     * 检查循环是否正在运行
     * 
     * @param sessionId 会话ID
     * @return 是否正在运行
     */
    boolean isRunning(String sessionId);
    
    /**
     * 获取所有活跃会话
     * 
     * @return 活跃会话列表
     */
    java.util.List<LoopSession> getActiveSessions();
}