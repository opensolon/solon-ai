package org.noear.solon.ai.loop.example;

import org.noear.solon.ai.loop.config.LoopConfig;
import org.noear.solon.ai.loop.config.LoopEngineConfig;
import org.noear.solon.ai.loop.engine.LoopEngine;
import org.noear.solon.ai.loop.engine.LoopResult;
import org.noear.solon.ai.loop.engine.LoopSession;
import org.noear.solon.ai.loop.engine.SimpleLoopEngine;
import org.noear.solon.ai.loop.strategy.RalphLoopStrategy;
import org.noear.solon.ai.loop.strategy.TeamPipelineStrategy;
import org.noear.solon.ai.loop.strategy.UltraQAStrategy;
import org.noear.solon.ai.loop.validator.*;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * 循环引擎使用示例
 * 
 * @author noear
 * @since 4.0.3
 */
public class LoopEngineExample {
    
    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Solon AI Loop Engine Example ===\n");
        
        // 1. 创建循环引擎
        LoopEngineConfig config = LoopEngineConfig.builder()
                .monitoringEnabled(true)
                .debuggingEnabled(true)
                .build();
        
        LoopEngine engine = new SimpleLoopEngine(config);
        
        // 2. 示例1: Ralph循环策略
        System.out.println("Example 1: Ralph Loop Strategy");
        System.out.println("------------------------------");
        exampleRalphLoop(engine);
        
        System.out.println("\n");
        
        // 3. 示例2: Team Pipeline策略
        System.out.println("Example 2: Team Pipeline Strategy");
        System.out.println("-------------------------------");
        exampleTeamPipeline(engine);
        
        System.out.println("\n");
        
        // 4. 示例3: UltraQA策略
        System.out.println("Example 3: UltraQA Strategy");
        System.out.println("---------------------------");
        exampleUltraQA(engine);
    }
    
    /**
     * Ralph循环策略示例
     */
    private static void exampleRalphLoop(LoopEngine engine) throws InterruptedException {
        // 创建Ralph循环策略
        RalphLoopStrategy strategy = RalphLoopStrategy.builder()
                .verificationRequired(true)
                .criticMode("architect")
                .maxIterations(10)
                .build();
        
        // 创建简单的验证器
        Validator validator = new Validator() {
            @Override
            public ValidationResult validate(Object result, ValidationCriteria criteria) {
                // 简单验证逻辑
                if (result != null && result.toString().contains("success")) {
                    return ValidationResult.passed("Validation passed");
                }
                return ValidationResult.failed("Validation failed", "Result does not contain success");
            }
            
            @Override
            public ValidationResult validateQualityGate(QualityGate gate, Object result) {
                return validate(result, null);
            }
            
            @Override
            public ValidationResult validateIteration(Object iterationResult, ValidationContext context) {
                return validate(iterationResult, null);
            }
        };
        
        // 创建循环配置
        LoopConfig config = LoopConfig.builder()
                .taskDescription("Implement user authentication feature")
                .strategy(strategy)
                .validator(validator)
                .maxIterations(10)
                .verificationRequired(true)
                .statePersistenceEnabled(true)
                .build();
        
        // 启动循环
        LoopSession session = engine.start(config);
        
        // 监听状态变化
        session.onStateChange(state -> {
            System.out.println("State changed to: " + state);
        });
        
        // 监听迭代完成
        session.onIterationComplete(result -> {
            System.out.println("Iteration " + result.getNumber() + " completed: " + 
                             (result.isSuccess() ? "SUCCESS" : "FAILED"));
        });
        
        // 等待完成
        session.waitForCompletion(Duration.ofSeconds(30));
        
        // 获取结果
        LoopResult result = session.getResult();
        if (result != null) {
            System.out.println("\nLoop Result:");
            System.out.println("  Session ID: " + result.getSessionId());
            System.out.println("  Final State: " + result.getFinalState());
            System.out.println("  Success: " + result.isSuccess());
            System.out.println("  Total Iterations: " + result.getTotalIterations());
            System.out.println("  Success Rate: " + String.format("%.2f%%", result.getSuccessRate() * 100));
            System.out.println("  Total Duration: " + result.getTotalDuration());
        }
    }
    
    /**
     * Team Pipeline策略示例
     */
    private static void exampleTeamPipeline(LoopEngine engine) throws InterruptedException {
        // 创建Team Pipeline策略
        TeamPipelineStrategy strategy = TeamPipelineStrategy.builder()
                .phases(Arrays.asList(
                    TeamPipelineStrategy.Phase.PLAN,
                    TeamPipelineStrategy.Phase.PRD,
                    TeamPipelineStrategy.Phase.EXEC,
                    TeamPipelineStrategy.Phase.VERIFY,
                    TeamPipelineStrategy.Phase.FIX
                ))
                .maxFixAttempts(3)
                .parallelExecution(false)
                .build();
        
        // 创建验证器
        Validator validator = createSimpleValidator();
        
        // 创建循环配置
        LoopConfig config = LoopConfig.builder()
                .taskDescription("Build REST API service")
                .strategy(strategy)
                .validator(validator)
                .maxIterations(20)
                .verificationRequired(true)
                .statePersistenceEnabled(true)
                .build();
        
        // 启动循环
        LoopSession session = engine.start(config);
        
        // 等待完成
        session.waitForCompletion(Duration.ofSeconds(30));
        
        // 获取结果
        LoopResult result = session.getResult();
        if (result != null) {
            System.out.println("\nTeam Pipeline Result:");
            System.out.println("  Session ID: " + result.getSessionId());
            System.out.println("  Final State: " + result.getFinalState());
            System.out.println("  Success: " + result.isSuccess());
            System.out.println("  Total Iterations: " + result.getTotalIterations());
        }
    }
    
    /**
     * UltraQA策略示例
     */
    private static void exampleUltraQA(LoopEngine engine) throws InterruptedException {
        // 创建UltraQA策略
        UltraQAStrategy strategy = UltraQAStrategy.builder()
                .parallelTesting(false)
                .maxTestAttempts(5)
                .build();
        
        // 创建验证器
        Validator validator = createSimpleValidator();
        
        // 创建循环配置
        LoopConfig config = LoopConfig.builder()
                .taskDescription("Quality assurance testing")
                .strategy(strategy)
                .validator(validator)
                .maxIterations(15)
                .verificationRequired(true)
                .statePersistenceEnabled(true)
                .build();
        
        // 启动循环
        LoopSession session = engine.start(config);
        
        // 等待完成
        session.waitForCompletion(Duration.ofSeconds(30));
        
        // 获取结果
        LoopResult result = session.getResult();
        if (result != null) {
            System.out.println("\nUltraQA Result:");
            System.out.println("  Session ID: " + result.getSessionId());
            System.out.println("  Final State: " + result.getFinalState());
            System.out.println("  Success: " + result.isSuccess());
            System.out.println("  Total Iterations: " + result.getTotalIterations());
            System.out.println("  Success Rate: " + String.format("%.2f%%", result.getSuccessRate() * 100));
        }
    }
    
    /**
     * 创建简单的验证器
     */
    private static Validator createSimpleValidator() {
        return new Validator() {
            @Override
            public ValidationResult validate(Object result, ValidationCriteria criteria) {
                // 简单验证逻辑
                if (result != null) {
                    return ValidationResult.passed("Validation passed");
                }
                return ValidationResult.failed("Validation failed", "Result is null");
            }
            
            @Override
            public ValidationResult validateQualityGate(QualityGate gate, Object result) {
                return validate(result, null);
            }
            
            @Override
            public ValidationResult validateIteration(Object iterationResult, ValidationContext context) {
                return validate(iterationResult, null);
            }
        };
    }
}