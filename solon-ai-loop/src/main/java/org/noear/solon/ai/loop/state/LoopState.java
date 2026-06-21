package org.noear.solon.ai.loop.state;

/**
 * 循环状态枚举
 * 定义循环引擎的所有可能状态
 * 
 * @author noear
 * @since 4.0.3
 */
public enum LoopState {
    
    /**
     * 空闲状态
     */
    IDLE,
    
    /**
     * 规划中
     */
    PLANNING,
    
    /**
     * 执行中
     */
    EXECUTING,
    
    /**
     * 验证中
     */
    VERIFYING,
    
    /**
     * 修复中
     */
    FIXING,
    
    /**
     * 已暂停
     */
    PAUSED,
    
    /**
     * 已完成
     */
    COMPLETED,
    
    /**
     * 失败
     */
    FAILED;
    
    /**
     * 检查是否为活跃状态
     * 
     * @return 是否为活跃状态
     */
    public boolean isActive() {
        return this == PLANNING || this == EXECUTING || this == VERIFYING || this == FIXING;
    }
    
    /**
     * 检查是否为终态
     * 
     * @return 是否为终态
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
    
    /**
     * 检查是否可以暂停
     * 
     * @return 是否可以暂停
     */
    public boolean canPause() {
        return isActive();
    }
    
    /**
     * 检查是否可以恢复
     * 
     * @return 是否可以恢复
     */
    public boolean canResume() {
        return this == PAUSED;
    }
    
    /**
     * 检查是否可以停止
     * 
     * @return 是否可以停止
     */
    public boolean canStop() {
        return isActive() || this == PAUSED;
    }
    
    /**
     * 获取状态描述
     * 
     * @return 状态描述
     */
    public String getDescription() {
        switch (this) {
            case IDLE:
                return "空闲状态";
            case PLANNING:
                return "规划中";
            case EXECUTING:
                return "执行中";
            case VERIFYING:
                return "验证中";
            case FIXING:
                return "修复中";
            case PAUSED:
                return "已暂停";
            case COMPLETED:
                return "已完成";
            case FAILED:
                return "失败";
            default:
                return "未知状态";
        }
    }
}