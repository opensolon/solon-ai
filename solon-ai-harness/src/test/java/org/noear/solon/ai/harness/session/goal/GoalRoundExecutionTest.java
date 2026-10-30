package org.noear.solon.ai.harness.session.goal;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.harness.runtime.ExecutionBridge;
import org.noear.solon.ai.harness.runtime.RoundResult;
import org.noear.solon.ai.harness.session.goal.validate.BuildPassValidator;
import org.noear.solon.ai.harness.session.goal.validate.GoalValidator;
import org.noear.solon.ai.harness.session.goal.validate.NoopValidator;
import org.noear.solon.ai.harness.session.goal.validate.TestsPassValidator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GoalEngine 轮次执行与验证器链。
 *
 * <p>用真实 ExecutionBridge + 内存会话驱动 runRound，覆盖守卫、预算收尾、
 * 完成复核、异常分级熔断与验证器链，不依赖 JobManager。</p>
 */
class GoalRoundExecutionTest {

    /** 记录每轮 prompt 并返回可编排结果的执行桥。 */
    private static class FakeBridge {
        final ExecutionBridge bridge = new ExecutionBridge(null);
        final List<String> prompts = new ArrayList<>();
        RoundResult next = RoundResult.ofText("ok");
        Exception failure;
        /** 轮次内回调：模拟 goal_update(complete) 在工具执行中把状态置为 ACHIEVED。 */
        Runnable duringRound;

        FakeBridge() {
            bridge.addTaskExecutor((sessionId, prompt, agentName) -> {
                prompts.add(prompt);
                if (duringRound != null) {
                    duringRound.run();
                }
                if (failure != null) {
                    // 桥接层允许抛出任意异常，引擎按 ErrorClassifier 分级
                    throw new RuntimeException(failure);
                }
                return next.getFinalResult();
            });
        }
    }

    private static GoalEngine engine(ExecutionBridge bridge, int maxErrors) {
        return new GoalEngine(bridge, BudgetThresholds.defaults(), 3, maxErrors, false);
    }

    private static AgentSession session() {
        return InMemoryAgentSession.of("sess-round");
    }

    // ========== 守卫 ==========

    @Test
    void runRoundReturnsFalseWithoutGoal() {
        FakeBridge fb = new FakeBridge();
        assertFalse(engine(fb.bridge, 3).runRound(session()));
        assertTrue(fb.prompts.isEmpty());
    }

    @Test
    void runRoundSkipsWhenBridgeHasNoHandler() {
        GoalEngine e = engine(new ExecutionBridge(null), 3);
        AgentSession s = session();
        e.attach(s, "目标", 0, 0, 0);
        assertFalse(e.runRound(s));
    }

    @Test
    void runRoundSkipsWhenSessionBusy() {
        FakeBridge fb = new FakeBridge();
        fb.bridge.addBusyChecker(sessionId -> true);
        GoalEngine e = engine(fb.bridge, 3);
        AgentSession s = session();
        e.attach(s, "目标", 0, 0, 0);

        assertFalse(e.runRound(s));
        assertTrue(fb.prompts.isEmpty(), "繁忙时应跳过本轮");
    }

    @Test
    void runRoundRejectsReentrantCall() {
        FakeBridge fb = new FakeBridge();
        GoalEngine e = engine(fb.bridge, 3);
        AgentSession s = session();
        e.attach(s, "目标", 0, 0, 0);
        // 模拟「上一轮仍在跑」的运行态标记
        s.attrs().put("session.goal.running", Boolean.TRUE);

        assertFalse(e.runRound(s));
        assertTrue(fb.prompts.isEmpty());
    }

    // ========== 正常轮次 ==========

    @Test
    void runRoundExecutesAndIncrementsIteration() {
        FakeBridge fb = new FakeBridge();
        fb.next = RoundResult.ofText("完成了第一步");
        GoalEngine e = engine(fb.bridge, 3);
        AgentSession s = session();
        GoalState gs = e.attach(s, "目标", 0, 0, 0);

        assertTrue(e.runRound(s));
        assertEquals(1, gs.getCurrentIteration());
        // TaskHandler 只回文本，token 由 ExecutionBridge 按文本长度估算（len/4，至少 1）
        assertTrue(gs.getConsumedTokens() > 0);
        assertEquals("完成了第一步", gs.getLastResult());
        assertEquals(1, fb.prompts.size());
        // 运行态标记必须清理，否则后续轮次永远被判重入
        assertNull(s.attrs().get("session.goal.running"));
    }

    @Test
    void runRoundAccumulatesTokensAcrossRounds() {
        FakeBridge fb = new FakeBridge();
        GoalEngine e = engine(fb.bridge, 3);
        AgentSession s = session();
        GoalState gs = e.attach(s, "目标", 0, 0, 0);

        e.runRound(s);
        long afterFirst = gs.getConsumedTokens();
        fb.next = RoundResult.ofText("第二轮的文本明显更长一些用于累计验证");
        e.runRound(s);

        assertTrue(afterFirst > 0);
        assertTrue(gs.getConsumedTokens() > afterFirst, "跨轮应累计而非覆盖");
    }

    private static String text(int repeat) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < repeat; i++) {
            sb.append("进展内容");
        }
        return sb.toString();
    }

    @Test
    void runRoundRecordsStagnationOnRepeatedFingerprint() {
        FakeBridge fb = new FakeBridge();
        fb.next = RoundResult.ofText(text(60));
        GoalEngine e = engine(fb.bridge, 3);
        AgentSession s = session();
        GoalState gs = e.attach(s, "目标", 0, 0, 0);

        e.runRound(s);
        e.runRound(s);

        assertEquals(1, gs.getStagnationCount());
    }

    @Test
    void runRoundResetsStagnationWhenFingerprintChanges() {
        FakeBridge fb = new FakeBridge();
        GoalEngine e = engine(fb.bridge, 3);
        AgentSession s = session();
        GoalState gs = e.attach(s, "目标", 0, 0, 0);

        // 指纹按长度分桶（每 200 字符一档），两轮必须落在不同桶才判定"有进展"
        fb.next = RoundResult.ofText(text(30));
        e.runRound(s);
        fb.next = RoundResult.ofText(text(120));
        e.runRound(s);

        assertEquals(0, gs.getStagnationCount());
    }

    // ========== 完成判定与证据复核 ==========

    @Test
    void achievedGoalStopsRounds() {
        FakeBridge fb = new FakeBridge();
        GoalEngine e = engine(fb.bridge, 3);
        AgentSession s = session();
        GoalState gs = e.attach(s, "分析现状", 0, 0, 0);
        gs.achieve();

        assertFalse(e.runRound(s), "已达成的目标不再续行");
    }

    @Test
    void completionRejectedWithoutToolEvidenceForActionGoal() {
        FakeBridge fb = new FakeBridge();
        // 执行型目标 + 本轮无工具调用 → 完成声明被拒
        fb.next = RoundResult.ofText("我做完了");
        GoalEngine e = engine(fb.bridge, 3);
        AgentSession s = session();
        GoalState gs = e.attach(s, "创建一份文件", 0, 0, 0);
        // 模拟模型在工具执行中调用 goal_update(complete)
        fb.duringRound = gs::achieve;

        assertTrue(e.runRound(s), "驳回后目标回到活跃态，仍可继续");
        assertTrue(gs.getStatus().isActive());
        assertTrue(gs.getLastResult().contains("完成声明被拒绝"));
    }

    @Test
    void completionAcceptedWithToolEvidenceForActionGoal() {
        FakeBridge fb = new FakeBridge();
        fb.next = RoundResult.of(true, 10, "已创建文件");
        GoalEngine e = engine(fb.bridge, 3);
        AgentSession s = session();
        GoalState gs = e.attach(s, "创建一份文件", 0, 0, 0);
        gs.achieve();

        assertFalse(e.runRound(s));
        assertEquals(GoalState.Status.ACHIEVED, gs.getStatus());
    }

    @Test
    void nonActionGoalNeedsNoEvidence() {
        FakeBridge fb = new FakeBridge();
        fb.next = RoundResult.ofText("分析完毕");
        GoalEngine e = engine(fb.bridge, 3);
        AgentSession s = session();
        GoalState gs = e.attach(s, "分析现状", 0, 0, 0);
        fb.duringRound = gs::achieve;

        assertFalse(e.runRound(s));
        assertEquals(GoalState.Status.ACHIEVED, gs.getStatus());
    }

    // ========== 预算守卫 ==========

    @Test
    void tokenBudgetExceededTriggersWrapUpAndStops() {
        FakeBridge fb = new FakeBridge();
        fb.next = RoundResult.ofText("最终总结");
        GoalEngine e = engine(fb.bridge, 3);
        AgentSession s = session();
        GoalState gs = e.attach(s, "分析现状", 100, 0, 0);
        gs.addTokens(200); // 已超预算
        e.commit(s, gs);

        assertFalse(e.runRound(s));
        // 一轮收尾（wrap-up），然后标记预算受限
        assertEquals(1, fb.prompts.size());
        assertEquals(GoalState.Status.BUDGET_LIMITED, gs.getStatus());
    }

    @Test
    void iterationLimitStopsRounds() {
        FakeBridge fb = new FakeBridge();
        GoalEngine e = engine(fb.bridge, 3);
        AgentSession s = session();
        GoalState gs = e.attach(s, "分析现状", 0, 0, 1);
        gs.setCurrentIteration(1);
        e.commit(s, gs);

        assertFalse(e.runRound(s));
        assertTrue(fb.prompts.isEmpty(), "轮次超限应在执行前就停下");
        assertEquals(GoalState.Status.ITERATION_LIMITED, gs.getStatus());
    }

    @Test
    void durationBudgetExceededTriggersWrapUp() throws Exception {
        FakeBridge fb = new FakeBridge();
        GoalEngine e = engine(fb.bridge, 3);
        AgentSession s = session();
        // maxDurationMs=1：attach 后已必然超时，无需改内部时间戳
        GoalState gs = e.attach(s, "分析现状", 0, 1, 0);
        Thread.sleep(5); // 确保 elapsed >= 1ms，避免同毫秒内取整
        e.commit(s, gs);

        assertFalse(e.runRound(s));
        assertEquals(1, fb.prompts.size());
        assertEquals(GoalState.Status.BUDGET_LIMITED, gs.getStatus());
    }

    // ========== 异常分级熔断 ==========

    @Test
    void sameErrorTypeThreeTimesBlocks() {
        FakeBridge fb = new FakeBridge();
        GoalEngine e = engine(fb.bridge, 99);
        AgentSession s = session();
        GoalState gs = e.attach(s, "分析现状", 0, 0, 0);

        fb.failure = new IllegalStateException("boom");
        e.runRound(s);
        e.runRound(s);
        e.runRound(s);

        assertEquals(GoalState.Status.BLOCKED, gs.getStatus());
    }

    @Test
    void nonRecoverableErrorBlocksAfterTwo() {
        FakeBridge fb = new FakeBridge();
        GoalEngine e = engine(fb.bridge, 99);
        AgentSession s = session();
        GoalState gs = e.attach(s, "分析现状", 0, 0, 0);

        // SSL 属于不可恢复错误：两次同类即熔断
        fb.failure = new javax.net.ssl.SSLHandshakeException("bad cert");
        e.runRound(s);
        e.runRound(s);

        assertEquals(GoalState.Status.BLOCKED, gs.getStatus());
    }

    @Test
    void consecutiveErrorsThresholdBlocks() {
        FakeBridge fb = new FakeBridge();
        GoalEngine e = engine(fb.bridge, 2);
        AgentSession s = session();
        GoalState gs = e.attach(s, "分析现状", 0, 0, 0);

        // 两种不同类型错误各一次：同类型计数只有 1，但总数达到阈值 2
        fb.failure = new IllegalStateException("a");
        e.runRound(s);
        fb.failure = new IllegalArgumentException("b");
        e.runRound(s);

        assertEquals(GoalState.Status.BLOCKED, gs.getStatus());
    }

    @Test
    void failedRoundClearsRunningFlag() {
        FakeBridge fb = new FakeBridge();
        fb.failure = new IllegalStateException("boom");
        GoalEngine e = engine(fb.bridge, 3);
        AgentSession s = session();
        e.attach(s, "分析现状", 0, 0, 0);

        e.runRound(s);

        assertNull(s.attrs().get("session.goal.running"), "异常路径也必须清理重入标记");
    }

    @Test
    void errorSummaryIsTruncatedAndClassified() {
        assertEquals("IllegalStateException", GoalEngine.extractErrorSummary(new IllegalStateException()));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            sb.append('x');
        }
        String summary = GoalEngine.extractErrorSummary(new RuntimeException(sb.toString()));
        assertEquals(203, summary.length());
        assertTrue(summary.endsWith("..."));
    }

    @Test
    void nonRecoverableClassification() {
        assertTrue(GoalEngine.isNonRecoverable("SSL"));
        assertTrue(GoalEngine.isNonRecoverable("HTTP_4XX"));
        assertFalse(GoalEngine.isNonRecoverable("HTTP_5XX"));
        assertFalse(GoalEngine.isNonRecoverable(null));
    }

    // ========== 单槽读写与更新 ==========

    @Test
    void updateThrowsWhenNoGoal() {
        GoalEngine e = engine(new ExecutionBridge(null), 3);
        assertThrows(IllegalStateException.class,
                () -> e.update(session(), "新目标", 0, 0));
    }

    @Test
    void updateReplacesObjectiveAndBudgets() {
        GoalEngine e = engine(new ExecutionBridge(null), 3);
        AgentSession s = session();
        e.attach(s, "旧目标", 0, 0, 0);

        e.update(s, "新目标", 999, 7);

        GoalState gs = e.state(s);
        assertEquals("新目标", gs.getCondition());
        assertEquals(999, gs.getMaxTokens());
        assertEquals(7, gs.getMaxIterations());
    }

    @Test
    void hasGoalFalseForTerminal() {
        GoalEngine e = engine(new ExecutionBridge(null), 3);
        AgentSession s = session();
        GoalState gs = e.attach(s, "目标", 0, 0, 0);
        assertTrue(e.hasGoal(s));

        gs.achieve();
        e.commit(s, gs);
        assertFalse(e.hasGoal(s), "终态不再算有活跃目标");
    }

    @Test
    void listenerReceivesChanges() {
        GoalEngine e = engine(new ExecutionBridge(null), 3);
        AgentSession s = session();
        List<String> events = new ArrayList<>();
        e.addListener((sessionId, state, removed) ->
                events.add(state.getCondition() + ":" + removed));
        e.addListener(null); // null 监听器应被忽略

        GoalState gs = e.attach(s, "目标A", 0, 0, 0);
        e.clear(s);

        assertFalse(events.isEmpty());
        assertTrue(events.get(0).startsWith("目标A:"));
        assertNotNull(gs);
    }

    @Test
    void toTaskMapCarriesContractKeys() {
        GoalEngine e = engine(new ExecutionBridge(null), 3);
        AgentSession s = session();
        GoalState gs = e.attach(s, "目标", 500, 0, 3);

        Map<String, Object> item = e.toTaskMap(s, gs);

        assertEquals("GOAL", item.get("type"));
        assertEquals("目标", item.get("prompt"));
        assertEquals(true, item.get("enabled"));
        assertEquals(500L, item.get("maxTokens"));
        // 预算明细在嵌套 goal 内（与前端 goal.* 约定对齐）
        Map<?, ?> goal = (Map<?, ?>) item.get("goal");
        assertEquals(3, goal.get("maxIterations"));
        assertEquals(500L, goal.get("maxTokens"));
    }

    @Test
    void countUnfinishedTodosHandlesMissingInputs() {
        GoalEngine e = engine(new ExecutionBridge(null), 3);
        assertEquals(0, e.countUnfinishedTodos(null, "ws", "s1"));
        assertEquals(0, e.countUnfinishedTodos(null, "ws", null));
    }

    @Test
    void countUnfinishedCheckboxesCountsOnlyPending() {
        String todo = "- [ ] 未开始\n- [/] 进行中\n- [x] 已完成\n- 普通行\n";
        assertEquals(2, GoalEngine.countUnfinishedCheckboxes(todo));
        assertEquals(0, GoalEngine.countUnfinishedCheckboxes(null));
    }

    @Test
    void isTerminalStatusHandlesUnknown() {
        assertTrue(GoalEngine.isTerminalStatus("ACHIEVED"));
        assertFalse(GoalEngine.isTerminalStatus("PURSUING"));
        assertFalse(GoalEngine.isTerminalStatus("NOT_A_STATUS"));
        assertFalse(GoalEngine.isTerminalStatus(null));
    }

    @Test
    void isValidatorEnabledReflectsConstruction() {
        assertFalse(engine(new ExecutionBridge(null), 3).isValidatorEnabled());
        assertTrue(new GoalEngine(new ExecutionBridge(null), BudgetThresholds.defaults(), 3, 3, true)
                .isValidatorEnabled());
    }

    // ========== 验证器 ==========

    @Test
    void noopValidatorAlwaysPasses() {
        assertTrue(new NoopValidator().validate("任意条件").isPassed());
    }

    @Test
    void buildValidatorPassesWithoutWorkspace() {
        // 无工作区信息时不阻塞完成
        assertTrue(new BuildPassValidator(null).validate("build").isPassed());
        assertTrue(new BuildPassValidator("").validate("build").isPassed());
    }

    @Test
    void buildValidatorPassesWhenNoBuildToolDetected() {
        // 临时目录既无 pom.xml 也无 build.gradle → 跳过，不阻塞
        assertTrue(new BuildPassValidator(System.getProperty("java.io.tmpdir")).validate("build").isPassed());
    }

    @Test
    void testsValidatorFailsWithoutWorkspace() {
        // 与 BuildPassValidator 不同：无工作区时测试无法验证，按"未通过"处理（更保守）。
        // 引擎侧 registerDefaultValidators 在 workspace 为空时不注册本验证器，故无活路径受害者。
        assertFalse(new TestsPassValidator(null).validate("test").isPassed());
        assertFalse(new TestsPassValidator("").validate("test").isPassed());
    }

    @Test
    void testsValidatorFailsWhenNoBuildToolDetected() {
        // 未检测到项目类型时同样保守失败
        assertFalse(new TestsPassValidator(System.getProperty("java.io.tmpdir")).validate("test").isPassed());
    }

    @Test
    void validationResultValueSemantics() {
        GoalValidator.ValidationResult passed = GoalValidator.ValidationResult.passed();
        GoalValidator.ValidationResult failed = GoalValidator.ValidationResult.failed("原因");

        assertTrue(passed.isPassed());
        assertEquals("", passed.detail());
        assertFalse(failed.isPassed());
        assertEquals("原因", failed.detail());
    }
}
