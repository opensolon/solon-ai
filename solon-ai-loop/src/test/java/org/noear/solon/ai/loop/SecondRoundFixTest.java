package org.noear.solon.ai.loop;

import org.junit.jupiter.api.*;
import org.noear.solon.ai.loop.engine.LoopEngine;
import org.noear.solon.ai.loop.integration.*;
import org.noear.solon.ai.loop.pipeline.*;
import org.noear.solon.ai.loop.state.*;
import org.noear.solon.ai.loop.state.disk.AtomicWrite;
import org.noear.solon.ai.loop.state.disk.DiskStateManager;
import org.noear.solon.ai.loop.strategy.*;
import org.noear.solon.ai.loop.validator.verify.VerificationState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 第二轮修复新增测试 —— 覆盖 ONode 序列化、互斥锁并发、策略层、Autopilot、集成层等修复。
 */
public class SecondRoundFixTest {

    // ==========================================
    // 1. ONode 序列化测试
    // ==========================================

    @Nested
    class ONodeSerializationTest {

        @Test
        void testContextDataAndMetadataSerialization() throws IOException {
            Path tempDir = Files.createTempDirectory("onode-test");
            try {
                DiskStateManager dsm = new DiskStateManager(tempDir.toString());

                LoopStateData state = new LoopStateData();
                state.setSessionId("test-ctx-001");
                state.setState(LoopState.EXECUTING);
                state.setIterationCount(3);

                Map<String, Object> ctx = new HashMap<>();
                ctx.put("key1", "value1");
                ctx.put("key2", 42);
                Map<String, String> nested = new HashMap<>();
                nested.put("inner", "data");
                ctx.put("nested", nested);
                state.setContextData(ctx);

                Map<String, Object> meta = new HashMap<>();
                meta.put("meta1", "mvalue1");
                state.setMetadata(meta);

                assertTrue(dsm.writeState("ralph", state, "test-ctx-001"),
                        "writeState should succeed");

                LoopStateData loaded = dsm.readState("ralph", "test-ctx-001");
                assertNotNull(loaded, "readState should return non-null");
                assertEquals("test-ctx-001", loaded.getSessionId());
                assertEquals(LoopState.EXECUTING, loaded.getState());
                assertEquals(3, loaded.getIterationCount());

                // 验证 contextData
                Map<String, Object> loadedCtx = loaded.getContextData();
                assertNotNull(loadedCtx, "contextData should be non-null");
                assertEquals("value1", loadedCtx.get("key1"));
                assertEquals(42, loadedCtx.get("key2"));

                // 验证 nested map
                assertNotNull(loadedCtx.get("nested"));
                assertTrue(loadedCtx.get("nested") instanceof Map);
                assertEquals("data", ((Map) loadedCtx.get("nested")).get("inner"));

                // 验证 metadata
                assertNotNull(loaded.getMetadata());
                assertEquals("mvalue1", loaded.getMetadata().get("meta1"));

            } finally {
                deleteDirectory(tempDir);
            }
        }

        @Test
        void testNullContextData() throws IOException {
            Path tempDir = Files.createTempDirectory("onode-null-test");
            try {
                DiskStateManager dsm = new DiskStateManager(tempDir.toString());

                LoopStateData state = new LoopStateData();
                state.setSessionId("test-null-001");
                state.setState(LoopState.PLANNING);
                // 不设 contextData、metadata

                assertTrue(dsm.writeState("ralph", state, "test-null-001"));

                LoopStateData loaded = dsm.readState("ralph", "test-null-001");
                assertNotNull(loaded);
                assertEquals("test-null-001", loaded.getSessionId());
                assertEquals(LoopState.PLANNING, loaded.getState());

            } finally {
                deleteDirectory(tempDir);
            }
        }

        @Test
        void testInstantSerialization() throws IOException {
            Path tempDir = Files.createTempDirectory("onode-instant-test");
            try {
                DiskStateManager dsm = new DiskStateManager(tempDir.toString());

                Instant now = Instant.now();
                LoopStateData state = new LoopStateData();
                state.setSessionId("test-instant-001");
                state.setState(LoopState.EXECUTING);
                state.setStartTime(now);
                state.setLastUpdateTime(now);

                assertTrue(dsm.writeState("ralph", state, "test-instant-001"));

                LoopStateData loaded = dsm.readState("ralph", "test-instant-001");
                assertNotNull(loaded);
                assertNotNull(loaded.getStartTime());
                assertEquals(now.toString(), loaded.getStartTime().toString());

            } finally {
                deleteDirectory(tempDir);
            }
        }

        @Test
        void testReadNonExistentState() throws IOException {
            Path tempDir = Files.createTempDirectory("onode-nonexist-test");
            try {
                DiskStateManager dsm = new DiskStateManager(tempDir.toString());
                LoopStateData loaded = dsm.readState("ralph", "non-existent");
                assertNull(loaded);
            } finally {
                deleteDirectory(tempDir);
            }
        }
    }

    // ==========================================
    // 2. 互斥锁测试
    // ==========================================

    @Nested
    class MutualExclusionAdvancedTest {

        @Test
        void testConcurrentAcquireAllSucceed() throws Exception {
            Path tempDir = Files.createTempDirectory("mutex-concurrent-test");
            try {
                DiskStateManager dsm = new DiskStateManager(tempDir.toString());
                MutualExclusionGuard guard = new MutualExclusionGuard(dsm);

                int threadCount = 10;
                CountDownLatch startLatch = new CountDownLatch(1);
                CountDownLatch doneLatch = new CountDownLatch(threadCount);
                AtomicInteger successCount = new AtomicInteger(0);

                for (int i = 0; i < threadCount; i++) {
                    final String sessionId = "session-" + i;
                    new Thread(() -> {
                        try {
                            startLatch.await();
                            if (guard.acquire(sessionId, "ralph")) {
                                successCount.incrementAndGet();
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            doneLatch.countDown();
                        }
                    }).start();
                }

                startLatch.countDown();
                doneLatch.await(5, TimeUnit.SECONDS);

                // 所有线程应该成功（不同的 sessionId）
                assertEquals(threadCount, successCount.get(),
                        "All threads with different sessionIds should acquire successfully");

                // 清理
                for (int i = 0; i < threadCount; i++) {
                    guard.release("session-" + i);
                }
            } finally {
                deleteDirectory(tempDir);
            }
        }

        @Test
        void testForceReleaseClearsDiskState() throws IOException {
            Path tempDir = Files.createTempDirectory("mutex-force-test");
            try {
                DiskStateManager dsm = new DiskStateManager(tempDir.toString());
                MutualExclusionGuard guard = new MutualExclusionGuard(dsm);

                // 先写入状态到磁盘
                LoopStateData state = new LoopStateData();
                state.setSessionId("test-force-001");
                state.setState(LoopState.EXECUTING);
                dsm.writeState("ralph", state, "test-force-001");

                assertTrue(dsm.hasState("ralph", "test-force-001"),
                        "Disk state should exist before forceRelease");

                guard.acquire("test-force-001", "ralph");
                assertTrue(guard.forceRelease("ralph", "test-force-001"),
                        "forceRelease should succeed");

                assertFalse(dsm.hasState("ralph", "test-force-001"),
                        "Disk state should be cleared after forceRelease");

            } finally {
                deleteDirectory(tempDir);
            }
        }

        @Test
        void testCanStartTeamWithRalphActive() throws IOException {
            Path tempDir = Files.createTempDirectory("mutex-team-test");
            try {
                DiskStateManager dsm = new DiskStateManager(tempDir.toString());
                MutualExclusionGuard guard = new MutualExclusionGuard(dsm);

                // 写入 ralph 状态到磁盘
                LoopStateData state = new LoopStateData();
                state.setSessionId("test-team-001");
                state.setState(LoopState.EXECUTING);
                dsm.writeState("ralph", state, "test-team-001");

                // canStartTeam 应检测到 ralph 活跃
                assertFalse(guard.canStartTeam("test-team-001"),
                        "canStartTeam should return false when ralph is active");

            } finally {
                deleteDirectory(tempDir);
            }
        }
    }

    // ==========================================
    // 3. 策略层测试
    // ==========================================

    @Nested
    class StrategyFixTest {

        @Test
        void testUltraQACancel() {
            UltraQAStrategy strategy = UltraQAStrategy.builder().build();
            LoopContext context = createTestContext("cancel-test");

            // 取消前 exit reason 应为 null
            strategy.cancel(context);

            // 验证退出原因为 CANCELLED
            UltraQAStrategy.UltraQAExitReason reason = strategy.getFinalExitReason(context);
            assertEquals(UltraQAStrategy.UltraQAExitReason.CANCELLED, reason,
                    "After cancel, exit reason should be CANCELLED");
        }

        @Test
        void testUltraQAReportEnvError() {
            UltraQAStrategy strategy = UltraQAStrategy.builder().build();
            LoopContext context = createTestContext("env-error-test");

            strategy.reportEnvError(context, "JVM crashed");

            // 验证退出原因为 ENV_ERROR
            UltraQAStrategy.UltraQAExitReason reason = strategy.getFinalExitReason(context);
            assertEquals(UltraQAStrategy.UltraQAExitReason.ENV_ERROR, reason,
                    "After env error, exit reason should be ENV_ERROR");
            assertNotNull(context.get("envErrorDiagnosis"));
        }

        @Test
        void testUltraQAStrictMode() {
            // strictMode 影响 execute*Check 的返回值
            // 此处验证策略对象创建和各参数设置正确
            UltraQAStrategy strategy = UltraQAStrategy.builder()
                    .strictMode(true)
                    .maxTestAttempts(3)
                    .build();

            // 验证构建参数
            assertEquals(3, strategy.getMaxTestAttempts());
            assertNotNull(strategy);
        }

        @Test
        void testRalphGetTeamPhaseDirectiveNoTeam() {
            RalphLoopStrategy strategy = new RalphLoopStrategy();
            // 没有 Team 状态时返回 null
            String directive = strategy.getTeamPhaseDirective("no-team-session");
            assertNull(directive, "getTeamPhaseDirective should return null when no team state exists");
        }

        @Test
        void testTeamCancelAndResume() {
            TeamPipelineStrategy strategy = TeamPipelineStrategy.builder().build();
            LoopContext context = createTestContext("team-cancel-resume");

            // 走合法路径：PLAN -> PRD -> EXEC
            strategy.markPhase(context, TeamPipelineStrategy.Phase.PRD, "plan to prd");
            strategy.markPhase(context, TeamPipelineStrategy.Phase.EXEC, "prd to exec");

            // 请求取消（保留恢复状态）
            strategy.requestCancel(context, true);

            // 验证取消状态
            assertTrue(Boolean.TRUE.equals(context.get("cancelRequested")),
                    "cancelRequested should be set");
            assertTrue(Boolean.TRUE.equals(context.get("preserveForResume")),
                    "preserveForResume should be set");

            // 恢复
            boolean resumed = strategy.resumeFromCancel(context);
            assertTrue(resumed, "resumeFromCancel should succeed");
            assertFalse(Boolean.TRUE.equals(context.get("cancelRequested")),
                    "cancelRequested should be cleared after resume");
        }

        @Test
        void testTeamCancelWithoutPreserve() {
            TeamPipelineStrategy strategy = TeamPipelineStrategy.builder().build();
            LoopContext context = createTestContext("team-cancel-no-preserve");

            // 走合法路径：PLAN -> PRD -> EXEC
            strategy.markPhase(context, TeamPipelineStrategy.Phase.PRD, "plan to prd");
            strategy.markPhase(context, TeamPipelineStrategy.Phase.EXEC, "prd to exec");

            strategy.requestCancel(context, false);

            assertTrue(Boolean.TRUE.equals(context.get("cancelRequested")));
            assertFalse(Boolean.TRUE.equals(context.get("preserveForResume")),
                    "preserveForResume should be false");
        }

        @Test
        void testVerificationStateAsObject() {
            VerificationState vs = new VerificationState();
            assertEquals(VerificationState.STATE_PENDING, vs.getCurrentState());
            assertFalse(vs.isTerminal());
            assertFalse(vs.isPending());

            vs.startVerification("Verify story S001", "S001", "architect");
            assertEquals(VerificationState.STATE_AWAITING_REVIEW, vs.getCurrentState());
            assertTrue(vs.isPending());
            assertNotNull(vs.getRequestId());
            assertEquals("S001", vs.getStoryId());

            // Architect 通过
            vs.recordArchitectFeedback(true, "Looks good");
            assertEquals(VerificationState.STATE_ARCHITECT_APPROVED, vs.getCurrentState());
            assertTrue(vs.isTerminal());

            // 清除后回到初始状态
            vs.clear();
            assertEquals(VerificationState.STATE_PENDING, vs.getCurrentState());
            assertFalse(vs.isPending());
        }

        @Test
        void testVerificationStateMaxAttempts() {
            VerificationState vs = new VerificationState();
            vs.setMaxVerificationAttempts(3);
            vs.startVerification("Verify", "S001", "architect");

            boolean r1 = vs.recordArchitectFeedback(false, "fail 1");
            assertFalse(r1, "Not terminal after 1 failure");
            assertEquals(1, vs.getVerificationAttempts());

            boolean r2 = vs.recordArchitectFeedback(false, "fail 2");
            assertFalse(r2, "Not terminal after 2 failures");
            assertEquals(2, vs.getVerificationAttempts());

            boolean r3 = vs.recordArchitectFeedback(false, "fail 3");
            assertTrue(r3, "Terminal after 3 failures");
            assertEquals(VerificationState.STATE_FAILED, vs.getCurrentState());
            assertEquals(3, vs.getVerificationAttempts());
        }
    }

    // ==========================================
    // 4. Autopilot 测试
    // ==========================================

    @Nested
    class AutopilotFixTest {

        @Test
        void testPipelineStageToTeamPhaseMapping() {
            assertEquals(TeamPipelineStrategy.Phase.PLAN,
                    PipelineStage.PLANNING.toTeamPhase());
            assertEquals(TeamPipelineStrategy.Phase.PLAN,
                    PipelineStage.EXPANSION.toTeamPhase());
            assertEquals(TeamPipelineStrategy.Phase.EXEC,
                    PipelineStage.EXECUTION.toTeamPhase());
            assertEquals(TeamPipelineStrategy.Phase.VERIFY,
                    PipelineStage.QA.toTeamPhase());
            assertEquals(TeamPipelineStrategy.Phase.VERIFY,
                    PipelineStage.VALIDATION.toTeamPhase());
            assertEquals(TeamPipelineStrategy.Phase.COMPLETED,
                    PipelineStage.COMPLETED.toTeamPhase());
        }

        @Test
        void testPipelineStageFromTeamPhaseMapping() {
            assertEquals(PipelineStage.PLANNING,
                    PipelineStage.fromTeamPhase(TeamPipelineStrategy.Phase.PLAN));
            assertEquals(PipelineStage.PLANNING,
                    PipelineStage.fromTeamPhase(TeamPipelineStrategy.Phase.PRD));
            assertEquals(PipelineStage.EXECUTION,
                    PipelineStage.fromTeamPhase(TeamPipelineStrategy.Phase.EXEC));
            assertEquals(PipelineStage.QA,
                    PipelineStage.fromTeamPhase(TeamPipelineStrategy.Phase.VERIFY));
            assertEquals(PipelineStage.EXECUTION,
                    PipelineStage.fromTeamPhase(TeamPipelineStrategy.Phase.FIX));
            assertEquals(PipelineStage.COMPLETED,
                    PipelineStage.fromTeamPhase(TeamPipelineStrategy.Phase.COMPLETED));
        }

        @Test
        void testAutopilotCancelPipeline() {
            LoopEngine engine = LoopAutoConfiguration.createDefaultEngine();
            PipelineConfig config = PipelineConfig.builder().build();
            AutopilotExecutor executor = new AutopilotExecutor(engine, config);

            // 启动 pipeline
            AutopilotExecutor.PipelineRequest request =
                    AutopilotExecutor.PipelineRequest.create("test-cancel-001",
                            "Test cancel cleanup");
            CompletableFuture<AutopilotExecutor.PipelineResult> future =
                    executor.startPipeline(request);
            assertNotNull(future);

            // 取消
            assertTrue(executor.cancelPipeline("test-cancel-001"),
                    "cancelPipeline should succeed");

            // 验证 tracking 被清理
            assertNull(executor.getTracking("test-cancel-001"),
                    "Tracking should be null after cancel");
        }

        @Test
        void testAutopilotGetActivePipelinesEmpty() {
            LoopEngine engine = LoopAutoConfiguration.createDefaultEngine();
            PipelineConfig config = PipelineConfig.builder().build();
            AutopilotExecutor executor = new AutopilotExecutor(engine, config);

            // 初始时没有活跃 pipeline
            assertTrue(executor.getActivePipelines().isEmpty(),
                    "Active pipelines should be empty initially");
        }

        @Test
        void testDefaultStageAdapters() {
            StageAdapter execAdapter = DefaultStageAdapters.executionAdapter();
            assertNotNull(execAdapter);
            assertEquals(PipelineStage.EXECUTION, execAdapter.supportedStage());

            StageAdapter qaAdapter = DefaultStageAdapters.qaAdapter();
            assertNotNull(qaAdapter);
            assertEquals(PipelineStage.QA, qaAdapter.supportedStage());
        }
    }

    // ==========================================
    // 5. 集成层测试
    // ==========================================

    @Nested
    class IntegrationFixTest {

        @Test
        void testAgentBridgeWithoutAgent() {
            LoopEngine engine = LoopAutoConfiguration.createDefaultEngine();
            SolonAgentIntegration integration = new SolonAgentIntegration(engine);

            SolonAgentIntegration.AgentBridge bridge = integration.getAgentBridge();
            // 没有注入 SimpleAgent，executePrompt 应抛异常
            assertThrows(IllegalStateException.class, () -> bridge.executePrompt("test"),
                    "executePrompt should throw without agent injected");
        }

        @Test
        void testFlowBridgeExtractVariablesWithoutContext() {
            LoopEngine engine = LoopAutoConfiguration.createDefaultEngine();
            SolonFlowIntegration integration = new SolonFlowIntegration(engine);
            SolonFlowIntegration.FlowBridge bridge = integration.getFlowBridge();

            // 没有注入 FlowContext 时应返回空 Map
            Map<String, Object> vars = bridge.extractFlowVariables();
            assertNotNull(vars);
            assertTrue(vars.isEmpty(), "Variables should be empty without FlowContext");
        }

        @Test
        void testHarnessBridgeWithoutEngine() {
            LoopEngine engine = LoopAutoConfiguration.createDefaultEngine();
            SolonHarnessIntegration integration = new SolonHarnessIntegration(engine);
            SolonHarnessIntegration.HarnessBridge bridge = integration.getHarnessBridge();

            // 没有注入 HarnessEngine
            assertFalse(bridge.isToolAvailable("any-tool"),
                    "Tool should not be available without HarnessEngine");

            // executeWithHarness 应抛异常
            assertThrows(IllegalStateException.class,
                    () -> bridge.executeWithHarness("test"),
                    "executeWithHarness should throw without engine injected");
        }

        @Test
        void testLoopAutoConfigurationUseDiskState() throws IOException {
            Path tempDir = Files.createTempDirectory("autoconfig-disk-test");
            try {
                LoopAutoConfiguration configurator = new LoopAutoConfiguration();
                configurator.useDiskState(tempDir.toString());
                LoopEngine engine = configurator.build();
                assertNotNull(engine, "Engine should be built with DiskStateManager");
            } finally {
                deleteDirectory(tempDir);
            }
        }

        @Test
        void testLoopAutoConfigurationBuildWithIntegrations() {
            LoopAutoConfiguration.IntegratedComponents components =
                    LoopAutoConfiguration.createDefault();
            assertNotNull(components.loopEngine);
            assertNotNull(components.agentIntegration);
            assertNotNull(components.flowIntegration);
            assertNotNull(components.harnessIntegration);
        }

        @Test
        void testAgentIntegrationMethods() {
            LoopEngine engine = LoopAutoConfiguration.createDefaultEngine();
            SolonAgentIntegration integration = new SolonAgentIntegration(engine);

            // 验证辅助方法不会抛异常
            assertNotNull(integration.getAgentBridge());
            assertFalse(integration.startUltraQALoop("test", null).getState().isTerminal());
        }
    }

    // ==========================================
    // 辅助方法
    // ==========================================

    private static LoopContext createTestContext(String description) {
        return new LoopContext(
                "test-session", description,
                LoopState.IDLE, 0, 10,
                Instant.now(),
                new HashMap<>(), new ArrayList<>(), new HashMap<>()
        );
    }

    private static void deleteDirectory(Path path) throws IOException {
        if (Files.exists(path)) {
            Files.walk(path)
                    .sorted(Comparator.reverseOrder())
                    .map(java.nio.file.Path::toFile)
                    .forEach(java.io.File::delete);
        }
    }
}
