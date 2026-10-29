package org.noear.solon.ai.harness.session.goal;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.harness.runtime.ExecutionBridge;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GoalEngine 会话级单槽语义测试。
 *
 * <p>验证：投影落在 getContext().data()、单槽唯一性、hydrate、
 * 清除后投影移除、终端态可被覆盖。全部通过 AgentSession 接口，
 * 不依赖任何具体存储实现（内存/文件/Redis/JDBC 同构）。</p>
 */
class GoalEngineTest {

    private GoalEngine newEngine() {
        // bridge 传 null 引擎：本轮测试不涉及执行
        return new GoalEngine(new ExecutionBridge(null), BudgetThresholds.defaults(), 3, 3, false);
    }

    private AgentSession newSession() {
        return InMemoryAgentSession.of("sess-1");
    }

    @Test
    void attachStoresProjectionInSessionContext() {
        GoalEngine engine = newEngine();
        AgentSession session = newSession();

        GoalState gs = engine.attach(session, "生成报告", 1000, 0, 0);

        assertNotNull(gs);
        Object projection = session.getContext().data().get(GoalEngine.KEY_GOAL);
        assertTrue(projection instanceof Map, "投影必须是 Map（存储无关）");
        Map<?, ?> map = (Map<?, ?>) projection;
        assertEquals("生成报告", map.get("objective"));
        assertEquals("PURSUING", map.get("status"));
    }

    @Test
    void secondAttachIsRejectedWhileActive() {
        GoalEngine engine = newEngine();
        AgentSession session = newSession();

        engine.attach(session, "目标一", 0, 0, 0);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> engine.attach(session, "目标二", 0, 0, 0));
        assertTrue(error.getMessage().contains("已有进行中的 Goal"));
    }

    @Test
    void terminalGoalCanBeReplaced() {
        GoalEngine engine = newEngine();
        AgentSession session = newSession();

        GoalState first = engine.attach(session, "目标一", 0, 0, 0);
        first.achieve();
        engine.commit(session, first);

        GoalState second = engine.attach(session, "目标二", 0, 0, 0);

        assertEquals("目标二", second.getCondition());
        assertNotEquals(first.getId(), second.getId());
    }

    @Test
    void stateHydratesFromProjectionWhenRuntimeMissing() {
        GoalEngine engine = newEngine();
        AgentSession session = newSession();
        engine.attach(session, "目标", 500, 0, 0);

        // 模拟进程重启：清掉运行态 attrs，只留上下文投影
        session.attrs().remove("session.goal.state");

        GoalState restored = engine.state(session);
        assertNotNull(restored, "必须能从投影 hydrate");
        assertEquals("目标", restored.getCondition());
        assertEquals(500, restored.getMaxTokens());
    }

    @Test
    void clearRemovesProjectionAndDisposesSlot() {
        GoalEngine engine = newEngine();
        AgentSession session = newSession();
        engine.attach(session, "目标", 0, 0, 0);

        engine.clear(session);

        assertNull(engine.state(session));
        assertFalse(session.getContext().data().containsKey(GoalEngine.KEY_GOAL),
                "清除后投影必须移除");
        // 清除后可重新创建
        assertNotNull(engine.attach(session, "新目标", 0, 0, 0));
    }

    @Test
    void pauseAndResumeFlipStatusAndPersist() {
        GoalEngine engine = newEngine();
        AgentSession session = newSession();
        GoalState gs = engine.attach(session, "目标", 0, 0, 0);

        assertTrue(engine.pause(session, gs));
        assertEquals(GoalState.Status.PAUSED, gs.getStatus());
        assertEquals("PAUSED", ((Map<?, ?>) session.getContext().data().get(GoalEngine.KEY_GOAL)).get("status"));

        assertTrue(engine.resume(session, gs));
        assertEquals(GoalState.Status.PURSUING, gs.getStatus());
    }

    @Test
    void pauseRejectsBlockedGoalWithoutCommittingFalseSuccess() {
        GoalEngine engine = newEngine();
        AgentSession session = newSession();
        GoalState gs = engine.attach(session, "目标", 0, 0, 0);
        gs.markBlocked();

        assertFalse(engine.pause(session, gs), "BLOCKED 是熔断态，不得由 pause 转换");
        assertEquals(GoalState.Status.BLOCKED, gs.getStatus());
    }

    @Test
    void terminalStatusClassificationForRecovery() {
        assertTrue(GoalEngine.isTerminalStatus("ACHIEVED"));
        assertTrue(GoalEngine.isTerminalStatus("BUDGET_LIMITED"));
        assertTrue(GoalEngine.isTerminalStatus("ITERATION_LIMITED"));
        assertFalse(GoalEngine.isTerminalStatus("PURSUING"));
        assertFalse(GoalEngine.isTerminalStatus("PAUSED"));
        assertFalse(GoalEngine.isTerminalStatus("BLOCKED"));
        assertFalse(GoalEngine.isTerminalStatus("CORRUPTED_VALUE"), "非法状态按非终态处理交由守卫兑底");
    }

    @Test
    void activeStatusClassificationForRecovery() {
        assertTrue(GoalEngine.isActiveStatus("PURSUING"), "仅 PURSUING 需要重启后续跑");
        assertFalse(GoalEngine.isActiveStatus("PAUSED"), "用户显式暂停，重启后保持静默");
        assertFalse(GoalEngine.isActiveStatus("BLOCKED"));
        assertFalse(GoalEngine.isActiveStatus("ACHIEVED"));
        assertFalse(GoalEngine.isActiveStatus("CORRUPTED_VALUE"), "非法状态不自动续跑");
    }

    @Test
    void pausedGoalBlocksNewAttach() {
        GoalEngine engine = newEngine();
        AgentSession session = newSession();
        GoalState gs = engine.attach(session, "目标", 0, 0, 0);
        engine.pause(session, gs);

        assertThrows(IllegalStateException.class, () -> engine.attach(session, "另一目标", 0, 0, 0));
    }

    @Test
    void runtimeFieldsSurviveProjectionRoundTrip() {
        GoalEngine engine = newEngine();
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
        assertTrue(GoalEngine.requiresActionEvidence("create a file"));
        assertTrue(GoalEngine.requiresActionEvidence("生成一份报告"));
        assertTrue(GoalEngine.requiresActionEvidence("修复构建错误"));
        assertFalse(GoalEngine.requiresActionEvidence("what is the capital of France"));
    }

    @Test
    void unfinishedCheckboxCounting() {
        String todo = "- [x] done\n- [ ] pending\n- [/] doing\n- [x] done2\n";
        assertEquals(2, GoalEngine.countUnfinishedCheckboxes(todo));
    }
}
