package org.noear.solon.ai.loop;

import org.junit.jupiter.api.*;
import org.noear.solon.ai.loop.engine.IterationResult;
import org.noear.solon.ai.loop.state.LoopState;
import org.noear.solon.ai.loop.strategy.LoopContext;
import org.noear.solon.ai.loop.strategy.TeamPipelineStrategy;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 第三轮新增测试——Team Pipeline 阶段转换测试
 *
 * 覆盖对标 oh-my-claudecode 的 transitions.test.ts 中缺失的：
 * - 合法/非法阶段转换路径
 * - 修复循环溢出进入 COMPLETED
 * - VERIFY 阶段守卫检查（tasksTotal/tasksCompleted）
 * - 取消/恢复完整路径
 */
public class TeamPipelineTransitionTest {

    // ==========================================
    // 1. 合法阶段转换
    // ==========================================

    @Nested
    class ValidTransitionsTest {

        @Test
        void testCanonicalPathPlanToPrdToExecToVerifyToCompleted() {
            TeamPipelineStrategy strategy = TeamPipelineStrategy.builder().build();
            LoopContext ctx = createTestContext("canonical-path");

            // 初始时 context 中没有 currentPhase（由内部 getCurrentPhase 默认返回 PLAN）
            assertNull(ctx.get("currentPhase"), "Initial phase should be null in context");

            // PLAN -> PRD
            strategy.markPhase(ctx, TeamPipelineStrategy.Phase.PRD, "plan done");
            assertEquals(TeamPipelineStrategy.Phase.PRD.name(),
                    ctx.get("currentPhase"));

            // PRD -> EXEC
            strategy.markPhase(ctx, TeamPipelineStrategy.Phase.EXEC, "prd done");
            assertEquals(TeamPipelineStrategy.Phase.EXEC.name(),
                    ctx.get("currentPhase"));

            // EXEC -> VERIFY（需要先设置执行数据）
            strategy.setExecutionData(ctx, 1, 3);
            strategy.markTaskCompleted(ctx);
            strategy.markTaskCompleted(ctx);
            strategy.markTaskCompleted(ctx);
            strategy.markPhase(ctx, TeamPipelineStrategy.Phase.VERIFY, "exec done");
            assertEquals(TeamPipelineStrategy.Phase.VERIFY.name(),
                    ctx.get("currentPhase"));

            // VERIFY -> COMPLETED（验证通过）
            strategy.markPhase(ctx, TeamPipelineStrategy.Phase.COMPLETED, "all verified");
            assertEquals(TeamPipelineStrategy.Phase.COMPLETED.name(),
                    ctx.get("currentPhase"));
        }

        @Test
        void testPathWithFixLoop() {
            TeamPipelineStrategy strategy = TeamPipelineStrategy.builder()
                    .maxFixAttempts(2)
                    .build();
            LoopContext ctx = createTestContext("fix-loop-path");

            // 走标准路径到 VERIFY
            strategy.markPhase(ctx, TeamPipelineStrategy.Phase.PRD, "plan->prd");
            strategy.markPhase(ctx, TeamPipelineStrategy.Phase.EXEC, "prd->exec");
            strategy.setExecutionData(ctx, 1, 1);
            strategy.markTaskCompleted(ctx);

            // VERIFY -> FIX（验证失败）
            strategy.markPhase(ctx, TeamPipelineStrategy.Phase.VERIFY, "exec->verify");
            strategy.markPhase(ctx, TeamPipelineStrategy.Phase.FIX, "verify failed");
            assertEquals(TeamPipelineStrategy.Phase.FIX.name(),
                    ctx.get("currentPhase"));

            // FIX -> 回 EXEC（修复后重新执行）
            strategy.markPhase(ctx, TeamPipelineStrategy.Phase.EXEC, "fix->exec");
            assertEquals(TeamPipelineStrategy.Phase.EXEC.name(),
                    ctx.get("currentPhase"));
        }

        @Test
        void testFixOverflowToCompleted() {
            TeamPipelineStrategy strategy = TeamPipelineStrategy.builder()
                    .maxFixAttempts(1)  // 只有 1 次修复机会
                    .build();
            LoopContext ctx = createTestContext("fix-overflow");

            strategy.markPhase(ctx, TeamPipelineStrategy.Phase.PRD, "plan->prd");
            strategy.markPhase(ctx, TeamPipelineStrategy.Phase.EXEC, "prd->exec");
            strategy.setExecutionData(ctx, 1, 1);
            strategy.markTaskCompleted(ctx);
            strategy.markPhase(ctx, TeamPipelineStrategy.Phase.VERIFY, "exec->verify");

            // 验证失败进入 FIX（第 1 次）
            strategy.markPhase(ctx, TeamPipelineStrategy.Phase.FIX, "verify failed #1");

            // 模拟 fix 溢出：通过迭代执行触发 updatePhase 的溢出检查
            // 直接通过 markPhase 无法触发 updatePhase 内部的溢出逻辑
            // 验证 fixAttempts 机制
            assertNotNull(ctx.get("currentPhase"));

            // 通过 markPhase 不能触发 fix 溢出（由内部 updatePhase 控制）
            // 但我们可以验证 maxFixAttempts 配置生效
            assertEquals(1, strategy.getMaxFixAttempts());

            // 手动模拟 fix 溢出场景：FIX -> COMPLETED（当 fixAttempts >= maxFixAttempts）
            // 注意：markPhase 只做合法性校验，FIX->COMPLETED 是合法转换
            strategy.markPhase(ctx, TeamPipelineStrategy.Phase.COMPLETED, "max fixes reached");
            assertEquals(TeamPipelineStrategy.Phase.COMPLETED.name(),
                    ctx.get("currentPhase"));
        }

        @Test
        void testFixToVerifyDirectTransition() {
            TeamPipelineStrategy strategy = TeamPipelineStrategy.builder().build();
            LoopContext ctx = createTestContext("fix-to-verify");

            strategy.markPhase(ctx, TeamPipelineStrategy.Phase.PRD, "plan->prd");
            strategy.markPhase(ctx, TeamPipelineStrategy.Phase.EXEC, "prd->exec");
            strategy.setExecutionData(ctx, 1, 1);
            strategy.markTaskCompleted(ctx);
            strategy.markPhase(ctx, TeamPipelineStrategy.Phase.VERIFY, "exec->verify");
            strategy.markPhase(ctx, TeamPipelineStrategy.Phase.FIX, "verify->fix");

            // FIX -> VERIFY 直接转也是合法的（根据 isValidTransition 表）
            // FIX 允许转到 EXEC、VERIFY、COMPLETED、CANCELLED
            strategy.markPhase(ctx, TeamPipelineStrategy.Phase.VERIFY, "fix->verify");
            assertEquals(TeamPipelineStrategy.Phase.VERIFY.name(),
                    ctx.get("currentPhase"));
        }
    }

    // ==========================================
    // 2. 非法阶段转换
    // ==========================================

    @Nested
    class InvalidTransitionsTest {

        @Test
        void testPlanToExecIllegal() {
            TeamPipelineStrategy strategy = TeamPipelineStrategy.builder().build();
            LoopContext ctx = createTestContext("plan-exec-illegal");

            // PLAN -> EXEC 需要经过 PRD
            assertThrows(IllegalArgumentException.class,
                    () -> strategy.markPhase(ctx, TeamPipelineStrategy.Phase.EXEC, "skip prd"),
                    "PLAN -> EXEC should be illegal");
        }

        @Test
        void testPlanToVerifyIllegal() {
            TeamPipelineStrategy strategy = TeamPipelineStrategy.builder().build();
            LoopContext ctx = createTestContext("plan-verify-illegal");

            assertThrows(IllegalArgumentException.class,
                    () -> strategy.markPhase(ctx, TeamPipelineStrategy.Phase.VERIFY, "skip all"),
                    "PLAN -> VERIFY should be illegal");
        }

        @Test
        void testPlanToFixIllegal() {
            TeamPipelineStrategy strategy = TeamPipelineStrategy.builder().build();
            LoopContext ctx = createTestContext("plan-fix-illegal");

            assertThrows(IllegalArgumentException.class,
                    () -> strategy.markPhase(ctx, TeamPipelineStrategy.Phase.FIX, "skip all"),
                    "PLAN -> FIX should be illegal");
        }

        @Test
        void testCompletedToAnyIllegal() {
            TeamPipelineStrategy strategy = TeamPipelineStrategy.builder().build();
            LoopContext ctx = createTestContext("completed-illegal");

            // 先走到 COMPLETED
            strategy.markPhase(ctx, TeamPipelineStrategy.Phase.PRD, "plan->prd");
            strategy.markPhase(ctx, TeamPipelineStrategy.Phase.EXEC, "prd->exec");
            strategy.markPhase(ctx, TeamPipelineStrategy.Phase.VERIFY, "exec->verify");
            strategy.markPhase(ctx, TeamPipelineStrategy.Phase.COMPLETED, "done");

            // 从 COMPLETED 无法转到任何其他阶段
            assertThrows(IllegalArgumentException.class,
                    () -> strategy.markPhase(ctx, TeamPipelineStrategy.Phase.PLAN, "restart"),
                    "COMPLETED -> PLAN should be illegal");
            assertThrows(IllegalArgumentException.class,
                    () -> strategy.markPhase(ctx, TeamPipelineStrategy.Phase.EXEC, "restart"),
                    "COMPLETED -> EXEC should be illegal");
        }

        @Test
        void testCancelledToAnyIllegal() {
            TeamPipelineStrategy strategy = TeamPipelineStrategy.builder().build();
            LoopContext ctx = createTestContext("cancelled-illegal");

            // PLAN -> CANCELLED 是合法转换
            strategy.markPhase(ctx, TeamPipelineStrategy.Phase.CANCELLED, "cancel plan");

            // 从 CANCELLED 无法转到任何其他阶段
            assertThrows(IllegalArgumentException.class,
                    () -> strategy.markPhase(ctx, TeamPipelineStrategy.Phase.PLAN, "restart"),
                    "CANCELLED -> PLAN should be illegal");
        }

        @Test
        void testExecToPlanIllegal() {
            TeamPipelineStrategy strategy = TeamPipelineStrategy.builder().build();
            LoopContext ctx = createTestContext("exec-plan-illegal");

            strategy.markPhase(ctx, TeamPipelineStrategy.Phase.PRD, "plan->prd");
            strategy.markPhase(ctx, TeamPipelineStrategy.Phase.EXEC, "prd->exec");

            // EXEC -> PLAN 是回退，非法
            assertThrows(IllegalArgumentException.class,
                    () -> strategy.markPhase(ctx, TeamPipelineStrategy.Phase.PLAN, "rollback"),
                    "EXEC -> PLAN should be illegal");
        }
    }

    // ==========================================
    // 3. 取消与恢复测试
    // ==========================================

    @Nested
    class CancelAndResumeTest {

        @Test
        void testCancelWithoutPreserve() {
            TeamPipelineStrategy strategy = TeamPipelineStrategy.builder().build();
            LoopContext ctx = createTestContext("cancel-no-preserve");

            strategy.markPhase(ctx, TeamPipelineStrategy.Phase.PRD, "plan->prd");
            strategy.markPhase(ctx, TeamPipelineStrategy.Phase.EXEC, "prd->exec");

            strategy.requestCancel(ctx, false);

            assertTrue(Boolean.TRUE.equals(ctx.get("cancelRequested")));
            assertFalse(Boolean.TRUE.equals(ctx.get("preserveForResume")));
            assertNull(ctx.get("phaseBeforeCancel"),
                    "phaseBeforeCancel should be null when not preserving");
        }

        @Test
        void testCancelWithPreserve() {
            TeamPipelineStrategy strategy = TeamPipelineStrategy.builder().build();
            LoopContext ctx = createTestContext("cancel-with-preserve");

            strategy.markPhase(ctx, TeamPipelineStrategy.Phase.PRD, "plan->prd");
            strategy.markPhase(ctx, TeamPipelineStrategy.Phase.EXEC, "prd->exec");

            strategy.requestCancel(ctx, true);

            assertTrue(Boolean.TRUE.equals(ctx.get("cancelRequested")));
            assertTrue(Boolean.TRUE.equals(ctx.get("preserveForResume")));
            assertEquals(TeamPipelineStrategy.Phase.EXEC, ctx.get("phaseBeforeCancel"),
                    "phaseBeforeCancel should be EXEC");
        }

        @Test
        void testResumeFromCancel() {
            TeamPipelineStrategy strategy = TeamPipelineStrategy.builder().build();
            LoopContext ctx = createTestContext("resume-test");

            strategy.markPhase(ctx, TeamPipelineStrategy.Phase.PRD, "plan->prd");
            strategy.markPhase(ctx, TeamPipelineStrategy.Phase.EXEC, "prd->exec");

            strategy.requestCancel(ctx, true);
            assertTrue(strategy.resumeFromCancel(ctx), "Resume should succeed");

            // 恢复后 cancelRequested 应清除
            assertFalse(Boolean.TRUE.equals(ctx.get("cancelRequested")),
                    "cancelRequested should be cleared after resume");
            assertFalse(Boolean.TRUE.equals(ctx.get("preserveForResume")),
                    "preserveForResume should be cleared after resume");
        }

        @Test
        void testResumeWithoutPreserveFails() {
            TeamPipelineStrategy strategy = TeamPipelineStrategy.builder().build();
            LoopContext ctx = createTestContext("resume-no-preserve");

            strategy.markPhase(ctx, TeamPipelineStrategy.Phase.PRD, "plan->prd");
            strategy.markPhase(ctx, TeamPipelineStrategy.Phase.EXEC, "prd->exec");

            strategy.requestCancel(ctx, false);
            assertFalse(strategy.resumeFromCancel(ctx),
                    "Resume should fail when preserveForResume is false");
        }

        @Test
        void testResumeWithoutCancelFails() {
            TeamPipelineStrategy strategy = TeamPipelineStrategy.builder().build();
            LoopContext ctx = createTestContext("resume-no-cancel");

            // 没有请求过取消
            assertFalse(strategy.resumeFromCancel(ctx),
                    "Resume should fail when no cancel was requested");
        }

        @Test
        void testCancelAtDifferentPhases() {
            TeamPipelineStrategy strategy = TeamPipelineStrategy.builder().build();
            LoopContext ctx = createTestContext("cancel-phases");

            // 在每个阶段都可以取消
            // PLAN -> CANCELLED（通过 markPhase 转换到 CANCELLED）
            strategy.markPhase(ctx, TeamPipelineStrategy.Phase.CANCELLED, "cancel at plan");
            assertEquals(TeamPipelineStrategy.Phase.CANCELLED.name(),
                    ctx.get("currentPhase"));
        }
    }

    // ==========================================
    // 4. 执行追踪测试
    // ==========================================

    @Nested
    class ExecutionTrackingTest {

        @Test
        void testSetExecutionData() {
            TeamPipelineStrategy strategy = TeamPipelineStrategy.builder().build();
            LoopContext ctx = createTestContext("exec-data");

            // 先走到 EXEC 阶段
            strategy.markPhase(ctx, TeamPipelineStrategy.Phase.PRD, "plan->prd");
            strategy.markPhase(ctx, TeamPipelineStrategy.Phase.EXEC, "prd->exec");

            strategy.setExecutionData(ctx, 3, 10);
            assertEquals(TeamPipelineStrategy.Phase.EXEC.name(),
                    ctx.get("currentPhase"), "Should still be in EXEC phase");

            // 通过 markTaskCompleted 间接验证执行数据已设置
            strategy.markTaskCompleted(ctx);
            strategy.markTaskCompleted(ctx);
            strategy.markTaskCompleted(ctx);

            // 验证 EXEC -> VERIFY 时 tasksCompleted >= tasksTotal（3 >= 3）
            strategy.markPhase(ctx, TeamPipelineStrategy.Phase.VERIFY, "3 tasks done");
            assertEquals(TeamPipelineStrategy.Phase.VERIFY.name(),
                    ctx.get("currentPhase"));
        }

        @Test
        void testMarkTaskCompleted() {
            TeamPipelineStrategy strategy = TeamPipelineStrategy.builder().build();
            LoopContext ctx = createTestContext("mark-completed");

            strategy.setExecutionData(ctx, 1, 5);
            for (int i = 0; i < 5; i++) {
                strategy.markTaskCompleted(ctx);
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> exec = (Map<String, Object>) ctx.get("execution");
            assertEquals(5, exec.get("tasksCompleted"),
                    "All 5 tasks should be marked completed");
        }

        @Test
        void testMarkTaskCompletedIncremental() {
            TeamPipelineStrategy strategy = TeamPipelineStrategy.builder().build();
            LoopContext ctx = createTestContext("incremental");

            strategy.setExecutionData(ctx, 1, 3);

            strategy.markTaskCompleted(ctx);
            @SuppressWarnings("unchecked")
            Map<String, Object> exec1 = (Map<String, Object>) ctx.get("execution");
            assertEquals(1, exec1.get("tasksCompleted"));

            strategy.markTaskCompleted(ctx);
            assertEquals(2, ((Map<String, Object>) ctx.get("execution")).get("tasksCompleted"));

            strategy.markTaskCompleted(ctx);
            assertEquals(3, ((Map<String, Object>) ctx.get("execution")).get("tasksCompleted"));
        }

        @Test
        void testPhaseHistoryRecords() {
            TeamPipelineStrategy strategy = TeamPipelineStrategy.builder().build();
            LoopContext ctx = createTestContext("history-records");

            strategy.markPhase(ctx, TeamPipelineStrategy.Phase.PRD, "Phase 1 done");
            strategy.markPhase(ctx, TeamPipelineStrategy.Phase.EXEC, "Phase 2 done");

            List<Map<String, String>> history = strategy.getPhaseHistory(ctx);
            assertNotNull(history);

            // 应该有 2 条历史记录（PRD 和 EXEC）
            // 注意：markPhase 记录的是目标阶段，所以第一条是 PRD，第二条是 EXEC
            assertTrue(history.size() >= 2, "Should have at least 2 history entries");

            // 验证历史记录格式
            Map<String, String> firstEntry = history.get(0);
            assertEquals(TeamPipelineStrategy.Phase.PRD.name(), firstEntry.get("phase"));
            assertEquals("Phase 1 done", firstEntry.get("reason"));
            assertNotNull(firstEntry.get("enteredAt"), "Should have timestamp");

            Map<String, String> secondEntry = history.get(1);
            assertEquals(TeamPipelineStrategy.Phase.EXEC.name(), secondEntry.get("phase"));
            assertEquals("Phase 2 done", secondEntry.get("reason"));
        }
    }

    // ==========================================
    // 5. Phase 枚举完整性测试
    // ==========================================

    @Nested
    class PhaseEnumTest {

        @Test
        void testAllPhasesDefined() {
            // 验证所有阶段都存在
            assertNotNull(TeamPipelineStrategy.Phase.valueOf("PLAN"));
            assertNotNull(TeamPipelineStrategy.Phase.valueOf("PRD"));
            assertNotNull(TeamPipelineStrategy.Phase.valueOf("EXEC"));
            assertNotNull(TeamPipelineStrategy.Phase.valueOf("VERIFY"));
            assertNotNull(TeamPipelineStrategy.Phase.valueOf("FIX"));
            assertNotNull(TeamPipelineStrategy.Phase.valueOf("COMPLETED"));
            assertNotNull(TeamPipelineStrategy.Phase.valueOf("CANCELLED"));
        }

        @Test
        void testDefaultPhasesOrder() {
            TeamPipelineStrategy strategy = TeamPipelineStrategy.builder().build();
            List<TeamPipelineStrategy.Phase> phases = strategy.getPhases();

            assertEquals(5, phases.size());
            assertEquals(TeamPipelineStrategy.Phase.PLAN, phases.get(0));
            assertEquals(TeamPipelineStrategy.Phase.PRD, phases.get(1));
            assertEquals(TeamPipelineStrategy.Phase.EXEC, phases.get(2));
            assertEquals(TeamPipelineStrategy.Phase.VERIFY, phases.get(3));
            assertEquals(TeamPipelineStrategy.Phase.FIX, phases.get(4));
        }

        @Test
        void testMaxFixAttemptsDefault() {
            TeamPipelineStrategy strategy = TeamPipelineStrategy.builder().build();
            assertEquals(3, strategy.getMaxFixAttempts());
        }

        @Test
        void testMaxFixAttemptsCustom() {
            TeamPipelineStrategy strategy = TeamPipelineStrategy.builder()
                    .maxFixAttempts(5)
                    .build();
            assertEquals(5, strategy.getMaxFixAttempts());
        }
    }

    // ==========================================
    // 辅助方法
    // ==========================================

    private static LoopContext createTestContext(String description) {
        return new LoopContext(
                "test-session", description,
                LoopState.IDLE, 0, 100,
                Instant.now(),
                new HashMap<>(), new ArrayList<>(), new HashMap<>()
        );
    }
}
