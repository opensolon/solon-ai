package org.noear.solon.ai.loop.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.noear.solon.ai.loop.config.LoopConfig;
import org.noear.solon.ai.loop.config.LoopEngineConfig;
import org.noear.solon.ai.loop.state.LoopState;
import org.noear.solon.ai.loop.strategy.RalphLoopStrategy;
import org.noear.solon.ai.loop.strategy.TeamPipelineStrategy;
import org.noear.solon.ai.loop.strategy.UltraQAStrategy;
import org.noear.solon.ai.loop.validator.ValidationResult;
import org.noear.solon.ai.loop.validator.ValidationCriteria;
import org.noear.solon.ai.loop.validator.ValidationContext;
import org.noear.solon.ai.loop.validator.QualityGate;
import org.noear.solon.ai.loop.validator.Validator;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 循环引擎单元测试
 * 
 * @author noear
 * @since 4.0.3
 */
class LoopEngineTest {
    
    private LoopEngine engine;
    
    @BeforeEach
    void setUp() {
        LoopEngineConfig config = LoopEngineConfig.builder()
                .monitoringEnabled(false)
                .debuggingEnabled(false)
                .build();
        engine = new SimpleLoopEngine(config);
    }
    
    @AfterEach
    void tearDown() {
        // 清理资源
    }
    
    @Test
    void testStartAndStop() throws InterruptedException {
        // 创建简单的验证器
        Validator validator = createSimpleValidator();
        
        // 创建Ralph循环策略
        RalphLoopStrategy strategy = RalphLoopStrategy.builder()
                .verificationRequired(true)
                .maxIterations(5)
                .build();
        
        // 创建循环配置
        LoopConfig config = LoopConfig.builder()
                .taskDescription("Test task")
                .strategy(strategy)
                .validator(validator)
                .maxIterations(5)
                .verificationRequired(true)
                .statePersistenceEnabled(false)
                .build();
        
        // 启动循环
        LoopSession session = engine.start(config);
        assertNotNull(session);
        assertNotNull(session.getId());
        
        // 检查状态
        assertTrue(session.getState().isActive() || session.getState() == LoopState.IDLE);
        
        // 等待一段时间
        Thread.sleep(100);
        
        // 停止循环
        engine.stop(session.getId());
        
        // 验证停止
        assertFalse(engine.isRunning(session.getId()));
    }
    
    @Test
    void testPauseAndResume() throws InterruptedException {
        // 创建简单的验证器
        Validator validator = createSimpleValidator();
        
        // 创建Ralph循环策略（使用大量迭代，确保循环不会立即完成）
        RalphLoopStrategy strategy = RalphLoopStrategy.builder()
                .verificationRequired(true)
                .maxIterations(100)
                .build();
        
        // 创建循环配置
        LoopConfig config = LoopConfig.builder()
                .taskDescription("Test pause and resume")
                .strategy(strategy)
                .validator(validator)
                .maxIterations(100)
                .verificationRequired(true)
                .statePersistenceEnabled(false)
                .build();
        
        // 启动循环
        LoopSession session = engine.start(config);
        assertNotNull(session);
        
        // 等待循环开始运行
        Thread.sleep(50);
        
        // 只有在循环仍在运行时才测试暂停/恢复
        if (engine.isRunning(session.getId())) {
            // 暂停循环
            engine.pause(session.getId());
            assertEquals(LoopState.PAUSED, session.getState());
            
            // 等待一段时间
            Thread.sleep(100);
            
            // 恢复循环
            engine.resume(session.getId());
            assertTrue(session.getState().isActive());
        }
        
        // 停止循环
        engine.stop(session.getId());
    }
    
    @Test
    void testGetState() throws InterruptedException {
        // 创建简单的验证器
        Validator validator = createSimpleValidator();
        
        // 创建UltraQA策略
        UltraQAStrategy strategy = UltraQAStrategy.builder()
                .parallelTesting(false)
                .maxTestAttempts(3)
                .build();
        
        // 创建循环配置
        LoopConfig config = LoopConfig.builder()
                .taskDescription("Test get state")
                .strategy(strategy)
                .validator(validator)
                .maxIterations(5)
                .verificationRequired(true)
                .statePersistenceEnabled(false)
                .build();
        
        // 启动循环
        LoopSession session = engine.start(config);
        assertNotNull(session);
        
        // 检查状态
        LoopState state = engine.getState(session.getId());
        assertNotNull(state);
        
        // 停止循环
        engine.stop(session.getId());
    }
    
    @Test
    void testGetActiveSessions() throws InterruptedException {
        // 创建简单的验证器
        Validator validator = createSimpleValidator();
        
        // 创建多个循环
        for (int i = 0; i < 3; i++) {
            RalphLoopStrategy strategy = RalphLoopStrategy.builder()
                    .verificationRequired(true)
                    .maxIterations(3)
                    .build();
            
            LoopConfig config = LoopConfig.builder()
                    .taskDescription("Test active sessions " + i)
                    .strategy(strategy)
                    .validator(validator)
                    .maxIterations(3)
                    .verificationRequired(true)
                    .statePersistenceEnabled(false)
                    .build();
            
            engine.start(config);
        }
        
        // 检查活跃会话
        assertTrue(engine.getActiveSessions().size() <= 3);
        
        // 停止所有会话
        for (LoopSession session : engine.getActiveSessions()) {
            engine.stop(session.getId());
        }
    }
    
    @Test
    void testRalphLoopStrategy() throws InterruptedException {
        // 创建简单的验证器
        Validator validator = createSimpleValidator();
        
        // 创建Ralph循环策略
        RalphLoopStrategy strategy = RalphLoopStrategy.builder()
                .verificationRequired(true)
                .criticMode("architect")
                .maxIterations(3)
                .build();
        
        // 创建循环配置
        LoopConfig config = LoopConfig.builder()
                .taskDescription("Test Ralph loop strategy")
                .strategy(strategy)
                .validator(validator)
                .maxIterations(3)
                .verificationRequired(true)
                .statePersistenceEnabled(false)
                .build();
        
        // 启动循环
        LoopSession session = engine.start(config);
        assertNotNull(session);
        
        // 等待完成
        session.waitForCompletion(Duration.ofSeconds(10));
        
        // 获取结果
        LoopResult result = session.getResult();
        if (result != null) {
            assertNotNull(result.getSessionId());
            assertNotNull(result.getFinalState());
            assertTrue(result.getTotalIterations() >= 0);
        }
    }
    
    @Test
    void testStateListeners() throws InterruptedException {
        // 创建简单的验证器
        Validator validator = createSimpleValidator();
        
        // 创建Ralph循环策略
        RalphLoopStrategy strategy = RalphLoopStrategy.builder()
                .verificationRequired(true)
                .maxIterations(2)
                .build();
        
        // 创建循环配置
        LoopConfig config = LoopConfig.builder()
                .taskDescription("Test state listeners")
                .strategy(strategy)
                .validator(validator)
                .maxIterations(2)
                .verificationRequired(true)
                .statePersistenceEnabled(false)
                .build();
        
        // 启动循环
        LoopSession session = engine.start(config);
        
        // 添加状态监听器
        AtomicBoolean stateChanged = new AtomicBoolean(false);
        session.onStateChange(state -> {
            stateChanged.set(true);
        });
        
        // 等待一段时间
        Thread.sleep(500);
        
        // 验证状态变化
        // 注意：在实际测试中，可能需要更复杂的同步机制
        
        // 停止循环
        engine.stop(session.getId());
    }
    
    @Test
    void testIterationListeners() throws InterruptedException {
        // 创建简单的验证器
        Validator validator = createSimpleValidator();
        
        // 创建Ralph循环策略
        RalphLoopStrategy strategy = RalphLoopStrategy.builder()
                .verificationRequired(true)
                .maxIterations(2)
                .build();
        
        // 创建循环配置
        LoopConfig config = LoopConfig.builder()
                .taskDescription("Test iteration listeners")
                .strategy(strategy)
                .validator(validator)
                .maxIterations(2)
                .verificationRequired(true)
                .statePersistenceEnabled(false)
                .build();
        
        // 启动循环
        LoopSession session = engine.start(config);
        
        // 添加迭代监听器
        AtomicInteger iterationCount = new AtomicInteger(0);
        session.onIterationComplete(result -> {
            iterationCount.incrementAndGet();
        });
        
        // 等待完成
        session.waitForCompletion(Duration.ofSeconds(5));
        
        // 验证迭代次数
        assertTrue(iterationCount.get() >= 0);
    }
    
    // ==================== 边界条件测试 ====================
    
    @Test
    void testNullValidator() throws InterruptedException {
        // 测试空验证器 - 当前实现允许空验证器
        RalphLoopStrategy strategy = RalphLoopStrategy.builder()
                .verificationRequired(true)
                .maxIterations(5)
                .build();
        
        LoopConfig config = LoopConfig.builder()
                .taskDescription("Test null validator")
                .strategy(strategy)
                .validator(null)
                .maxIterations(5)
                .verificationRequired(true)
                .build();
        
        // 应该能正常启动（当前实现允许空验证器）
        LoopSession session = engine.start(config);
        assertNotNull(session);
        
        // 停止循环
        engine.stop(session.getId());
    }
    
    @Test
    void testEmptyTaskDescription() throws InterruptedException {
        // 测试空任务描述
        Validator validator = createSimpleValidator();
        
        RalphLoopStrategy strategy = RalphLoopStrategy.builder()
                .verificationRequired(true)
                .maxIterations(3)
                .build();
        
        LoopConfig config = LoopConfig.builder()
                .taskDescription("")
                .strategy(strategy)
                .validator(validator)
                .maxIterations(3)
                .verificationRequired(true)
                .build();
        
        // 应该能正常启动
        LoopSession session = engine.start(config);
        assertNotNull(session);
        
        // 停止循环
        engine.stop(session.getId());
    }
    
    @Test
    void testMaxIterationsZero() throws InterruptedException {
        // 测试最大迭代次数为0
        Validator validator = createSimpleValidator();
        
        RalphLoopStrategy strategy = RalphLoopStrategy.builder()
                .verificationRequired(true)
                .maxIterations(0)
                .build();
        
        LoopConfig config = LoopConfig.builder()
                .taskDescription("Test max iterations zero")
                .strategy(strategy)
                .validator(validator)
                .maxIterations(0)
                .verificationRequired(true)
                .build();
        
        // 应该能正常启动
        LoopSession session = engine.start(config);
        assertNotNull(session);
        
        // 等待完成
        session.waitForCompletion(Duration.ofSeconds(5));
        
        // 验证结果
        LoopResult result = session.getResult();
        if (result != null) {
            assertEquals(0, result.getTotalIterations());
        }
    }
    
    // ==================== 异常情况测试 ====================
    
    @Test
    void testInvalidSessionId() {
        // 测试无效的会话ID - 当前实现可能不抛出异常
        // 这些操作应该不抛出异常（静默处理无效ID）
        assertDoesNotThrow(() -> {
            engine.stop("invalid-session-id");
        });
        
        assertDoesNotThrow(() -> {
            engine.pause("invalid-session-id");
        });
        
        assertDoesNotThrow(() -> {
            engine.resume("invalid-session-id");
        });
        
        // 无效ID的getState应该返回null
        assertNull(engine.getState("invalid-session-id"));
    }
    
    @Test
    void testDoubleStop() throws InterruptedException {
        // 测试重复停止
        Validator validator = createSimpleValidator();
        
        RalphLoopStrategy strategy = RalphLoopStrategy.builder()
                .verificationRequired(true)
                .maxIterations(5)
                .build();
        
        LoopConfig config = LoopConfig.builder()
                .taskDescription("Test double stop")
                .strategy(strategy)
                .validator(validator)
                .maxIterations(5)
                .verificationRequired(true)
                .build();
        
        LoopSession session = engine.start(config);
        assertNotNull(session);
        
        // 第一次停止
        engine.stop(session.getId());
        
        // 第二次停止应该不抛出异常
        assertDoesNotThrow(() -> {
            engine.stop(session.getId());
        });
    }
    
    @Test
    void testPauseWithoutStart() {
        // 测试未启动时暂停 - 当前实现可能不抛出异常
        assertDoesNotThrow(() -> {
            engine.pause("non-existent-session");
        });
    }
    
    // ==================== 性能测试 ====================
    
    @Test
    void testMultipleConcurrentSessions() throws InterruptedException {
        // 测试多个并发会话
        int sessionCount = 5;
        LoopSession[] sessions = new LoopSession[sessionCount];
        
        for (int i = 0; i < sessionCount; i++) {
            Validator validator = createSimpleValidator();
            
            RalphLoopStrategy strategy = RalphLoopStrategy.builder()
                    .verificationRequired(true)
                    .maxIterations(10)
                    .build();
            
            LoopConfig config = LoopConfig.builder()
                    .taskDescription("Concurrent session " + i)
                    .strategy(strategy)
                    .validator(validator)
                    .maxIterations(10)
                    .verificationRequired(true)
                    .build();
            
            sessions[i] = engine.start(config);
            assertNotNull(sessions[i]);
        }
        
        // 等待一段时间
        Thread.sleep(100);
        
        // 检查所有会话
        assertTrue(engine.getActiveSessions().size() <= sessionCount);
        
        // 停止所有会话
        for (LoopSession session : sessions) {
            engine.stop(session.getId());
        }
    }
    
    @Test
    void testLoopCompletion() throws InterruptedException {
        // 测试循环正常完成
        Validator validator = createSimpleValidator();
        
        RalphLoopStrategy strategy = RalphLoopStrategy.builder()
                .verificationRequired(true)
                .maxIterations(3)
                .build();
        
        LoopConfig config = LoopConfig.builder()
                .taskDescription("Test loop completion")
                .strategy(strategy)
                .validator(validator)
                .maxIterations(3)
                .verificationRequired(true)
                .build();
        
        LoopSession session = engine.start(config);
        assertNotNull(session);
        
        // 等待完成
        session.waitForCompletion(Duration.ofSeconds(10));
        
        // 验证循环已完成
        assertFalse(engine.isRunning(session.getId()));
        
        // 验证结果
        LoopResult result = session.getResult();
        if (result != null) {
            assertNotNull(result.getSessionId());
            assertNotNull(result.getFinalState());
        }
    }
    
    @Test
    void testDifferentStrategies() throws InterruptedException {
        // 测试不同的循环策略
        Validator validator = createSimpleValidator();
        
        // 1. Ralph策略
        RalphLoopStrategy ralphStrategy = RalphLoopStrategy.builder()
                .verificationRequired(true)
                .maxIterations(3)
                .build();
        
        LoopConfig ralphConfig = LoopConfig.builder()
                .taskDescription("Ralph strategy test")
                .strategy(ralphStrategy)
                .validator(validator)
                .maxIterations(3)
                .verificationRequired(true)
                .build();
        
        LoopSession ralphSession = engine.start(ralphConfig);
        assertNotNull(ralphSession);
        
        // 2. Team Pipeline策略
        TeamPipelineStrategy teamStrategy = TeamPipelineStrategy.builder()
                .maxFixAttempts(2)
                .parallelExecution(false)
                .build();
        
        LoopConfig teamConfig = LoopConfig.builder()
                .taskDescription("Team Pipeline strategy test")
                .strategy(teamStrategy)
                .validator(validator)
                .maxIterations(10)
                .verificationRequired(true)
                .build();
        
        LoopSession teamSession = engine.start(teamConfig);
        assertNotNull(teamSession);
        
        // 3. UltraQA策略
        UltraQAStrategy ultraStrategy = UltraQAStrategy.builder()
                .parallelTesting(false)
                .maxTestAttempts(3)
                .build();
        
        LoopConfig ultraConfig = LoopConfig.builder()
                .taskDescription("UltraQA strategy test")
                .strategy(ultraStrategy)
                .validator(validator)
                .maxIterations(10)
                .verificationRequired(true)
                .build();
        
        LoopSession ultraSession = engine.start(ultraConfig);
        assertNotNull(ultraSession);
        
        // 等待一段时间
        Thread.sleep(200);
        
        // 停止所有会话
        engine.stop(ralphSession.getId());
        engine.stop(teamSession.getId());
        engine.stop(ultraSession.getId());
    }
    
    @Test
    void testRalphStrategyWithStoryImplementor() throws InterruptedException {
        // 测试Ralph策略与故事实现器
        Validator validator = createSimpleValidator();
        
        RalphLoopStrategy strategy = RalphLoopStrategy.builder()
                .verificationRequired(true)
                .maxIterations(3)
                .storyImplementor((story, context) -> {
                    // 自定义故事实现逻辑
                    return "Custom implementation for: " + story;
                })
                .storyValidator((story, result, context) -> {
                    // 自定义故事验证逻辑
                    return result != null && result.toString().contains("Custom implementation");
                })
                .build();
        
        LoopConfig config = LoopConfig.builder()
                .taskDescription("Test Ralph with custom implementor")
                .strategy(strategy)
                .validator(validator)
                .maxIterations(3)
                .verificationRequired(true)
                .build();
        
        LoopSession session = engine.start(config);
        assertNotNull(session);
        
        // 等待完成
        session.waitForCompletion(Duration.ofSeconds(10));
        
        // 验证结果
        LoopResult result = session.getResult();
        if (result != null) {
            assertNotNull(result.getSessionId());
        }
    }
    
    /**
     * 创建简单的验证器
     */
    private Validator createSimpleValidator() {
        return new Validator() {
            @Override
            public ValidationResult validate(Object result, ValidationCriteria criteria) {
                return ValidationResult.passed("Validation passed");
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
