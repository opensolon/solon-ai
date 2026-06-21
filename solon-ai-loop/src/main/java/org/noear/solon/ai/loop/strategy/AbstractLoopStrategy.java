package org.noear.solon.ai.loop.strategy;

import org.noear.solon.ai.loop.engine.IterationResult;
import org.noear.solon.ai.loop.state.LoopState;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 抽象循环策略基类
 * 提供循环策略的基础实现
 * 
 * @author noear
 * @since 4.0.3
 */
public abstract class AbstractLoopStrategy implements LoopStrategy {
    
    private final String name;
    private final String description;
    private final int maxIterations;
    private final boolean supportsParallel;
    
    protected AbstractLoopStrategy(String name, String description, int maxIterations, boolean supportsParallel) {
        this.name = name;
        this.description = description;
        this.maxIterations = maxIterations;
        this.supportsParallel = supportsParallel;
    }
    
    @Override
    public String getName() {
        return name;
    }
    
    @Override
    public String getDescription() {
        return description;
    }
    
    @Override
    public int getMaxIterations() {
        return maxIterations;
    }
    
    @Override
    public boolean supportsParallelExecution() {
        return supportsParallel;
    }
    
    /**
     * 检查是否应该继续循环
     * 
     * @param context 循环上下文
     * @return 是否应该继续
     */
    @Override
    public boolean shouldContinue(LoopContext context) {
        // 默认实现：检查是否达到最大迭代次数
        if (context.isMaxIterationsReached()) {
            return false;
        }
        
        // 检查是否为终态
        if (context.getCurrentState().isTerminal()) {
            return false;
        }
        
        // 调用子类的具体实现
        return shouldContinueInternal(context);
    }
    
    /**
     * 子类实现的继续条件检查
     * 
     * @param context 循环上下文
     * @return 是否应该继续
     */
    protected abstract boolean shouldContinueInternal(LoopContext context);
    
    /**
     * 执行一次迭代
     * 
     * @param context 循环上下文
     * @return 迭代结果
     */
    @Override
    public IterationResult executeIteration(LoopContext context) {
        Instant startTime = Instant.now();
        
        try {
            // 调用子类的具体实现
            IterationResult result = executeIterationInternal(context);
            
            Instant endTime = Instant.now();
            Duration duration = Duration.between(startTime, endTime);
            
            // 创建成功的结果
            return new IterationResult(
                context.getIterationCount() + 1,
                context.getMaxIterations(),
                LoopState.EXECUTING,
                result.getResult(),
                result.isSuccess(),
                result.getMessage(),
                duration,
                startTime,
                endTime,
                result.getMetadata()
            );
        } catch (Exception e) {
            Instant endTime = Instant.now();
            Duration duration = Duration.between(startTime, endTime);
            
            // 创建失败的结果
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("error", e.getClass().getName());
            metadata.put("errorMessage", e.getMessage());
            
            return new IterationResult(
                context.getIterationCount() + 1,
                context.getMaxIterations(),
                LoopState.FAILED,
                null,
                false,
                "Iteration failed: " + e.getMessage(),
                duration,
                startTime,
                endTime,
                metadata
            );
        }
    }
    
    /**
     * 子类实现的迭代执行逻辑
     * 
     * @param context 循环上下文
     * @return 迭代结果
     */
    protected abstract IterationResult executeIterationInternal(LoopContext context);
    
    @Override
    public String toString() {
        return "LoopStrategy{" +
                "name='" + name + '\'' +
                ", maxIterations=" + maxIterations +
                ", supportsParallel=" + supportsParallel +
                '}';
    }
}