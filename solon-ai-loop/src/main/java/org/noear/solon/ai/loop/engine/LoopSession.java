package org.noear.solon.ai.loop.engine;

import org.noear.solon.ai.loop.state.LoopState;
import org.noear.solon.ai.loop.strategy.LoopContext;
import org.noear.solon.ai.loop.validator.ValidationResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

/**
 * 循环会话接口
 * 代表一个循环执行实例
 * 
 * @author noear
 * @since 4.0.3
 */
public interface LoopSession {
    
    /**
     * 获取会话ID
     * 
     * @return 会话ID
     */
    String getId();
    
    /**
     * 获取当前状态
     * 
     * @return 循环状态
     */
    LoopState getState();
    
    /**
     * 获取循环上下文
     * 
     * @return 循环上下文
     */
    LoopContext getContext();
    
    /**
     * 检查是否应该继续循环
     * 
     * @return 是否应该继续
     */
    boolean shouldContinue();
    
    /**
     * 执行一次迭代
     * 
     * @return 迭代结果
     */
    IterationResult executeIteration();
    
    /**
     * 等待循环完成
     */
    void waitForCompletion();
    
    /**
     * 等待循环完成，带超时
     * 
     * @param timeout 超时时间
     */
    void waitForCompletion(Duration timeout);
    
    /**
     * 获取最终结果
     * 
     * @return 最终结果
     */
    LoopResult getResult();
    
    /**
     * 获取迭代历史
     * 
     * @return 迭代历史列表
     */
    List<IterationResult> getIterationHistory();
    
    /**
     * 获取验证结果
     * 
     * @return 验证结果列表
     */
    List<ValidationResult> getValidationResults();
    
    /**
     * 获取迭代次数
     * 
     * @return 迭代次数
     */
    int getIterationCount();
    
    /**
     * 获取执行时间
     * 
     * @return 执行时间
     */
    Duration getExecutionTime();
    
    /**
     * 获取成功率
     * 
     * @return 成功率 (0.0 - 1.0)
     */
    double getSuccessRate();
    
    /**
     * 获取开始时间
     * 
     * @return 开始时间
     */
    Instant getStartTime();
    
    /**
     * 获取结束时间
     * 
     * @return 结束时间，如果未结束返回null
     */
    Instant getEndTime();
    
    /**
     * 监听状态变化
     * 
     * @param listener 状态变化监听器
     */
    void onStateChange(Consumer<LoopState> listener);
    
    /**
     * 监听迭代完成
     * 
     * @param listener 迭代完成监听器
     */
    void onIterationComplete(Consumer<IterationResult> listener);
    
    /**
     * 监听验证结果
     * 
     * @param listener 验证结果监听器
     */
    void onValidationResult(Consumer<ValidationResult> listener);
    
    /**
     * 暂停循环
     */
    void pause();
    
    /**
     * 恢复循环
     */
    void resume();
    
    /**
     * 停止循环
     */
    void stop();
    
    /**
     * 进入修复循环
     */
    void enterFixLoop();
    
    /**
     * 更新状态
     * 
     * @param validation 验证结果
     */
    void updateState(ValidationResult validation);
}