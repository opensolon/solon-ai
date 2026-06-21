package org.noear.solon.ai.loop.state;

import org.noear.solon.Utils;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * 循环状态管理器
 * 管理状态转换逻辑
 * 
 * @author noear
 * @since 4.0.3
 */
public class LoopStateManager {
    
    private static final Map<LoopState, Set<LoopState>> TRANSITIONS = new EnumMap<>(LoopState.class);
    
    static {
        // IDLE -> PLANNING
        TRANSITIONS.put(LoopState.IDLE, Utils.asSet(LoopState.PLANNING));
        
        // PLANNING -> EXECUTING, PAUSED, FAILED
        TRANSITIONS.put(LoopState.PLANNING, Utils.asSet(LoopState.EXECUTING, LoopState.PAUSED, LoopState.FAILED));
        
        // EXECUTING -> VERIFYING, PAUSED, FAILED
        TRANSITIONS.put(LoopState.EXECUTING, Utils.asSet(LoopState.VERIFYING, LoopState.PAUSED, LoopState.FAILED));
        
        // VERIFYING -> COMPLETED, FIXING, PAUSED, FAILED
        TRANSITIONS.put(LoopState.VERIFYING, Utils.asSet(LoopState.COMPLETED, LoopState.FIXING, LoopState.PAUSED, LoopState.FAILED));
        
        // FIXING -> EXECUTING, PAUSED, FAILED
        TRANSITIONS.put(LoopState.FIXING, Utils.asSet(LoopState.EXECUTING, LoopState.PAUSED, LoopState.FAILED));
        
        // PAUSED -> PLANNING, EXECUTING, VERIFYING, FIXING, FAILED
        TRANSITIONS.put(LoopState.PAUSED, Utils.asSet(LoopState.PLANNING, LoopState.EXECUTING, 
                                                LoopState.VERIFYING, LoopState.FIXING, LoopState.FAILED));
        
        // COMPLETED -> 终态，无转换
        TRANSITIONS.put(LoopState.COMPLETED, Utils.asSet());
        
        // FAILED -> 终态，无转换
        TRANSITIONS.put(LoopState.FAILED, Utils.asSet());
    }
    
    /**
     * 检查状态转换是否有效
     * 
     * @param from 源状态
     * @param to 目标状态
     * @return 是否有效
     */
    public static boolean isValidTransition(LoopState from, LoopState to) {
        if (from == null || to == null) {
            return false;
        }
        
        Set<LoopState> allowedTransitions = TRANSITIONS.get(from);
        return allowedTransitions != null && allowedTransitions.contains(to);
    }
    
    /**
     * 执行状态转换
     * 
     * @param currentState 当前状态
     * @param targetState 目标状态
     * @return 新状态
     * @throws IllegalStateException 如果转换无效
     */
    public static LoopState transition(LoopState currentState, LoopState targetState) {
        if (!isValidTransition(currentState, targetState)) {
            throw new IllegalStateException(
                String.format("Invalid state transition from %s to %s", currentState, targetState));
        }
        return targetState;
    }
    
    /**
     * 获取允许的下一个状态
     * 
     * @param currentState 当前状态
     * @return 允许的状态集合
     */
    public static Set<LoopState> getAllowedTransitions(LoopState currentState) {
        Set<LoopState> allowed = TRANSITIONS.get(currentState);
        return allowed != null ? allowed : Utils.asSet();
    }
    
    /**
     * 检查是否可以暂停
     * 
     * @param currentState 当前状态
     * @return 是否可以暂停
     */
    public static boolean canPause(LoopState currentState) {
        return currentState.isActive();
    }
    
    /**
     * 检查是否可以恢复
     * 
     * @param currentState 当前状态
     * @return 是否可以恢复
     */
    public static boolean canResume(LoopState currentState) {
        return currentState == LoopState.PAUSED;
    }
    
    /**
     * 检查是否可以停止
     * 
     * @param currentState 当前状态
     * @return 是否可以停止
     */
    public static boolean canStop(LoopState currentState) {
        return currentState.isActive() || currentState == LoopState.PAUSED;
    }
    
    /**
     * 检查是否为终态
     * 
     * @param currentState 当前状态
     * @return 是否为终态
     */
    public static boolean isTerminal(LoopState currentState) {
        return currentState.isTerminal();
    }
    
    /**
     * 检查是否为活跃状态
     * 
     * @param currentState 当前状态
     * @return 是否为活跃状态
     */
    public static boolean isActive(LoopState currentState) {
        return currentState.isActive();
    }
}