package org.noear.solon.ai.harness.extension.goal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.InterruptMode;
import org.noear.solon.ai.harness.InterruptPlan;
import org.noear.solon.scheduling.scheduled.JobHandler;
import org.noear.solon.scheduling.scheduled.manager.IJobManager;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用户停止会话时暂停 goal 的契约：
 * <ol>
 *   <li>{@code INTERRUPT_ALL}（一步式与两段式）回调 {@code onSessionInterrupt}：PURSUING → PAUSED 并摘调度；</li>
 *   <li>程序停流（{@code CANCEL_STREAM} / {@code PREEMPT_STREAM}）不动 goal；</li>
 *   <li>没有 goal、goal 已暂停时什么都不做；</li>
 *   <li>轮次进行中被暂停、随后以异常结束：不记错误、不覆盖上一轮结果（对照：未暂停时照常记错误）。</li>
 * </ol>
 */
class GoalSessionInterruptTest {
    @TempDir
    Path workspace;

    private final Map<String, AgentSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, JobHandler> jobs = new ConcurrentHashMap<>();

    private IJobManager jobManager() {
        return (IJobManager) Proxy.newProxyInstance(IJobManager.class.getClassLoader(),
                new Class<?>[]{IJobManager.class}, (p, method, args) -> {
                    switch (method.getName()) {
                        case "jobAdd":
                            jobs.put((String) args[0], (JobHandler) args[2]);
                            return null;
                        case "jobExists":
                            return jobs.containsKey((String) args[0]);
                        case "jobRemove":
                            jobs.remove((String) args[0]);
                            return null;
                        case "isStarted":
                        case "hasJobInterceptor":
                            return false;
                        case "hashCode":
                            return System.identityHashCode(p);
                        case "equals":
                            return p == args[0];
                        case "toString":
                            return "GoalFakeJobManager";
                        default:
                            return null;
                    }
                });
    }

    private HarnessEngine engine(GoalExtension ext) {
        return HarnessEngine.of(workspace.toString(), workspace.resolve("home").toString())
                .sessionProvider((id, parentId, c) -> c ? sessions.computeIfAbsent(id, InMemoryAgentSession::of) : sessions.get(id))
                .extensionAdd(ext)
                .build();
    }

    private String createGoal(GoalExtension ext, String sid) {
        sessions.computeIfAbsent(sid, InMemoryAgentSession::of);
        assertTrue(ext.create(sid, GoalSpec.of("目标").runNow(false)).isOk());
        assertTrue(ext.hasActiveGoals(), "创建后应挂上调度");
        return sid;
    }

    @Test
    void userInterruptPausesGoalAndUnschedules() {
        GoalExtension ext = GoalExtension.builder().jobManager(jobManager()).build();
        HarnessEngine engine = engine(ext);
        String sid = createGoal(ext, "s1");

        assertTrue(engine.interrupt(sessions.get(sid), InterruptMode.INTERRUPT_ALL).isOk());

        assertEquals(GoalStatus.PAUSED, ext.get(sid).getStatus(), "用户停止必须暂停 goal");
        assertFalse(ext.hasActiveGoals(), "暂停必须摘掉调度，否则下一拍又会拉起一轮");
        assertTrue(ext.resume(sid).isOk(), "暂停后可以恢复");
        assertEquals(GoalStatus.PURSUING, ext.get(sid).getStatus());
    }

    @Test
    void twoPhaseInterruptAlsoPausesBeforeCommit() {
        GoalExtension ext = GoalExtension.builder().jobManager(jobManager()).build();
        HarnessEngine engine = engine(ext);
        String sid = createGoal(ext, "s1");

        try (InterruptPlan plan = engine.prepareInterrupt(sessions.get(sid))) {
            // 第一段（停流之前）就已暂停
            assertEquals(GoalStatus.PAUSED, ext.get(sid).getStatus());
            assertFalse(ext.hasActiveGoals());
            plan.commit();
        }
    }

    @Test
    void programStopDoesNotTouchGoal() {
        GoalExtension ext = GoalExtension.builder().jobManager(jobManager()).build();
        HarnessEngine engine = engine(ext);
        String sid = createGoal(ext, "s1");

        assertTrue(engine.interrupt(sessions.get(sid), InterruptMode.CANCEL_STREAM).isOk());
        assertTrue(engine.interrupt(sessions.get(sid), InterruptMode.PREEMPT_STREAM).isOk());

        assertEquals(GoalStatus.PURSUING, ext.get(sid).getStatus(), "程序停流不得改 goal 状态");
        assertTrue(ext.hasActiveGoals(), "程序停流不得摘调度");
    }

    @Test
    void interruptWithoutGoalOrAlreadyPausedIsNoop() {
        GoalExtension ext = GoalExtension.builder().jobManager(jobManager()).build();
        HarnessEngine engine = engine(ext);

        sessions.computeIfAbsent("empty", InMemoryAgentSession::of);
        assertTrue(engine.interrupt(sessions.get("empty"), InterruptMode.INTERRUPT_ALL).isOk());
        assertNull(ext.get("empty"));

        String sid = createGoal(ext, "s1");
        assertTrue(ext.pause(sid).isOk());
        assertTrue(engine.interrupt(sessions.get(sid), InterruptMode.INTERRUPT_ALL).isOk());
        assertEquals(GoalStatus.PAUSED, ext.get(sid).getStatus());
    }

    @Test
    void roundFailingAfterUserInterruptIsNotCountedAsError() {
        GoalExtension ext = GoalExtension.builder().jobManager(jobManager()).build();
        HarnessEngine engine = engine(ext);
        String sid = createGoal(ext, "s1");
        AgentSession session = sessions.get(sid);
        // 执行器内模拟：轮次进行中用户点了停止，随后这一轮以异常结束（直接用引擎时被中断的 call() 会这样）
        ext.addExecutor("test", null, (id, prompt, locale) -> {
            engine.interrupt(session, InterruptMode.INTERRUPT_ALL);
            throw new IllegalStateException("interrupted");
        });

        assertFalse(ext.getGoalRuntime().runRound(session));

        Goal gs = ext.getGoalRuntime().state(session);
        assertEquals(GoalStatus.PAUSED, gs.getStatus());
        assertEquals(0, gs.getConsecutiveErrors(), "被用户停掉的一轮不算错误");
        String last = gs.getLastResult();
        assertTrue(last == null || last.startsWith("error") == false, "不得用错误覆盖上一轮结果: " + last);
    }

    @Test
    void roundFailingWithoutInterruptIsStillCountedAsError() {
        // 对照：未被暂停时，异常照常参与熔断计数
        GoalExtension ext = GoalExtension.builder().jobManager(jobManager()).build();
        engine(ext);
        String sid = createGoal(ext, "s1");
        AgentSession session = sessions.get(sid);
        ext.addExecutor("test", null, (id, prompt, locale) -> {
            throw new IllegalStateException("boom");
        });

        ext.getGoalRuntime().runRound(session);

        Goal gs = ext.getGoalRuntime().state(session);
        assertEquals(GoalStatus.PURSUING, gs.getStatus());
        assertEquals(1, gs.getConsecutiveErrors());
        assertTrue(gs.getLastResult().startsWith("error"), gs.getLastResult());
    }
}
