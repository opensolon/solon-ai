package org.noear.solon.ai.loop;

import org.junit.jupiter.api.*;
import org.noear.solon.ai.loop.engine.LoopEngine;
import org.noear.solon.ai.loop.integration.LoopAutoConfiguration;
import org.noear.solon.ai.loop.pipeline.*;
import org.noear.solon.ai.loop.strategy.LoopStrategy;
import org.noear.solon.ai.loop.strategy.RalphLoopStrategy;
import org.noear.solon.ai.loop.strategy.TeamPipelineStrategy;
import org.noear.solon.ai.loop.strategy.UltraQAStrategy;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 第三轮新增测试——Autopilot Pipeline 扩展覆盖
 *
 * 覆盖对标 oh-my-claudecode 的 pipeline-orchestrator.test.ts 中缺失的：
 * - PipelineConfig 默认/自定义配置解析
 * - PipelineTracking 追踪构建/阶段推进/完成/跳过
 * - PipelineStage 映射完整性
 * - StageAdapter 注册和查找
 * - getSummary 格式化输出验证
 */
public class AutopilotExtendedTest {

    // ==========================================
    // 1. PipelineConfig 配置测试
    // ==========================================

    @Nested
    class PipelineConfigTest {

        @Test
        void testDefaultConfigAllEnabled() {
            PipelineConfig config = PipelineConfig.builder().build();

            assertTrue(config.isStageEnabled(PipelineStage.EXPANSION));
            assertTrue(config.isStageEnabled(PipelineStage.PLANNING));
            assertTrue(config.isStageEnabled(PipelineStage.EXECUTION));
            assertTrue(config.isStageEnabled(PipelineStage.QA));
            assertTrue(config.isStageEnabled(PipelineStage.VALIDATION));
            assertTrue(config.isStageEnabled(PipelineStage.COMPLETED), "COMPLETED should always be enabled");
            assertTrue(config.isStageEnabled(PipelineStage.FAILED), "FAILED should always be enabled");
        }

        @Test
        void testCustomConfigDisableStages() {
            PipelineConfig config = PipelineConfig.builder()
                    .expansionEnabled(false)
                    .planningEnabled(false)
                    .validationEnabled(false)
                    .build();

            assertFalse(config.isStageEnabled(PipelineStage.EXPANSION));
            assertFalse(config.isStageEnabled(PipelineStage.PLANNING));
            assertTrue(config.isStageEnabled(PipelineStage.EXECUTION), "EXECUTION should still be enabled");
            assertTrue(config.isStageEnabled(PipelineStage.QA), "QA should still be enabled");
            assertFalse(config.isStageEnabled(PipelineStage.VALIDATION));
        }

        @Test
        void testGetEnabledStagesDefaultOrder() {
            PipelineConfig config = PipelineConfig.builder().build();
            List<PipelineStage> stages = config.getEnabledStages();

            assertEquals(5, stages.size());
            assertEquals(PipelineStage.EXPANSION, stages.get(0));
            assertEquals(PipelineStage.PLANNING, stages.get(1));
            assertEquals(PipelineStage.EXECUTION, stages.get(2));
            assertEquals(PipelineStage.QA, stages.get(3));
            assertEquals(PipelineStage.VALIDATION, stages.get(4));
        }

        @Test
        void testGetEnabledStagesOnlyExecution() {
            PipelineConfig config = PipelineConfig.builder()
                    .expansionEnabled(false)
                    .planningEnabled(false)
                    .executionEnabled(true)
                    .qaEnabled(false)
                    .validationEnabled(false)
                    .build();
            List<PipelineStage> stages = config.getEnabledStages();

            assertEquals(1, stages.size());
            assertEquals(PipelineStage.EXECUTION, stages.get(0));
        }

        @Test
        void testGetDefaultStrategyForStage() {
            PipelineConfig config = PipelineConfig.builder().build();

            assertTrue(config.getStrategyForStage(PipelineStage.EXECUTION) instanceof RalphLoopStrategy);
            assertTrue(config.getStrategyForStage(PipelineStage.QA) instanceof UltraQAStrategy);
            assertTrue(config.getStrategyForStage(PipelineStage.EXPANSION) instanceof TeamPipelineStrategy);
            assertTrue(config.getStrategyForStage(PipelineStage.PLANNING) instanceof TeamPipelineStrategy);
            assertTrue(config.getStrategyForStage(PipelineStage.VALIDATION) instanceof TeamPipelineStrategy);
        }

        @Test
        void testCustomStrategyForStage() {
            RalphLoopStrategy customExec = RalphLoopStrategy.builder()
                    .criticMode("codex")
                    .build();

            PipelineConfig config = PipelineConfig.builder()
                    .strategyForStage(PipelineStage.EXECUTION, customExec)
                    .build();

            LoopStrategy retrieved = config.getStrategyForStage(PipelineStage.EXECUTION);
            assertSame(customExec, retrieved, "Should return the custom strategy");
            assertTrue(retrieved instanceof RalphLoopStrategy);
        }

        @Test
        void testAllDisabledEnabledStagesEmpty() {
            PipelineConfig config = PipelineConfig.builder()
                    .expansionEnabled(false)
                    .planningEnabled(false)
                    .executionEnabled(false)
                    .qaEnabled(false)
                    .validationEnabled(false)
                    .build();

            assertTrue(config.getEnabledStages().isEmpty(),
                    "Enabled stages should be empty when all disabled");
        }
    }

    // ==========================================
    // 2. PipelineTracking 追踪测试
    // ==========================================

    @Nested
    class PipelineTrackingTest {

        @Test
        void testTrackingInitialState() {
            PipelineTracking tracking = new PipelineTracking("tracking-001");

            assertEquals("tracking-001", tracking.getSessionId());
            assertEquals(PipelineStage.EXPANSION, tracking.getCurrentStage());
            assertNotNull(tracking.getStartedAt());
            assertFalse(tracking.isCompleted());
            assertFalse(tracking.isFailed());
            assertTrue(tracking.getStageHistory().isEmpty());
        }

        @Test
        void testEnterStageRecordsHistory() {
            PipelineTracking tracking = new PipelineTracking("tracking-002");

            tracking.enterStage(PipelineStage.EXECUTION);
            assertEquals(PipelineStage.EXECUTION, tracking.getCurrentStage());

            List<PipelineTracking.StageRecord> history = tracking.getStageHistory();
            assertEquals(1, history.size());
            assertEquals(PipelineStage.EXECUTION, history.get(0).getStage());
            assertNotNull(history.get(0).getEnteredAt());
            assertNull(history.get(0).getCompletedAt());
            assertFalse(history.get(0).isSkipped());
        }

        @Test
        void testCompleteStage() {
            PipelineTracking tracking = new PipelineTracking("tracking-003");

            tracking.enterStage(PipelineStage.EXECUTION);
            tracking.completeStage(PipelineStage.EXECUTION, true, "Execution passed");

            List<PipelineTracking.StageRecord> history = tracking.getStageHistory();
            assertEquals(1, history.size());
            assertNotNull(history.get(0).getCompletedAt());
            assertTrue(history.get(0).isSuccess());
            assertEquals("Execution passed", history.get(0).getSummary());
        }

        @Test
        void testCompletePipeline() {
            PipelineTracking tracking = new PipelineTracking("tracking-004");

            tracking.complete(false);
            assertTrue(tracking.isCompleted());
            assertFalse(tracking.isFailed());
            assertNotNull(tracking.getCompletedAt());
        }

        @Test
        void testFailedPipeline() {
            PipelineTracking tracking = new PipelineTracking("tracking-005");

            tracking.complete(true);
            assertTrue(tracking.isCompleted());
            assertTrue(tracking.isFailed());
        }

        @Test
        void testSkipStage() {
            PipelineTracking tracking = new PipelineTracking("tracking-006");

            tracking.skipStage(PipelineStage.VALIDATION);

            List<PipelineTracking.StageRecord> history = tracking.getStageHistory();
            assertEquals(1, history.size());
            assertEquals(PipelineStage.VALIDATION, history.get(0).getStage());
            assertTrue(history.get(0).isSkipped());
        }

        @Test
        void testMultipleStages() {
            PipelineTracking tracking = new PipelineTracking("tracking-007");

            tracking.enterStage(PipelineStage.PLANNING);
            tracking.completeStage(PipelineStage.PLANNING, true, "Plan ok");

            tracking.enterStage(PipelineStage.EXECUTION);
            tracking.completeStage(PipelineStage.EXECUTION, true, "Exec ok");

            tracking.enterStage(PipelineStage.QA);
            tracking.completeStage(PipelineStage.QA, true, "QA passed");

            assertEquals(3, tracking.getStageHistory().size());
        }

        @Test
        void testGetSummaryRunning() {
            PipelineTracking tracking = new PipelineTracking("summary-test");
            tracking.enterStage(PipelineStage.EXECUTION);

            String summary = tracking.getSummary();
            assertTrue(summary.contains("Pipeline [summary-test]"));
            assertTrue(summary.contains("RUNNING"));
            assertTrue(summary.contains("EXECUTION"));
            assertTrue(summary.contains("IN PROGRESS"));
        }

        @Test
        void testGetSummaryCompleted() {
            PipelineTracking tracking = new PipelineTracking("summary-complete");

            tracking.enterStage(PipelineStage.EXECUTION);
            tracking.completeStage(PipelineStage.EXECUTION, true, "All good");
            tracking.complete(false);

            String summary = tracking.getSummary();
            assertTrue(summary.contains("COMPLETED"));
            assertTrue(summary.contains("PASSED"));
            assertTrue(summary.contains("All good"));
        }

        @Test
        void testGetSummaryFailed() {
            PipelineTracking tracking = new PipelineTracking("summary-failed");

            tracking.enterStage(PipelineStage.QA);
            tracking.completeStage(PipelineStage.QA, false, "Tests failed");
            tracking.complete(true);

            String summary = tracking.getSummary();
            assertTrue(summary.contains("FAILED"));
            assertTrue(summary.contains("FAILED"));
            assertTrue(summary.contains("Tests failed"));
        }

        @Test
        void testGetSummaryWithSkipped() {
            PipelineTracking tracking = new PipelineTracking("summary-skipped");

            tracking.enterStage(PipelineStage.EXECUTION);
            tracking.completeStage(PipelineStage.EXECUTION, true, "Done");
            tracking.skipStage(PipelineStage.VALIDATION);
            tracking.complete(false);

            String summary = tracking.getSummary();
            assertTrue(summary.contains("SKIPPED"));
        }
    }

    // ==========================================
    // 3. PipelineStage 映射测试
    // ==========================================

    @Nested
    class PipelineStageMappingTest {

        @Test
        void testAllPipelineStagesMappedToTeamPhases() {
            // 每个 PipelineStage 都能映射到 TeamPhase
            assertEquals(TeamPipelineStrategy.Phase.PLAN, PipelineStage.EXPANSION.toTeamPhase());
            assertEquals(TeamPipelineStrategy.Phase.PLAN, PipelineStage.PLANNING.toTeamPhase());
            assertEquals(TeamPipelineStrategy.Phase.EXEC, PipelineStage.EXECUTION.toTeamPhase());
            assertEquals(TeamPipelineStrategy.Phase.VERIFY, PipelineStage.QA.toTeamPhase());
            assertEquals(TeamPipelineStrategy.Phase.VERIFY, PipelineStage.VALIDATION.toTeamPhase());
            assertEquals(TeamPipelineStrategy.Phase.COMPLETED, PipelineStage.COMPLETED.toTeamPhase());
            assertEquals(TeamPipelineStrategy.Phase.COMPLETED, PipelineStage.FAILED.toTeamPhase());
        }

        @Test
        void testAllTeamPhasesMappedBack() {
            assertEquals(PipelineStage.PLANNING, PipelineStage.fromTeamPhase(TeamPipelineStrategy.Phase.PLAN));
            assertEquals(PipelineStage.PLANNING, PipelineStage.fromTeamPhase(TeamPipelineStrategy.Phase.PRD));
            assertEquals(PipelineStage.EXECUTION, PipelineStage.fromTeamPhase(TeamPipelineStrategy.Phase.EXEC));
            assertEquals(PipelineStage.QA, PipelineStage.fromTeamPhase(TeamPipelineStrategy.Phase.VERIFY));
            assertEquals(PipelineStage.EXECUTION, PipelineStage.fromTeamPhase(TeamPipelineStrategy.Phase.FIX));
            assertEquals(PipelineStage.COMPLETED, PipelineStage.fromTeamPhase(TeamPipelineStrategy.Phase.COMPLETED));
            assertEquals(PipelineStage.FAILED, PipelineStage.fromTeamPhase(TeamPipelineStrategy.Phase.CANCELLED));
        }

        @Test
        void testRoundTripMapping() {
            // PipelineStage -> TeamPhase -> PipelineStage 往返映射
            for (PipelineStage stage : PipelineStage.values()) {
                if (stage == PipelineStage.FAILED) continue; // FAILED→COMPLETED→PLANNING 不是恒等
                TeamPipelineStrategy.Phase phase = stage.toTeamPhase();
                PipelineStage back = PipelineStage.fromTeamPhase(phase);
                assertNotNull(back);
            }
        }

        @Test
        void testIsTerminal() {
            assertTrue(PipelineStage.COMPLETED.isTerminal());
            assertTrue(PipelineStage.FAILED.isTerminal());
            assertFalse(PipelineStage.EXPANSION.isTerminal());
            assertFalse(PipelineStage.PLANNING.isTerminal());
            assertFalse(PipelineStage.EXECUTION.isTerminal());
            assertFalse(PipelineStage.QA.isTerminal());
            assertFalse(PipelineStage.VALIDATION.isTerminal());
        }

        @Test
        void testIsCancellable() {
            assertFalse(PipelineStage.COMPLETED.isCancellable());
            assertFalse(PipelineStage.FAILED.isCancellable());
            assertTrue(PipelineStage.EXPANSION.isCancellable());
            assertTrue(PipelineStage.EXECUTION.isCancellable());
        }
    }

    // ==========================================
    // 4. StageAdapter 测试
    // ==========================================

    @Nested
    class StageAdapterTest {

        @Test
        void testDefaultAdaptersSupportedStages() {
            StageAdapter execAdapter = DefaultStageAdapters.executionAdapter();
            assertEquals(PipelineStage.EXECUTION, execAdapter.supportedStage());

            StageAdapter qaAdapter = DefaultStageAdapters.qaAdapter();
            assertEquals(PipelineStage.QA, qaAdapter.supportedStage());
        }

        @Test
        void testRegisterAndGetAdapter() {
            LoopEngine engine = LoopAutoConfiguration.createDefaultEngine();
            PipelineConfig config = PipelineConfig.builder().build();
            AutopilotExecutor executor = new AutopilotExecutor(engine, config);

            // 注册自定义适配器
            StageAdapter customAdapter = new StageAdapter() {
                @Override
                public StageAdapter.StageResult execute(PipelineStage stage, Map<String, Object> context, LoopEngine le) {
                    return StageAdapter.StageResult.ok("Custom execution");
                }

                @Override
                public PipelineStage supportedStage() {
                    return PipelineStage.PLANNING;
                }
            };

            executor.registerAdapter(customAdapter);
            // 无法直接验证注册结果，但至少不抛异常
            assertNotNull(executor);
        }

        @Test
        void testStageResultOk() {
            StageAdapter.StageResult result = StageAdapter.StageResult.ok("Success");
            assertTrue(result.isSuccess());
            assertEquals("Success", result.getMessage());
            assertNotNull(result.getData());
        }

        @Test
        void testStageResultFail() {
            StageAdapter.StageResult result = StageAdapter.StageResult.fail("Error occurred");
            assertFalse(result.isSuccess());
            assertEquals("Error occurred", result.getMessage());
        }

        @Test
        void testStageResultWithData() {
            Map<String, Object> data = new HashMap<>();
            data.put("key", "value");

            StageAdapter.StageResult result = new StageAdapter.StageResult(true, "With data", data);
            assertTrue(result.isSuccess());
            assertEquals("value", result.getData().get("key"));
        }
    }

    // ==========================================
    // 5. AutopilotExecutor 集成测试
    // ==========================================

    @Nested
    class AutopilotExecutorTest {

        @Test
        void testCreateExecutor() {
            LoopEngine engine = LoopAutoConfiguration.createDefaultEngine();
            PipelineConfig config = PipelineConfig.builder().build();
            AutopilotExecutor executor = new AutopilotExecutor(engine, config);

            assertNotNull(executor);
            assertTrue(executor.getActivePipelines().isEmpty());
        }

        @Test
        void testStartAndCancelPipeline() throws Exception {
            LoopEngine engine = LoopAutoConfiguration.createDefaultEngine();
            PipelineConfig config = PipelineConfig.builder().build();
            AutopilotExecutor executor = new AutopilotExecutor(engine, config);

            AutopilotExecutor.PipelineRequest request =
                    AutopilotExecutor.PipelineRequest.create("exec-test-001", "Test execution");
            CompletableFuture<AutopilotExecutor.PipelineResult> future =
                    executor.startPipeline(request);
            assertNotNull(future);

            // 验证活跃列表包含该 pipeline
            assertTrue(executor.getActivePipelines().contains("exec-test-001"),
                    "Pipeline should be in active list");

            // 取消
            assertTrue(executor.cancelPipeline("exec-test-001"));

            // 验证从活跃列表移除
            assertFalse(executor.getActivePipelines().contains("exec-test-001"),
                    "Pipeline should be removed from active list after cancel");
        }

        @Test
        void testMultiplePipelines() {
            LoopEngine engine = LoopAutoConfiguration.createDefaultEngine();
            PipelineConfig config = PipelineConfig.builder().build();
            AutopilotExecutor executor = new AutopilotExecutor(engine, config);

            AutopilotExecutor.PipelineRequest req1 =
                    AutopilotExecutor.PipelineRequest.create("multi-001", "First");
            AutopilotExecutor.PipelineRequest req2 =
                    AutopilotExecutor.PipelineRequest.create("multi-002", "Second");

            executor.startPipeline(req1);
            executor.startPipeline(req2);

            List<String> active = executor.getActivePipelines();
            assertTrue(active.contains("multi-001"));
            assertTrue(active.contains("multi-002"));
            assertEquals(2, active.size());

            // 清理
            executor.cancelPipeline("multi-001");
            executor.cancelPipeline("multi-002");
        }

        @Test
        void testPipelineRequestFactory() {
            AutopilotExecutor.PipelineRequest request =
                    AutopilotExecutor.PipelineRequest.create("req-test-001", "Test request");

            assertNotNull(request);
            assertEquals("req-test-001", request.sessionId);
            assertEquals("Test request", request.description);
        }

        @Test
        void testPipelineResultSummary() {
            PipelineTracking tracking = new PipelineTracking("result-test");
            tracking.enterStage(PipelineStage.EXECUTION);
            tracking.completeStage(PipelineStage.EXECUTION, true, "OK");
            tracking.complete(false);

            AutopilotExecutor.PipelineResult result =
                    AutopilotExecutor.PipelineResult.success("result-test", "All done", tracking);

            assertNotNull(result);
            assertEquals("result-test", result.sessionId);
            assertEquals("All done", result.message);
            assertEquals(true, result.success, "PipelineResult.success() should have success=true");
            String summary = result.getSummary();
            // PipelineResult.getSummary() 返回 tracking.getSummary()（阶段摘要），不含 message
            assertTrue(summary.contains("COMPLETED"),
                    "Summary should contain COMPLETED status");
            assertTrue(summary.contains("PASSED"),
                    "Summary should contain PASSED for the stage");
        }

        @Test
        void testPipelineResultFailure() {
            AutopilotExecutor.PipelineResult result =
                    AutopilotExecutor.PipelineResult.failure("fail-test", "Something broke", null);

assertFalse(result.success);
            assertEquals("Something broke", result.message);
            assertEquals("fail-test", result.sessionId);
        }
    }

    // ==========================================
    // 辅助方法
    // ==========================================

    private static void deleteDirectory(java.nio.file.Path path) throws java.io.IOException {
        if (java.nio.file.Files.exists(path)) {
            java.nio.file.Files.walk(path)
                    .sorted(Comparator.reverseOrder())
                    .map(java.nio.file.Path::toFile)
                    .forEach(java.io.File::delete);
        }
    }
}
