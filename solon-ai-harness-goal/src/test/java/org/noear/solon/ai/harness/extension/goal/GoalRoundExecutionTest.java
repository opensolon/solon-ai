package org.noear.solon.ai.harness.extension.goal;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.harness.extension.goal.validate.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GoalRuntime 轮次执行与验证器链。
 *
 * <p>用真实执行通道（{@link GoalRuntime#addExecutor}）+ 内存会话驱动 runRound，覆盖守卫、预算收尾、
 * 完成复核、异常分级熔断与验证器链，不依赖 JobManager。</p>
 */
class GoalRoundExecutionTest {

    /** 记录每轮 prompt 并返回可编排结果的执行者。 */
    private static class FakeBridge {
        final List<String> prompts = new ArrayList<>();
        GoalOutcome next = TestOutcomes.ofText("ok");
        Exception failure;
        /** 轮次内回调：模拟 goal_update(complete) 在工具执行中把状态置为 ACHIEVED。 */
        Runnable duringRound;

        final GoalExecutor handler = (sessionId, prompt) -> {
            prompts.add(prompt);
            if (duringRound != null) {
                duringRound.run();
            }
            if (failure != null) {
                // 执行通道允许抛出任意异常，引擎按 ErrorClassifier 分级
                throw new RuntimeException(failure);
            }
            return next;
        };
    }

    private static GoalRuntime engine(GoalExecutor handler, int maxErrors) {
        GoalRuntime e = new GoalRuntime((org.noear.solon.ai.harness.HarnessEngine) null,
                BudgetThresholds.defaults(), 3, maxErrors, false);
        if (handler != null) {
            e.addExecutor("test", null, handler);
        }
        return e;
    }

    private static AgentSession session() {
        return InMemoryAgentSession.of("sess-round");
    }

    // ========== 守卫 ==========

    @Test
    void runRoundReturnsFalseWithoutGoal() {
        FakeBridge fb = new FakeBridge();
        assertFalse(engine(fb.handler, 3).runRound(session()));
        assertTrue(fb.prompts.isEmpty());
    }

    @Test
    void runRoundSkipsWhenNoHandlerRegistered() {
        GoalRuntime e = engine(null, 3);
        AgentSession s = session();
        e.attach(s, "目标", 0, 0, 0);
        assertFalse(e.runRound(s));
    }

    @Test
    void runRoundDelegatesBusyCheckToSessionStatus() {
        // 忙判定不再由宿主编个谓词注入，而是从会话快照派生（SessionStatus.isOccupied）。
        // 引擎未注入时无法读取快照，因此不跳过——这是「委派」而非「缺省放行」：
        // 真实宿主（GoalExtension）总是带着 engine，故生产路径恒走快照判定。
        FakeBridge fb = new FakeBridge();
        GoalRuntime e = engine(fb.handler, 3);
        AgentSession s = session();
        e.attach(s, "目标", 0, 0, 0);

        e.runRound(s);
        assertEquals(1, fb.prompts.size(), "无 engine 时不拦截，说明判定确已委派给会话快照");
    }

    @Test
    void runRoundRejectsReentrantCall() {
        FakeBridge fb = new FakeBridge();
        GoalRuntime e = engine(fb.handler, 3);
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
        fb.next = TestOutcomes.ofText("完成了第一步");
        GoalRuntime e = engine(fb.handler, 3);
        AgentSession s = session();
        GoalState gs = e.attach(s, "目标", 0, 0, 0);

        assertTrue(e.runRound(s));
        assertEquals(1, gs.getCurrentIteration());
        // 执行者返回真实 GoalOutcome，token 直接来自结果（不再靠文本长度估算）
        assertTrue(gs.getConsumedTokens() > 0);
        assertEquals("完成了第一步", gs.getLastResult());
        assertEquals(1, fb.prompts.size());
        // 运行态标记必须清理，否则后续轮次永远被判重入
        assertNull(s.attrs().get("session.goal.running"));
    }

    @Test
    void runRoundAccumulatesTokensAcrossRounds() {
        FakeBridge fb = new FakeBridge();
        GoalRuntime e = engine(fb.handler, 3);
        AgentSession s = session();
        GoalState gs = e.attach(s, "目标", 0, 0, 0);

        e.runRound(s);
        long afterFirst = gs.getConsumedTokens();
        fb.next = TestOutcomes.ofText("第二轮的文本明显更长一些用于累计验证");
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
        fb.next = TestOutcomes.ofText(text(60));
        GoalRuntime e = engine(fb.handler, 3);
        AgentSession s = session();
        GoalState gs = e.attach(s, "目标", 0, 0, 0);

        e.runRound(s);
        e.runRound(s);

        assertEquals(1, gs.getStagnationCount());
    }

    @Test
    void runRoundResetsStagnationWhenFingerprintChanges() {
        FakeBridge fb = new FakeBridge();
        GoalRuntime e = engine(fb.handler, 3);
        AgentSession s = session();
        GoalState gs = e.attach(s, "目标", 0, 0, 0);

        // 指纹按长度分桶（每 200 字符一档），两轮必须落在不同桶才判定"有进展"
        fb.next = TestOutcomes.ofText(text(30));
        e.runRound(s);
        fb.next = TestOutcomes.ofText(text(120));
        e.runRound(s);

        assertEquals(0, gs.getStagnationCount());
    }

    // ========== 完成判定与证据复核 ==========

    @Test
    void achievedGoalStopsRounds() {
        FakeBridge fb = new FakeBridge();
        GoalRuntime e = engine(fb.handler, 3);
        AgentSession s = session();
        GoalState gs = e.attach(s, "分析现状", 0, 0, 0);
        gs.achieve();

        assertFalse(e.runRound(s), "已达成的目标不再续行");
    }

    @Test
    void completionRejectedWithoutToolEvidenceForActionGoal() {
        FakeBridge fb = new FakeBridge();
        // 执行型目标 + 本轮无工具调用 → 完成声明被拒
        fb.next = TestOutcomes.ofText("我做完了");
        GoalRuntime e = engine(fb.handler, 3);
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
        fb.next = TestOutcomes.of(true, 10, "已创建文件");
        GoalRuntime e = engine(fb.handler, 3);
        AgentSession s = session();
        GoalState gs = e.attach(s, "创建一份文件", 0, 0, 0);
        gs.achieve();

        assertFalse(e.runRound(s));
        assertEquals(GoalSnapshot.Status.ACHIEVED, gs.getStatus());
    }

    @Test
    void nonActionGoalNeedsNoEvidence() {
        FakeBridge fb = new FakeBridge();
        fb.next = TestOutcomes.ofText("分析完毕");
        GoalRuntime e = engine(fb.handler, 3);
        AgentSession s = session();
        GoalState gs = e.attach(s, "分析现状", 0, 0, 0);
        fb.duringRound = gs::achieve;

        assertFalse(e.runRound(s));
        assertEquals(GoalSnapshot.Status.ACHIEVED, gs.getStatus());
    }

    /**
     * 跨入口契约：工具证据必须来自执行器返回值，而非会话 attrs 旁路。
     *
     * <p>旧实现靠宿主往 attrs 写 {@code _loop_last_has_tool_calls}，而只有桌面端写——
     * CLI/Web 恒不写，于是 {@code currentRoundHasActionEvidence} 返回 null，
     * {@code Boolean.FALSE.equals(null)} 为 false，goal_update(complete) 的校验静默失效。
     * 现在四个入口都必须显式提供，证据随返回值回流。</p>
     */
    @Test
    void actionEvidenceComesFromHandlerResultNotSessionAttrs() {
        FakeBridge fb = new FakeBridge();
        fb.next = TestOutcomes.of(true, 7, "已写入");
        GoalRuntime e = engine(fb.handler, 3);
        AgentSession s = session();
        e.attach(s, "创建一份文件", 0, 0, 0);

        e.runRound(s);

        assertEquals(Boolean.TRUE, e.currentRoundHasActionEvidence(s),
                "证据必须随返回值回流到 GoalState");
    }

    /**
     * 时序敏感：上一轮的 TRUE 不得泄漏到本轮的执行期。
     *
     * <p>{@code goal_update(complete)} 是在 {@code execute} 内部（工具阶段）执行的，
     * 而本轮证据要到 {@code execute} 返回后才写入。若不在调用前置空，模型在「上轮有
     * 工具调用、本轮只有空谈」时会读到陈旧的 TRUE → 提前放行 → 本轮白跑后再被复核
     * 拒绝。本用例在 handler 内部读取（而非执行后断言），才能真正锁住该时序。</p>
     */
    @Test
    void previousRoundEvidenceMustNotLeakIntoCurrentRound() {
        FakeBridge fb = new FakeBridge();
        fb.next = TestOutcomes.of(true, 5, "第一轮确实执行了工具");
        GoalRuntime e = engine(fb.handler, 3);
        AgentSession s = session();
        e.attach(s, "创建一份文件", 0, 0, 0);

        e.runRound(s);
        assertEquals(Boolean.TRUE, e.currentRoundHasActionEvidence(s), "首轮结束后应有 TRUE");

        // 第二轮：无工具调用；在 handler 执行期间读取，模拟 goal_update 在工具阶段取值
        Boolean[] duringRound = new Boolean[1];
        fb.next = TestOutcomes.of(false, 5, "本轮只说了说，没有实际动手");
        fb.duringRound = () -> duringRound[0] = e.currentRoundHasActionEvidence(s);
        e.runRound(s);

        assertNull(duringRound[0],
                "执行期间必须为 null：读到上一轮的 TRUE 会让完成声明被误放行");
        assertEquals(Boolean.FALSE, e.currentRoundHasActionEvidence(s),
                "本轮结束后应落定为 FALSE");
    }

    /** 执行器未执行工具时，必须得到明确的 FALSE（而非 null），否则完成校验会被跳过。 */
    @Test
    void absentToolEvidenceYieldsFalseNotNull() {
        FakeBridge fb = new FakeBridge();
        fb.next = TestOutcomes.ofText("我只说了说");
        GoalRuntime e = engine(fb.handler, 3);
        AgentSession s = session();
        e.attach(s, "创建一份文件", 0, 0, 0);

        e.runRound(s);

        assertEquals(Boolean.FALSE, e.currentRoundHasActionEvidence(s),
                "无工具调用必须是 FALSE：null 会让 Boolean.FALSE.equals(null) 放过校验");
    }

    // ========== 预算守卫 ==========

    @Test
    void tokenBudgetExceededTriggersWrapUpAndStops() {
        FakeBridge fb = new FakeBridge();
        fb.next = TestOutcomes.ofText("最终总结");
        GoalRuntime e = engine(fb.handler, 3);
        AgentSession s = session();
        GoalState gs = e.attach(s, "分析现状", 100, 0, 0);
        gs.addTokens(200); // 已超预算
        e.commit(s, gs);

        assertFalse(e.runRound(s));
        // 一轮收尾（wrap-up），然后标记预算受限
        assertEquals(1, fb.prompts.size());
        assertEquals(GoalSnapshot.Status.BUDGET_LIMITED, gs.getStatus());
    }

    @Test
    void iterationLimitStopsRounds() {
        FakeBridge fb = new FakeBridge();
        GoalRuntime e = engine(fb.handler, 3);
        AgentSession s = session();
        GoalState gs = e.attach(s, "分析现状", 0, 0, 1);
        gs.setCurrentIteration(1);
        e.commit(s, gs);

        assertFalse(e.runRound(s));
        assertTrue(fb.prompts.isEmpty(), "轮次超限应在执行前就停下");
        assertEquals(GoalSnapshot.Status.ITERATION_LIMITED, gs.getStatus());
    }

    @Test
    void durationBudgetExceededTriggersWrapUp() throws Exception {
        FakeBridge fb = new FakeBridge();
        GoalRuntime e = engine(fb.handler, 3);
        AgentSession s = session();
        // maxDurationMs=1：attach 后已必然超时，无需改内部时间戳
        GoalState gs = e.attach(s, "分析现状", 0, 1, 0);
        Thread.sleep(5); // 确保 elapsed >= 1ms，避免同毫秒内取整
        e.commit(s, gs);

        assertFalse(e.runRound(s));
        assertEquals(1, fb.prompts.size());
        assertEquals(GoalSnapshot.Status.BUDGET_LIMITED, gs.getStatus());
    }

    // ========== 异常分级熔断 ==========

    /**
     * 无执行器认领时不得记 ok、不得清零熔断计数。
     *
     * <p>与 loop 侧「无认领不记 ok」同口径。若不拦住：目标看起来一直在跑（lastResult 有值、
     * 时间戳在更新），实际什么都没做；更阴的是 {@code resetConsecutiveErrors()} 每轮把熔断计数清零，
     * 于是异常分级熔断永远不触发。</p>
     */
    @Test
    void unclaimedRoundMustNotRecordOkNorResetErrorCounter() {
        // 先让一次真实失败把连续错误计数抬起来
        FakeBridge fb = new FakeBridge();
        fb.failure = new IllegalStateException("boom");
        GoalRuntime e = engine(fb.handler, 99);
        AgentSession s = session();
        GoalState gs = e.attach(s, "分析现状", 0, 0, 0);
        e.runRound(s);
        int errorsAfterFailure = gs.getConsecutiveErrors();
        assertTrue(errorsAfterFailure > 0, "前置失败应抬升连续错误计数");

        // 换成「不认领」的执行器：谓词不命中本会话，且没有内置兜底
        GoalRuntime noClaim = new GoalRuntime((org.noear.solon.ai.harness.HarnessEngine) null,
                BudgetThresholds.defaults(), 3, 99, false);
        noClaim.addExecutor("web", id -> false, (sessionId, prompt) -> TestOutcomes.ofText("不应执行"));
        AgentSession s2 = session();
        GoalState gs2 = noClaim.attach(s2, "分析现状", 0, 0, 0);

        assertNull(gs2.getLastResult(), "本轮不得产出结果文本");
        noClaim.runRound(s2);

        String last = gs2.getLastResult();
        assertNotNull(last, "无认领必须给出可见说明，不能无交代");
        assertFalse("ok".equals(last),
                "无认领时不得记 ok：否则表现为「目标在跑、实际什么都没做」");
        assertTrue(last.contains("no-executor"),
                "应当把「无执行器认领」这一事实写进 lastResult，实际：" + last);
        assertEquals(0, gs2.getConsecutiveErrors(), "未发生真实执行，不应有错误计数变化");
        assertEquals(0, gs2.getCurrentIteration(), "无认领的一轮不得计入迭代");
    }

    /** skip：本通道认领但此刻不宜执行——不计错误、不推进、不改结果文本，目标保持活跃。 */
    @Test
    void skippedRoundKeepsStateUntouched() {
        GoalRuntime e = engine((sessionId, prompt) -> GoalOutcome.skip("terminal busy"), 3);
        AgentSession s = session();
        GoalState gs = e.attach(s, "分析现状", 0, 0, 0);

        assertTrue(e.runRound(s), "skip 后仍可继续下一轮");
        assertNull(gs.getLastResult(), "skip 不写结果文本");
        assertEquals(0, gs.getCurrentIteration(), "skip 不计入迭代");
        assertEquals(0, gs.getConsecutiveErrors(), "skip 不计错误");
        assertEquals(GoalSnapshot.Status.PURSUING, gs.getStatus());
    }

    /** failed 与执行器返回 null 都按失败计（参与错误熔断），不再有「null = 穿透」。 */
    @Test
    void failedOrNullOutcomeCountsAsError() {
        GoalRuntime e = engine((sessionId, prompt) -> GoalOutcome.failed("upstream 500"), 99);
        AgentSession s = session();
        GoalState gs = e.attach(s, "分析现状", 0, 0, 0);
        e.runRound(s);
        assertEquals(1, gs.getConsecutiveErrors());
        assertTrue(gs.getLastResult().contains("upstream 500"), gs.getLastResult());

        GoalRuntime n = engine((sessionId, prompt) -> null, 99);
        AgentSession s2 = session();
        GoalState gs2 = n.attach(s2, "分析现状", 0, 0, 0);
        n.runRound(s2);
        assertEquals(1, gs2.getConsecutiveErrors(), "返回 null 是编程错误，按失败处理");
    }

    /** 命中的首个执行器即本轮执行者：其 skip 不会被转交给后注册的执行器。 */
    @Test
    void firstClaimingExecutorIsFinalNoPassThrough() {
        GoalRuntime e = engine(null, 3);
        List<String> calls = new ArrayList<>();
        e.addExecutor("a", id -> true, (sid, p) -> {
            calls.add("a");
            return GoalOutcome.skip("busy");
        });
        e.addExecutor("b", id -> true, (sid, p) -> {
            calls.add("b");
            return TestOutcomes.ofText("from-b");
        });
        AgentSession s = session();
        e.attach(s, "分析现状", 0, 0, 0);
        e.runRound(s);
        assertEquals(java.util.Collections.singletonList("a"), calls);
    }

    @Test
    void sameErrorTypeThreeTimesBlocks() {
        FakeBridge fb = new FakeBridge();
        GoalRuntime e = engine(fb.handler, 99);
        AgentSession s = session();
        GoalState gs = e.attach(s, "分析现状", 0, 0, 0);

        fb.failure = new IllegalStateException("boom");
        e.runRound(s);
        e.runRound(s);
        e.runRound(s);

        assertEquals(GoalSnapshot.Status.BLOCKED, gs.getStatus());
    }

    @Test
    void nonRecoverableErrorBlocksAfterTwo() {
        FakeBridge fb = new FakeBridge();
        GoalRuntime e = engine(fb.handler, 99);
        AgentSession s = session();
        GoalState gs = e.attach(s, "分析现状", 0, 0, 0);

        // SSL 属于不可恢复错误：两次同类即熔断
        fb.failure = new javax.net.ssl.SSLHandshakeException("bad cert");
        e.runRound(s);
        e.runRound(s);

        assertEquals(GoalSnapshot.Status.BLOCKED, gs.getStatus());
    }

    @Test
    void consecutiveErrorsThresholdBlocks() {
        FakeBridge fb = new FakeBridge();
        GoalRuntime e = engine(fb.handler, 2);
        AgentSession s = session();
        GoalState gs = e.attach(s, "分析现状", 0, 0, 0);

        // 两种不同类型错误各一次：同类型计数只有 1，但总数达到阈值 2
        fb.failure = new IllegalStateException("a");
        e.runRound(s);
        fb.failure = new IllegalArgumentException("b");
        e.runRound(s);

        assertEquals(GoalSnapshot.Status.BLOCKED, gs.getStatus());
    }

    @Test
    void failedRoundClearsRunningFlag() {
        FakeBridge fb = new FakeBridge();
        fb.failure = new IllegalStateException("boom");
        GoalRuntime e = engine(fb.handler, 3);
        AgentSession s = session();
        e.attach(s, "分析现状", 0, 0, 0);

        e.runRound(s);

        assertNull(s.attrs().get("session.goal.running"), "异常路径也必须清理重入标记");
    }

    @Test
    void errorSummaryIsTruncatedAndClassified() {
        assertEquals("IllegalStateException", GoalRuntime.extractErrorSummary(new IllegalStateException()));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            sb.append('x');
        }
        String summary = GoalRuntime.extractErrorSummary(new RuntimeException(sb.toString()));
        assertEquals(203, summary.length());
        assertTrue(summary.endsWith("..."));
    }

    @Test
    void nonRecoverableClassification() {
        assertTrue(GoalRuntime.isNonRecoverable("SSL"));
        assertTrue(GoalRuntime.isNonRecoverable("HTTP_4XX"));
        assertFalse(GoalRuntime.isNonRecoverable("HTTP_5XX"));
        assertFalse(GoalRuntime.isNonRecoverable(null));
    }

    // ========== 单槽读写与更新 ==========

    @Test
    void updateThrowsWhenNoGoal() {
        GoalRuntime e = engine(null, 3);
        assertThrows(IllegalStateException.class,
                () -> e.update(session(), "新目标", 0, 0));
    }

    @Test
    void updateReplacesObjectiveAndBudgets() {
        GoalRuntime e = engine(null, 3);
        AgentSession s = session();
        e.attach(s, "旧目标", 0, 0, 0);

        e.update(s, "新目标", 999, 7);

        GoalState gs = e.state(s);
        assertEquals("新目标", gs.getObjective());
        assertEquals(999, gs.getMaxTokens());
        assertEquals(7, gs.getMaxIterations());
    }

    @Test
    void hasGoalFalseForTerminal() {
        GoalRuntime e = engine(null, 3);
        AgentSession s = session();
        GoalState gs = e.attach(s, "目标", 0, 0, 0);
        assertTrue(e.hasGoal(s));

        gs.achieve();
        e.commit(s, gs);
        assertFalse(e.hasGoal(s), "终态不再算有活跃目标");
    }

    @Test
    void listenerReceivesChanges() {
        GoalRuntime e = engine(null, 3);
        AgentSession s = session();
        List<String> events = new ArrayList<>();
        e.addListener((sessionId, state, removed) ->
                events.add(state.getObjective() + ":" + removed));
        e.addListener(null); // null 监听器应被忽略

        GoalState gs = e.attach(s, "目标A", 0, 0, 0);
        e.clear(s);

        assertFalse(events.isEmpty());
        assertTrue(events.get(0).startsWith("目标A:"));
        assertNotNull(gs);
    }

    @Test
    void snapshotViewCarriesContractKeys() {
        GoalRuntime e = engine(null, 3);
        AgentSession s = session();
        e.attach(s, "目标", 500, 0, 3);

        Map<String, Object> item = e.snapshot(s).toMap();

        assertEquals("GOAL", item.get("type"));
        assertEquals("目标", item.get("prompt"));
        assertEquals(500L, item.get("maxTokens"));
        // 预算明细在嵌套 goal 内（与前端 goal.* 约定对齐）
        Map<?, ?> goal = (Map<?, ?>) item.get("goal");
        assertEquals(3, goal.get("maxIterations"));
        assertEquals(500L, goal.get("maxTokens"));
    }

    @Test
    void countUnfinishedTodosHandlesMissingInputs() {
        GoalRuntime e = engine(null, 3);
        assertEquals(0, e.countUnfinishedTodos(null, "ws", "s1"));
        assertEquals(0, e.countUnfinishedTodos(null, "ws", null));
    }

    @Test
    void countUnfinishedCheckboxesCountsOnlyPending() {
        String todo = "- [ ] 未开始\n- [/] 进行中\n- [x] 已完成\n- 普通行\n";
        assertEquals(2, GoalRuntime.countUnfinishedCheckboxes(todo));
        assertEquals(0, GoalRuntime.countUnfinishedCheckboxes(null));
    }

    @Test
    void isTerminalStatusHandlesUnknown() {
        assertTrue(GoalRuntime.isTerminalStatus("ACHIEVED"));
        assertFalse(GoalRuntime.isTerminalStatus("PURSUING"));
        assertFalse(GoalRuntime.isTerminalStatus("NOT_A_STATUS"));
        assertFalse(GoalRuntime.isTerminalStatus(null));
    }

    @Test
    void isValidatorEnabledReflectsConstruction() {
        assertFalse(engine(null, 3).isValidatorEnabled());
        assertTrue(new GoalRuntime((org.noear.solon.ai.harness.HarnessEngine) null, BudgetThresholds.defaults(), 3, 3, true)
                .isValidatorEnabled());
    }

    // ========== 验证器 ==========

    @Test
    void noopValidatorAlwaysPasses() {
        assertTrue(new NoopValidator().validate("任意条件", null).isPassed());
    }

    /** 不应被调用的执行口（探测不到项目类型时不跑命令） */
    private static final org.noear.solon.ai.harness.extension.goal.validate.CommandRunner NEVER = (c, d) -> {
        throw new AssertionError("should not run: " + c);
    };

    @Test
    void validatorsWithoutWorkspaceAreInconclusive() {
        // 无工作目录：无法判定（不再「无信息即通过」），去向由 ValidatorPolicy 决定
        assertTrue(new BuildPassValidator(NEVER).validate("build", null).isInconclusive());
        assertTrue(new BuildPassValidator(NEVER).validate("build", "").isInconclusive());
        assertTrue(new TestsPassValidator(NEVER).validate("test", null).isInconclusive());
        assertFalse(new TestsPassValidator(NEVER).validate("test", "").isPassed());
    }

    @Test
    void buildValidatorPassesWhenNoBuildToolDetected() {
        // 临时目录既无 pom.xml 也无 build.gradle → 没有可编译的对象，通过
        assertTrue(new BuildPassValidator(NEVER).validate("build", System.getProperty("java.io.tmpdir")).isPassed());
    }

    @Test
    void testsValidatorFailsWhenNoBuildToolDetected() {
        // 未检测到项目类型时保守失败（明确的 FAILED，不是无法判定）
        ValidationResult r = new TestsPassValidator(NEVER)
                .validate("test", System.getProperty("java.io.tmpdir"));
        assertFalse(r.isPassed());
        assertFalse(r.isInconclusive());
    }

    @Test
    void commandValidatorMapsRunnerResult(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Files.write(dir.resolve("pom.xml"), "<project/>".getBytes());
        String wd = dir.toString();
        java.util.List<String> ran = new java.util.ArrayList<>();

        assertTrue(new BuildPassValidator((c, d) -> {
            ran.add(c + "@" + d);
            return org.noear.solon.ai.talents.cli.CommandResult.completed(0, "ok");
        }).validate("build", wd).isPassed());
        assertEquals("mvn compile -q -o@" + wd, ran.get(0), "命令在本轮工作目录下经执行口运行");

        ValidationResult failed = new BuildPassValidator((c, d) ->
                org.noear.solon.ai.talents.cli.CommandResult.completed(1, "ERROR x")).validate("build", wd);
        assertFalse(failed.isPassed());
        assertFalse(failed.isInconclusive());
        assertTrue(failed.getDetail().contains("exit=1"));

        assertTrue(new BuildPassValidator((c, d) ->
                org.noear.solon.ai.talents.cli.CommandResult.timedOut("slow")).validate("build", wd).isInconclusive());
        assertTrue(new BuildPassValidator((c, d) ->
                org.noear.solon.ai.talents.cli.CommandResult.failed("sandbox down")).validate("build", wd).isInconclusive());
        assertTrue(new BuildPassValidator((c, d) -> {
            throw new IllegalStateException("boom");
        }).validate("build", wd).isInconclusive(), "异常不再即通过");
    }

    @Test
    void validatorPolicyDecidesInconclusive() {
        GoalRuntime rt = new GoalRuntime((org.noear.solon.ai.harness.HarnessEngine) null,
                BudgetThresholds.defaults(), 3, 3, true);
        rt.registerValidator(c -> c.contains("x"),
                (c, d) -> ValidationResult.inconclusive("timeout"));

        // 缺省 FAIL_OPEN（本地编码体验不变）
        assertTrue(rt.validate("x", "/w").isPassed());

        rt.configureValidation(org.noear.solon.ai.harness.extension.goal.validate.ValidatorPolicy.FAIL_CLOSED, 0);
        ValidationResult r = rt.validate("x", "/w");
        assertFalse(r.isPassed());
        assertFalse(r.isInconclusive(), "策略处理后只剩通过/未通过");
        assertTrue(r.getDetail().contains("timeout"));

        // 验证器抛异常同样按「无法判定」走策略
        rt.registerValidator(c -> c.contains("y"), (c, d) -> {
            throw new RuntimeException("boom");
        });
        assertFalse(rt.validate("y", "/w").isPassed());
    }

    @Test
    void runtimeWithoutEngineHasNoCommandRunner() {
        GoalRuntime rt = new GoalRuntime((org.noear.solon.ai.harness.HarnessEngine) null,
                BudgetThresholds.defaults(), 3, 3, true, true);
        assertEquals(org.noear.solon.ai.talents.cli.CommandResult.Status.FAILED,
                rt.runValidationCommand("echo hi", "/tmp").getStatus());
    }

    @Test
    void validationResultValueSemantics() {
        ValidationResult passed = ValidationResult.passed();
        ValidationResult failed = ValidationResult.failed("原因");

        assertTrue(passed.isPassed());
        assertEquals("", passed.getDetail());
        assertFalse(failed.isPassed());
        assertEquals("原因", failed.getDetail());
    }
}
