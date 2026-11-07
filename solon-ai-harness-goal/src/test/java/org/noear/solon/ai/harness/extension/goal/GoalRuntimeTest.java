package org.noear.solon.ai.harness.extension.goal;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;


import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GoalRuntime 会话级单槽语义测试。
 *
 * <p>验证：投影落在 getContext().data()、单槽唯一性、hydrate、
 * 清除后投影移除、终端态可被覆盖。全部通过 AgentSession 接口，
 * 不依赖任何具体存储实现（内存/文件/Redis/JDBC 同构）。</p>
 */
class GoalRuntimeTest {

    private GoalRuntime newEngine() {
        // bridge 传 null 引擎：本轮测试不涉及执行
        return new GoalRuntime((org.noear.solon.ai.harness.HarnessEngine) null, BudgetThresholds.defaults(), 3, 3, false);
    }

    private AgentSession newSession() {
        return InMemoryAgentSession.of("sess-1");
    }

    @Test
    void attachStoresProjectionInSessionContext() {
        GoalRuntime engine = newEngine();
        AgentSession session = newSession();

        GoalState gs = engine.attach(session, "生成报告", 1000, 0, 0);

        assertNotNull(gs);
        Object projection = session.getContext().data().get(GoalAttrs.GOAL);
        assertTrue(projection instanceof Map, "投影必须是 Map（存储无关）");
        Map<?, ?> map = (Map<?, ?>) projection;
        assertEquals("生成报告", map.get("objective"));
        assertEquals("PURSUING", map.get("status"));
    }

    @Test
    void secondAttachIsRejectedWhileActive() {
        GoalRuntime engine = newEngine();
        AgentSession session = newSession();

        engine.attach(session, "目标一", 0, 0, 0);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> engine.attach(session, "目标二", 0, 0, 0));
        assertTrue(error.getMessage().contains("已有进行中的 Goal"));
    }

    @Test
    void terminalGoalCanBeReplaced() {
        GoalRuntime engine = newEngine();
        AgentSession session = newSession();

        GoalState first = engine.attach(session, "目标一", 0, 0, 0);
        first.achieve();
        engine.commit(session, first);

        GoalState second = engine.attach(session, "目标二", 0, 0, 0);

        assertEquals("目标二", second.getObjective());
        assertNotEquals(first.getId(), second.getId());
    }

    @Test
    void stateHydratesFromProjectionWhenRuntimeMissing() {
        GoalRuntime engine = newEngine();
        AgentSession session = newSession();
        engine.attach(session, "目标", 500, 0, 0);

        // 模拟进程重启：清掉运行态 attrs，只留上下文投影
        session.attrs().remove("session.goal.state");

        GoalState restored = engine.state(session);
        assertNotNull(restored, "必须能从投影 hydrate");
        assertEquals("目标", restored.getObjective());
        assertEquals(500, restored.getMaxTokens());
    }

    @Test
    void clearRemovesProjectionAndDisposesSlot() {
        GoalRuntime engine = newEngine();
        AgentSession session = newSession();
        engine.attach(session, "目标", 0, 0, 0);

        engine.clear(session);

        assertNull(engine.state(session));
        assertFalse(session.getContext().data().containsKey(GoalAttrs.GOAL),
                "清除后投影必须移除");
        // 清除后可重新创建
        assertNotNull(engine.attach(session, "新目标", 0, 0, 0));
    }

    @Test
    void pauseAndResumeFlipStatusAndPersist() {
        GoalRuntime engine = newEngine();
        AgentSession session = newSession();
        GoalState gs = engine.attach(session, "目标", 0, 0, 0);

        assertTrue(engine.pause(session, gs));
        assertEquals(GoalSnapshot.Status.PAUSED, gs.getStatus());
        assertEquals("PAUSED", ((Map<?, ?>) session.getContext().data().get(GoalAttrs.GOAL)).get("status"));

        assertTrue(engine.resume(session, gs));
        assertEquals(GoalSnapshot.Status.PURSUING, gs.getStatus());
    }

    @Test
    void pauseRejectsBlockedGoalWithoutCommittingFalseSuccess() {
        GoalRuntime engine = newEngine();
        AgentSession session = newSession();
        GoalState gs = engine.attach(session, "目标", 0, 0, 0);
        gs.markBlocked();

        assertFalse(engine.pause(session, gs), "BLOCKED 是熔断态，不得由 pause 转换");
        assertEquals(GoalSnapshot.Status.BLOCKED, gs.getStatus());
    }

    @Test
    void terminalStatusClassificationForRecovery() {
        assertTrue(GoalRuntime.isTerminalStatus("ACHIEVED"));
        assertTrue(GoalRuntime.isTerminalStatus("BUDGET_LIMITED"));
        assertTrue(GoalRuntime.isTerminalStatus("ITERATION_LIMITED"));
        assertFalse(GoalRuntime.isTerminalStatus("PURSUING"));
        assertFalse(GoalRuntime.isTerminalStatus("PAUSED"));
        assertFalse(GoalRuntime.isTerminalStatus("BLOCKED"));
        assertFalse(GoalRuntime.isTerminalStatus("CORRUPTED_VALUE"), "非法状态按非终态处理交由守卫兑底");
    }

    @Test
    void activeStatusClassificationForRecovery() {
        assertTrue(GoalRuntime.isActiveStatus("PURSUING"), "仅 PURSUING 需要重启后续跑");
        assertFalse(GoalRuntime.isActiveStatus("PAUSED"), "用户显式暂停，重启后保持静默");
        assertFalse(GoalRuntime.isActiveStatus("BLOCKED"));
        assertFalse(GoalRuntime.isActiveStatus("ACHIEVED"));
        assertFalse(GoalRuntime.isActiveStatus("CORRUPTED_VALUE"), "非法状态不自动续跑");
    }

    @Test
    void pausedGoalBlocksNewAttach() {
        GoalRuntime engine = newEngine();
        AgentSession session = newSession();
        GoalState gs = engine.attach(session, "目标", 0, 0, 0);
        engine.pause(session, gs);

        assertThrows(IllegalStateException.class, () -> engine.attach(session, "另一目标", 0, 0, 0));
    }

    @Test
    void runtimeFieldsSurviveProjectionRoundTrip() {
        GoalRuntime engine = newEngine();
        AgentSession session = newSession();
        GoalState gs = engine.attach(session, "构建并测试项目", 2000, 600_000, 10);

        gs.incrementIteration();
        gs.addTokens(321);
        gs.updateLastExecution("上一轮做了什么");
        gs.recordStagnation();
        engine.commit(session, gs);

        session.attrs().remove("session.goal.state");
        GoalState restored = engine.state(session);

        assertEquals(1, restored.getCurrentIteration());
        assertEquals(321, restored.getConsumedTokens());
        assertEquals("上一轮做了什么", restored.getLastResult());
        assertEquals(1, restored.getStagnationCount());
        assertEquals(600_000, restored.getMaxDurationMs());
        assertEquals(10, restored.getMaxIterations());
    }

    @Test
    void requiresActionEvidenceDetectsExecutionGoals() {
        assertTrue(GoalRuntime.requiresActionEvidence("create a file"));
        assertTrue(GoalRuntime.requiresActionEvidence("生成一份报告"));
        assertTrue(GoalRuntime.requiresActionEvidence("修复构建错误"));
        assertFalse(GoalRuntime.requiresActionEvidence("what is the capital of France"));
    }

    @Test
    void unfinishedCheckboxCounting() {
        String todo = "- [x] done\n- [ ] pending\n- [/] doing\n- [x] done2\n";
        assertEquals(2, GoalRuntime.countUnfinishedCheckboxes(todo));
    }
}
