package org.noear.solon.ai.harness.extension.goal;

import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GoalState 状态机：状态迁移合法性、预算阈值判定、错误计数、序列化往返。
 * <p>
 * 这些方法此前完全未被覆盖，但它们是 goal 循环是否继续/中止的唯一判据，属高风险纯逻辑。
 */
class GoalStateTest {

    private static GoalState state(long maxTokens) {
        return new GoalState("完成登录功能", maxTokens);
    }

    // ---------- 初始状态 ----------

    @Test
    void initialStateIsPursuingAndEmpty() {
        GoalState s = state(1000);
        assertNotNull(s.getId());
        assertEquals("完成登录功能", s.getObjective());
        assertEquals(GoalSnapshot.Status.PURSUING, s.getStatus());
        assertEquals(0, s.getConsumedTokens());
        assertEquals(1000, s.getMaxTokens());
        assertEquals(0, s.getCurrentIteration());
        assertEquals(0, s.getStagnationCount());
        assertEquals(0, s.getConsecutiveErrors());
        assertNull(s.getLastResult());
        assertNull(s.getLastFingerprint());
        assertNull(s.getLastErrorType());
        assertNull(s.getLastErrorSummary());
        assertNotNull(s.getThresholds());
    }

    // ---------- 状态迁移 ----------

    @Test
    void pauseAndResumeFollowAllowedTransitions() {
        GoalState s = state(1000);

        assertTrue(s.pause());
        assertEquals(GoalSnapshot.Status.PAUSED, s.getStatus());
        // 重复 pause 在非 PURSUING 时无效
        assertFalse(s.pause());

        assertTrue(s.resume());
        assertEquals(GoalSnapshot.Status.PURSUING, s.getStatus());
        // 已是 PURSUING，不可再 resume
        assertFalse(s.resume());
    }

    @Test
    void blockedStateIsResumable() {
        GoalState s = state(1000);
        s.markBlocked();
        assertEquals(GoalSnapshot.Status.BLOCKED, s.getStatus());
        assertTrue(s.getStatus().isResumable());
        assertTrue(s.resume());
        assertEquals(GoalSnapshot.Status.PURSUING, s.getStatus());
    }

    @Test
    void achieveOnlyFromPursuing() {
        GoalState s = state(1000);
        s.achieve();
        assertEquals(GoalSnapshot.Status.ACHIEVED, s.getStatus());
        assertTrue(s.getStatus().isTerminal());

        // 已终态后再 achieve 不变
        s.achieve();
        assertEquals(GoalSnapshot.Status.ACHIEVED, s.getStatus());
    }

    @Test
    void rejectAchievementRollsBackToPursuing() {
        GoalState s = state(1000);

        // 未达 ACHIEVED 时拒绝无效
        assertFalse(s.rejectAchievement());

        s.achieve();
        assertTrue(s.rejectAchievement());
        assertEquals(GoalSnapshot.Status.PURSUING, s.getStatus());
    }

    @Test
    void terminalMarkersOnlyApplyFromPursuing() {
        GoalState s = state(1000);
        s.markBudgetLimited();
        assertEquals(GoalSnapshot.Status.BUDGET_LIMITED, s.getStatus());
        assertTrue(s.getStatus().isTerminal());

        GoalState s2 = state(1000);
        s2.markIterationLimited();
        assertEquals(GoalSnapshot.Status.ITERATION_LIMITED, s2.getStatus());

        // 已 terminel 时再标记不覆盖
        s.markIterationLimited();
        assertEquals(GoalSnapshot.Status.BUDGET_LIMITED, s.getStatus());
    }

    @Test
    void statusEnumPredicates() {
        assertTrue(GoalSnapshot.Status.PURSUING.isActive());
        assertFalse(GoalSnapshot.Status.PAUSED.isActive());
        assertTrue(GoalSnapshot.Status.PAUSED.isResumable());
        assertTrue(GoalSnapshot.Status.BLOCKED.isResumable());
        assertFalse(GoalSnapshot.Status.PURSUING.isResumable());
        assertTrue(GoalSnapshot.Status.ACHIEVED.isTerminal());
        assertTrue(GoalSnapshot.Status.BUDGET_LIMITED.isTerminal());
        assertTrue(GoalSnapshot.Status.ITERATION_LIMITED.isTerminal());
        assertFalse(GoalSnapshot.Status.BLOCKED.isTerminal());
    }

    // ---------- 预算 ----------

    @Test
    void budgetExceededWhenConsumedReachesMax() {
        GoalState s = state(100);
        assertFalse(s.isBudgetExceeded());
        s.addTokens(99);
        assertFalse(s.isBudgetExceeded());
        s.addTokens(1);
        assertTrue(s.isBudgetExceeded());
    }

    @Test
    void zeroMaxTokensMeansUnlimited() {
        GoalState s = state(0);
        s.addTokens(1_000_000);
        assertFalse(s.isBudgetExceeded(), "maxTokens=0 表示不限制");
        assertFalse(s.isBudgetWarning());
        assertFalse(s.isBudgetCritical());
    }

    @Test
    void warningAndCriticalThresholdsUseInjectedPercentages() {
        // 默认 70 / 85
        GoalState s = state(100);
        s.addTokens(69);
        assertFalse(s.isBudgetWarning());
        assertFalse(s.isBudgetCritical());

        s.addTokens(1); // 70%
        assertTrue(s.isBudgetWarning());
        assertFalse(s.isBudgetCritical());

        s.addTokens(15); // 85%
        assertTrue(s.isBudgetCritical());
        // critical 时 warning 为 false（互斥）
        assertFalse(s.isBudgetWarning());
    }

    @Test
    void setThresholdsIgnoresNull() {
        GoalState s = state(100);
        BudgetThresholds before = s.getThresholds();
        s.setThresholds(null);
        assertEquals(before, s.getThresholds());

        s.setThresholds(new BudgetThresholds(10, 20));
        assertEquals(10, s.getThresholds().getWarningPercent());
        assertEquals(20, s.getThresholds().getCriticalPercent());
    }

    @Test
    void extendBudgetReactivatesBudgetLimitedGoal() {
        GoalState s = state(100);
        s.addTokens(100);
        s.markBudgetLimited();
        assertEquals(GoalSnapshot.Status.BUDGET_LIMITED, s.getStatus());

        s.extendBudget(50);
        assertEquals(150, s.getMaxTokens());
        assertEquals(GoalSnapshot.Status.PURSUING, s.getStatus());
        assertFalse(s.isBudgetExceeded());
    }

    @Test
    void extendBudgetIgnoresNonPositive() {
        GoalState s = state(100);
        s.extendBudget(0);
        s.extendBudget(-10);
        assertEquals(100, s.getMaxTokens());
    }

    @Test
    void iterationExceededUsesMaxIterations() {
        GoalState s = state(100);
        s.setMaxIterations(3);
        assertEquals(3, s.getMaxIterations());
        assertFalse(s.isIterationExceeded(2));
        assertTrue(s.isIterationExceeded(3));
        assertTrue(s.isIterationExceeded(4));

        // 0 表示不限制
        s.setMaxIterations(0);
        assertFalse(s.isIterationExceeded(999));
        // 负数被夹到 0
        s.setMaxIterations(-5);
        assertEquals(0, s.getMaxIterations());
    }

    // ---------- 迭代与执行痕迹 ----------

    @Test
    void iterationCounterIncrements() {
        GoalState s = state(100);
        assertEquals(1, s.incrementIteration());
        assertEquals(2, s.incrementIteration());
        assertEquals(2, s.getCurrentIteration());
        s.setCurrentIteration(10);
        assertEquals(10, s.getCurrentIteration());
    }

    @Test
    void updateLastExecutionRecordsResultAndTimestamp() {
        GoalState s = state(100);
        assertEquals(0, s.getLastExecutedAt());
        s.updateLastExecution("done step 1");
        assertEquals("done step 1", s.getLastResult());
        assertTrue(s.getLastExecutedAt() > 0);
    }

    @Test
    void stagnationAndFingerprint() {
        GoalState s = state(100);
        s.recordStagnation();
        s.recordStagnation();
        assertEquals(2, s.getStagnationCount());
        s.resetStagnation();
        assertEquals(0, s.getStagnationCount());

        s.setLastFingerprint("fp-1");
        assertEquals("fp-1", s.getLastFingerprint());
    }

    // ---------- 错误计数 ----------

    @Test
    void recordErrorTracksSameTypeStreakOnly() {
        GoalState s = state(100);

        // recordError 只维护「连续同类型」计数，返回的也是该计数
        assertEquals(1, s.recordError("TIMEOUT", "first timeout"));
        assertEquals(1, s.getConsecutiveSameTypeErrors());
        assertEquals("TIMEOUT", s.getLastErrorType());
        assertEquals("first timeout", s.getLastErrorSummary());

        // 同类型：+1
        assertEquals(2, s.recordError("TIMEOUT", "second timeout"));
        assertEquals(2, s.getConsecutiveSameTypeErrors());

        // 换类型：归 1
        assertEquals(1, s.recordError("NETWORK", "network down"));
        assertEquals("NETWORK", s.getLastErrorType());

        // 【已确认的框架不一致，用例锁定现状】
        // consecutiveErrors（总错误数）从不被 recordError 递增，只能由 incrementConsecutiveErrors 手动加。
        // 于是「错误分类」与「错误总数」两条计数器由不同入口维护，recordError 的调用方若期望总数同步，
        // 会读到 0。详见本类末的 resetConsecutiveErrors 用例。
        assertEquals(0, s.getConsecutiveErrors());
    }

    @Test
    void resetConsecutiveErrorsClearsCountersButKeepsLastError() {
        GoalState s = state(100);
        s.recordError("TIMEOUT", "boom");
        // recordError 不动 consecutiveErrors，需显式自增
        assertEquals(0, s.getConsecutiveErrors());
        s.incrementConsecutiveErrors();
        assertEquals(1, s.getConsecutiveErrors());

        s.resetConsecutiveErrors();
        assertEquals(0, s.getConsecutiveErrors());
        assertEquals(0, s.getConsecutiveSameTypeErrors());
    }

    // ---------- 序列化 ----------

    @Test
    void onodeRoundTripKeepsCoreFields() {
        GoalState s = state(500);
        s.setObjective("条件A");
        s.addTokens(120);
        s.incrementIteration();
        s.setLastFingerprint("fp");
        s.recordError("TIMEOUT", "摘要");
        s.setMaxIterations(7);

        ONode node = s.toONode();
        assertNotNull(node);

        GoalState back = GoalState.fromONode(node);
        assertEquals(s.getId(), back.getId());
        assertEquals("条件A", back.getObjective());
        assertEquals(120, back.getConsumedTokens());
        assertEquals(500, back.getMaxTokens());
        assertEquals(1, back.getCurrentIteration());
        // 运行时连续性字段按设计不持久化（与 toMap 同一取舍），往返后回到默认值
        assertNull(back.getLastFingerprint(), "运行时指纹按设计不持久化");
        assertNull(back.getLastErrorType(), "运行时错误分类按设计不持久化");
        assertEquals(7, back.getMaxIterations());
        assertEquals(s.getStatus(), back.getStatus());
    }

    @Test
    void onodeRoundTripToleratesNullOrMissingStatus() {
        // fromONode 与 fromMap 对齐：null / 缺 status 均返回 null，不抛 NPE
        assertNull(GoalState.fromONode(null));
        assertNull(GoalState.fromONode(new ONode()));
    }

    @Test
    void mapRoundTripKeepsCoreFields() {
        GoalState s = state(300);
        s.setObjective("条件B");
        s.addTokens(42);
        s.achieve();

        Map<String, Object> map = s.toMap();
        assertNotNull(map);

        GoalState back = GoalState.fromMap(map);
        assertEquals(s.getId(), back.getId());
        assertEquals("条件B", back.getObjective());
        assertEquals(42, back.getConsumedTokens());
        assertEquals(GoalSnapshot.Status.ACHIEVED, back.getStatus());
    }

    @Test
    void setConditionReplacesGoalText() {
        GoalState s = state(100);
        s.setObjective("新条件");
        assertEquals("新条件", s.getObjective());
    }

    @Test
    void setMaxTokensAndDuration() {
        GoalState s = state(100);
        s.setMaxTokens(999);
        assertEquals(999, s.getMaxTokens());
        s.setMaxDurationMs(60_000);
        assertEquals(60_000, s.getMaxDurationMs());
        assertTrue(s.getStartEpochMs() > 0);
    }

    @Test
    void fromMapReturnsNullOnMissingStatus() {
        // fromMap 在 status 键缺失时返回 null（而非抛异常或给默认值），调用方必须判空；
        // fromONode 已对齐为同样语义（见 onodeRoundTripToleratesNullOrMissingStatus）。
        assertNull(GoalState.fromMap(new HashMap<>()));
        assertNull(GoalState.fromMap(null));
    }

    @Test
    void fromMapFallsBackToObjectiveKeyForCondition() {
        Map<String, Object> map = new HashMap<>();
        map.put("status", "PURSUING");
        map.put("objective", "来自 objective 键");
        GoalState back = GoalState.fromMap(map);
        assertNotNull(back);
        assertEquals("来自 objective 键", back.getObjective());
    }
}
