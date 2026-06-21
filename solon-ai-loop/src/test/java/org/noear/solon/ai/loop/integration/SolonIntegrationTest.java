package org.noear.solon.ai.loop.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.noear.solon.ai.loop.config.LoopConfig;
import org.noear.solon.ai.loop.config.LoopEngineConfig;
import org.noear.solon.ai.loop.engine.LoopEngine;
import org.noear.solon.ai.loop.engine.LoopResult;
import org.noear.solon.ai.loop.engine.LoopSession;
import org.noear.solon.ai.loop.engine.SimpleLoopEngine;
import org.noear.solon.ai.loop.strategy.LoopContext;
import org.noear.solon.ai.loop.strategy.RalphLoopStrategy;
import org.noear.solon.ai.loop.strategy.TeamPipelineStrategy;
import org.noear.solon.ai.loop.strategy.UltraQAStrategy;
import org.noear.solon.ai.loop.validator.ValidationResult;
import org.noear.solon.ai.loop.validator.ValidationCriteria;
import org.noear.solon.ai.loop.validator.ValidationContext;
import org.noear.solon.ai.loop.validator.QualityGate;
import org.noear.solon.ai.loop.validator.Validator;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Solon框架集成测试
 *
 * <p>验证 solon-ai-loop 各集成模块与引擎的协同工作。</p>
 *
 * @author noear
 * @since 4.0.3
 */
class SolonIntegrationTest {

    private LoopEngine engine;
    private SolonFlowIntegration flowIntegration;
    private SolonAgentIntegration agentIntegration;
    private SolonHarnessIntegration harnessIntegration;
    private Validator simpleValidator;

    @BeforeEach
    void setUp() {
        LoopEngineConfig config = LoopEngineConfig.builder()
                .monitoringEnabled(false)
                .debuggingEnabled(false)
                .build();
        engine = new SimpleLoopEngine(config);
        
        flowIntegration = new SolonFlowIntegration(engine);
        agentIntegration = new SolonAgentIntegration(engine);
        harnessIntegration = new SolonHarnessIntegration(engine);
        
        simpleValidator = createSimpleValidator();
    }
    
    @AfterEach
    void tearDown() {
        // 在各个测试中单独清理会话
    }

    // ==========================================
    // 1. SolonFlowIntegration 测试
    // ==========================================

    @Test
    void testFlowStartAndStop() throws Exception {
        RalphLoopStrategy strategy = RalphLoopStrategy.builder()
                .verificationRequired(true)
                .maxIterations(3)
                .build();

        LoopConfig config = LoopConfig.builder()
                .taskDescription("test-flow")
                .strategy(strategy)
                .validator(simpleValidator)
                .maxIterations(3)
                .build();

        LoopSession session = flowIntegration.startFlowExecution(config);
        assertNotNull(session, "Session should not be null");
        assertTrue(engine.isRunning(session.getId()), "Session should be running after start");

        Thread.sleep(200);

        flowIntegration.pauseFlowExecution(session.getId());
        Thread.sleep(100);

        flowIntegration.resumeFlowExecution(session.getId());
        Thread.sleep(100);

        flowIntegration.stopFlowExecution(session.getId());
        assertFalse(engine.isRunning(session.getId()), "Session should not be running after stop");
    }

    @Test
    void testFlowRalphLoop() throws Exception {
        LoopSession session = flowIntegration.startFlowRalphLoop(
                "flow-1", "Test Ralph flow integration", simpleValidator);
        assertNotNull(session, "Ralph loop session should not be null");

        Thread.sleep(300);
        flowIntegration.stopFlowExecution(session.getId());
    }

    @Test
    void testFlowStatus() throws Exception {
        RalphLoopStrategy strategy = RalphLoopStrategy.builder()
                .maxIterations(2).build();

        LoopConfig config = LoopConfig.builder()
                .taskDescription("status-test")
                .strategy(strategy)
                .validator(simpleValidator)
                .maxIterations(2)
                .build();

        LoopSession session = flowIntegration.startFlowExecution(config);
        assertNotNull(session);

        Thread.sleep(100);

        SolonFlowIntegration.FlowExecutionStatus status =
                flowIntegration.getFlowExecutionStatus(session.getId());
        assertNotNull(status, "Status should not be null");

        flowIntegration.stopFlowExecution(session.getId());
    }

    // ==========================================
    // 2. SolonAgentIntegration 测试
    // ==========================================

    @Test
    void testAgentRalphLoop() throws Exception {
        LoopSession session = agentIntegration.startRalphLoop("Test Ralph agent task", simpleValidator);
        assertNotNull(session, "Ralph loop session should not be null");
        assertTrue(engine.isRunning(session.getId()), "Session should be running");

        Thread.sleep(200);

        agentIntegration.pauseAgentExecution(session.getId());
        Thread.sleep(100);

        agentIntegration.resumeAgentExecution(session.getId());
        Thread.sleep(100);

        agentIntegration.stopAgentExecution(session.getId());
        assertFalse(engine.isRunning(session.getId()), "Session should be stopped");
    }

    @Test
    void testUltraQALoop() throws Exception {
        LoopSession session = agentIntegration.startUltraQALoop("Test UltraQA agent task", simpleValidator);
        assertNotNull(session, "UltraQA loop session should not be null");
        assertTrue(engine.isRunning(session.getId()), "Session should be running");

        Thread.sleep(200);
        agentIntegration.stopAgentExecution(session.getId());
        assertFalse(engine.isRunning(session.getId()), "Session should be stopped after stop");
    }

    @Test
    void testAgentStatus() throws Exception {
        LoopSession session = agentIntegration.startRalphLoop("Status test", simpleValidator);
        assertNotNull(session);

        Thread.sleep(100);

        SolonAgentIntegration.ExecutionStatus status =
                agentIntegration.getAgentExecutionStatus(session.getId());
        assertNotNull(status, "Status should not be null");

        agentIntegration.stopAgentExecution(session.getId());
    }

    // ==========================================
    // 3. SolonHarnessIntegration 测试
    // ==========================================

    @Test
    void testToolExecution() throws Exception {
        TeamPipelineStrategy strategy = TeamPipelineStrategy.builder()
                .maxFixAttempts(2)
                .parallelExecution(false)
                .build();

        LoopConfig config = LoopConfig.builder()
                .taskDescription("Test tool task")
                .strategy(strategy)
                .validator(simpleValidator)
                .maxIterations(3)
                .build();

        LoopSession session = harnessIntegration.startToolExecution(config);
        assertNotNull(session, "Tool execution session should not be null");
        assertTrue(engine.isRunning(session.getId()), "Session should be running");

        Thread.sleep(200);

        harnessIntegration.pauseToolExecution(session.getId());
        Thread.sleep(100);

        harnessIntegration.resumeToolExecution(session.getId());
        Thread.sleep(100);

        harnessIntegration.stopToolExecution(session.getId());
        assertFalse(engine.isRunning(session.getId()), "Session should be stopped");
    }

    @Test
    void testToolUltraQALoop() throws Exception {
        LoopSession session = harnessIntegration.startToolUltraQALoop(
                "Test tool UltraQA", simpleValidator);
        assertNotNull(session, "Tool UltraQA session should not be null");

        Thread.sleep(300);
        harnessIntegration.stopToolExecution(session.getId());
    }

    @Test
    void testToolStatus() throws Exception {
        TeamPipelineStrategy strategy = TeamPipelineStrategy.builder()
                .maxFixAttempts(1).build();

        LoopConfig config = LoopConfig.builder()
                .taskDescription("tool-status-test")
                .strategy(strategy)
                .validator(simpleValidator)
                .maxIterations(2)
                .build();

        LoopSession session = harnessIntegration.startToolExecution(config);
        assertNotNull(session);

        Thread.sleep(100);

        SolonHarnessIntegration.ToolExecutionStatus status =
                harnessIntegration.getToolExecutionStatus(session.getId());
        assertNotNull(status, "Status should not be null");

        harnessIntegration.stopToolExecution(session.getId());
    }

    // ==========================================
    // 4. 多集成协同测试
    // ==========================================

    @Test
    void testMultipleIntegrations() throws Exception {
        // 同时启动 Flow + Agent + Harness 三个集成会话
        RalphLoopStrategy ralphStrategy = RalphLoopStrategy.builder().maxIterations(2).build();
        TeamPipelineStrategy teamStrategy = TeamPipelineStrategy.builder().maxFixAttempts(1).build();

        LoopSession flowSession = flowIntegration.startFlowExecution(
                LoopConfig.builder()
                        .taskDescription("Flow 1")
                        .strategy(ralphStrategy)
                        .validator(simpleValidator)
                        .maxIterations(2)
                        .build());

        LoopSession agentSession = agentIntegration.startRalphLoop("Agent 1", simpleValidator);

        LoopSession harnessSession = harnessIntegration.startToolExecution(
                LoopConfig.builder()
                        .taskDescription("Tool 1")
                        .strategy(teamStrategy)
                        .validator(simpleValidator)
                        .maxIterations(2)
                        .build());

        Thread.sleep(300);

        // 停止所有会话
        flowIntegration.stopFlowExecution(flowSession.getId());
        agentIntegration.stopAgentExecution(agentSession.getId());
        harnessIntegration.stopToolExecution(harnessSession.getId());

        // 验证所有会话都已停止
        assertFalse(engine.isRunning(flowSession.getId()));
        assertFalse(engine.isRunning(agentSession.getId()));
        assertFalse(engine.isRunning(harnessSession.getId()));
    }

    @Test
    void testNewEngineAfterStop() throws Exception {
        // 用原引擎执行
        LoopSession session = flowIntegration.startFlowExecution(
                LoopConfig.builder()
                        .taskDescription("Restart test")
                        .strategy(RalphLoopStrategy.builder().maxIterations(2).build())
                        .validator(simpleValidator)
                        .maxIterations(2)
                        .build());

        Thread.sleep(200);
        flowIntegration.stopFlowExecution(session.getId());
        Thread.sleep(100);

        // 销毁原引擎，创建新引擎
        LoopEngineConfig config = LoopEngineConfig.builder()
                .monitoringEnabled(false)
                .debuggingEnabled(false)
                .build();
        LoopEngine newEngine = new SimpleLoopEngine(config);
        SolonFlowIntegration newFlowIntegration = new SolonFlowIntegration(newEngine);

        // 验证新引擎可以正常工作
        LoopSession newSession = newFlowIntegration.startFlowExecution(
                LoopConfig.builder()
                        .taskDescription("New engine test")
                        .strategy(RalphLoopStrategy.builder().maxIterations(2).build())
                        .validator(simpleValidator)
                        .maxIterations(2)
                        .build());

        assertNotNull(newSession, "New engine should be able to start sessions");
        Thread.sleep(200);
        newFlowIntegration.stopFlowExecution(newSession.getId());
    }

    // ==========================================
    // 5. 集成层便利方法测试
    // ==========================================

    @Test
    void testAllAgentConvenienceMethods() throws Exception {
        // startRalphLoop
        LoopSession ralphSession = agentIntegration.startRalphLoop("Convenience Ralph", simpleValidator);
        assertNotNull(ralphSession);

        // startUltraQALoop
        LoopSession ultraSession = agentIntegration.startUltraQALoop("Convenience UltraQA", simpleValidator);
        assertNotNull(ultraSession);

        Thread.sleep(200);
        agentIntegration.stopAgentExecution(ralphSession.getId());
        agentIntegration.stopAgentExecution(ultraSession.getId());
    }

    @Test
    void testAllFlowConvenienceMethods() throws Exception {
        LoopSession ralphSession = flowIntegration.startFlowRalphLoop(
                "flow-convenience", "Convenience Flow Ralph", simpleValidator);
        assertNotNull(ralphSession);

        Thread.sleep(200);
        flowIntegration.stopFlowExecution(ralphSession.getId());
    }

    @Test
    void testAllHarnessConvenienceMethods() throws Exception {
        LoopSession qaSession = harnessIntegration.startToolUltraQALoop(
                "Convenience Tool QA", simpleValidator);
        assertNotNull(qaSession);

        Thread.sleep(200);
        harnessIntegration.stopToolExecution(qaSession.getId());
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
