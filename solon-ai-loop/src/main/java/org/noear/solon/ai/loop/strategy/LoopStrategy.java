package org.noear.solon.ai.loop.strategy;

import org.noear.solon.ai.loop.engine.IterationResult;

/**
 * 循环策略接口
 * 定义循环的执行策略
 * 
 * @author noear
 * @since 4.0.3
 */
public interface LoopStrategy {
    
    /**
     * 检查是否应该继续循环
     * 
     * @param context 循环上下文
     * @return 是否应该继续
     */
    boolean shouldContinue(LoopContext context);
    
    /**
     * 执行一次迭代
     * 
     * @param context 循环上下文
     * @return 迭代结果
     */
    IterationResult executeIteration(LoopContext context);
    
    /**
     * 获取策略名称
     * 
     * @return 策略名称
     */
    String getName();
    
    /**
     * 获取策略描述
     * 
     * @return 策略描述
     */
    String getDescription();
    
    /**
     * 获取最大迭代次数
     * 
     * @return 最大迭代次数
     */
    int getMaxIterations();
    
    /**
     * 检查是否支持并行执行
     * 
     * @return 是否支持并行
     */
    boolean supportsParallelExecution();
}