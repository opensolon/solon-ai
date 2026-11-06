package org.noear.solon.ai.harness.extension.goal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.harness.HarnessEngine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GoalExtension 装配与门面契约：
 * <ol>
 *   <li>builder 形态经 addExtension 后完成 GoalRuntime → 调度 → /goal 命令 → Talent 全链装配；</li>
 *   <li>门面一律按 sessionId 定位，对外只交只读快照 {@link GoalTask}；</li>
 *   <li>update 内含「暂停 → 停流 → 停等 → 改写 → 恢复」时序；</li>
 *   <li>开关、状态贡献与键登记。</li>
 * </ol>
 */
class GoalExtensionTest {
    @TempDir
    Path workspace;

    /** 会话实例共享：sessionProvider 按 id 复用，门面按 sessionId 才能解析到同一会话。 */
    private final Map<String, AgentSession> sessions = new java.util.concurrent.ConcurrentHashMap<>();

    private HarnessEngine.Builder engineBuilder() {
        return HarnessEngine.of(workspace.toString(), workspace.resolve("home").toString())
                .sessionProvider((id, c) -> c ? sessions.computeIfAbsent(id, k -> InMemoryAgentSession.of(k)) : sessions.get(id));
    }

    private GoalExtension attached() {
        GoalExtension ext = GoalExtension.builder().jobManager(null).build();
        engineBuilder().extensionAdd(ext).build();
        return ext;
    }

    private AgentSession session(String id) {
        return sessions.computeIfAbsent(id, k -> InMemoryAgentSession.of(k));
    }

    // ==================== 装配 ====================

    @Test
    void builderAssemblesFullChainAndRegistersCommand() {
        GoalExtension ext = GoalExtension.builder().jobManager(null).build();
        HarnessEngine engine = engineBuilder().extensionAdd(ext).build();

        assertNotNull(ext.getGoalRuntime(), "挂接后 GoalRuntime 应已组装");
        assertNull(ext.getGoalScheduler(), "jobManager(null) 应关闭调度");
        assertNotNull(engine.getCommandRegistry().find("goal"), "/goal 命令应已注册");
        assertNotNull(ext.getGoalTalent(), "Talent 应已装配");
        assertTrue(ext.getGoalRuntime().hasExecutor(), "宿主未注册执行器时必须装入内置缺省");
    }

    @Test
    void listenerIsPreregisteredThroughBuilderAndReceivesSnapshots() {
        List<GoalTask> received = new ArrayList<>();
        GoalExtension ext = GoalExtension.builder()
                .jobManager(null)
                .listener((sid, task, removed) -> received.add(task))
                .build();
        engineBuilder().extensionAdd(ext).build();

        ext.create("s-l", GoalSpec.of("目标"));
        assertEquals(1, received.size(), "builder 预注册的 listener 应收到创建通知");
        assertEquals("目标", received.get(0).getObjective());
    }

    @Test
    void commandRegistrationIsIdempotentWhenHostAlreadyRegistered() {
        GoalExtension first = attached();
        GoalCommand hostCommand = new GoalCommand(first);

        HarnessEngine engine = engineBuilder().build();
        engine.getCommandRegistry().register(hostCommand);
        engine.addExtension(GoalExtension.builder().jobManager(null).build());

        assertSame(hostCommand, engine.getCommandRegistry().find("goal"), "已注册的同名命令应保持原实例");
    }

    // ==================== 创建 / 读取 ====================

    @Test
    void createReturnsSnapshotAndGetLocatesByTaskId() {
        GoalExtension ext = attached();
        session("s1");

        GoalTask t = ext.create("s1", GoalSpec.of("让测试全绿").maxTokens(100).maxIterations(5));
        assertEquals("让测试全绿", t.getObjective());
        assertEquals(GoalTask.Status.PURSUING, t.getStatus());
        assertEquals(100L, t.getMaxTokens());
        assertEquals(5, t.getMaxIterations());

        assertEquals(t.getId(), ext.get("s1").getId());
        assertEquals(t.getId(), ext.get("s1", t.getId()).getId());
        assertEquals(t.getId(), ext.get("s1", null).getId(), "空 taskId = 当前 goal");
        assertNull(ext.get("s1", "not-a-goal-id"), "taskId 不匹配返回 null（混排列表分流）");
        assertNull(ext.get("unknown-session-without-goal"), "无 goal 返回 null");
    }

    @Test
    void createRejectsSecondActiveGoal() {
        GoalExtension ext = attached();
        ext.create("s1", GoalSpec.of("目标一"));
        assertThrows(IllegalStateException.class, () -> ext.create("s1", GoalSpec.of("目标二")), "单槽");
    }

    /** 展示正文与执行正文分离：一次落盘、一次广播，列表不会看到附件全文的中间态。 */
    @Test
    void createWithDisplayObjectiveBroadcastsOnce() {
        List<String> objectives = new ArrayList<>();
        GoalExtension ext = GoalExtension.builder()
                .jobManager(null)
                .listener((sid, task, removed) -> objectives.add(task.getObjective()))
                .build();
        engineBuilder().extensionAdd(ext).build();

        GoalTask t = ext.create("s-display", GoalSpec.of("执行正文（含附件全文）").displayObjective("展示正文").runNow(false));
        assertEquals("展示正文", t.getObjective());
        assertEquals(Collections.singletonList("展示正文"), objectives, "只能广播一次");

        GoalTask same = ext.create("s-same", GoalSpec.of("同一个目标").displayObjective("同一个目标"));
        assertEquals("同一个目标", same.getObjective());
        GoalTask none = ext.create("s-none", GoalSpec.of("另一个目标").displayObjective(null));
        assertEquals("另一个目标", none.getObjective(), "null 展示正文不得覆写");
    }

    /** 快照只读：宿主拿到的对象不能修改运行态。 */
    @Test
    void snapshotIsDetachedFromRuntime() {
        GoalExtension ext = attached();
        GoalTask before = ext.create("s1", GoalSpec.of("目标"));
        assertTrue(ext.pause("s1", before.getId()));
        assertEquals(GoalTask.Status.PURSUING, before.getStatus(), "旧快照不随运行态变化");
        assertEquals(GoalTask.Status.PAUSED, ext.get("s1").getStatus());
    }

    @Test
    void toMapIsTheStandardView() {
        GoalExtension ext = attached();
        GoalTask t = ext.create("s1", GoalSpec.of("目标").maxTokens(50));
        Map<String, Object> view = t.toMap();

        assertEquals(t.getId(), view.get("id"));
        assertEquals("GOAL", view.get("type"));
        assertEquals("目标", view.get("prompt"));
        assertEquals(true, view.get("enabled"));
        assertEquals(false, view.get("cancelled"));
        assertEquals(50L, view.get("maxTokens"));
        @SuppressWarnings("unchecked")
        Map<String, Object> goal = (Map<String, Object>) view.get("goal");
        assertEquals("PURSUING", goal.get("status"));
        assertEquals("目标", goal.get("condition"));
    }

    // ==================== 状态转移 ====================

    @Test
    void pauseResumeRemoveBySessionAndTaskId() {
        GoalExtension ext = attached();
        GoalTask t = ext.create("s-str", GoalSpec.of("目标"));

        assertFalse(ext.pause("s-str", "not-a-goal-id"), "taskId 不匹配不得误伤");
        assertTrue(ext.pause("s-str", t.getId()));
        assertFalse(ext.pause("s-str", t.getId()), "已暂停不可再暂停");
        assertEquals(GoalTask.Status.PAUSED, ext.get("s-str").getStatus());

        assertTrue(ext.resume("s-str", null));
        assertEquals(GoalTask.Status.PURSUING, ext.get("s-str").getStatus());
        assertFalse(ext.resume("s-str", null), "活跃态不可恢复");

        assertTrue(ext.remove("s-str", t.getId()));
        assertNull(ext.get("s-str"));
        assertFalse(ext.remove("s-str", t.getId()), "不存在返回 false");

        assertFalse(ext.pause("unknown", null));
        assertFalse(ext.resume("unknown", null));
        assertFalse(ext.trigger("unknown", null));
        assertNull(ext.update("unknown", null, GoalPatch.create().objective("x"), null));
    }

    @Test
    void triggerOnlyForActiveGoal() {
        GoalExtension ext = attached();
        GoalTask t = ext.create("s1", GoalSpec.of("目标"));
        ext.pause("s1", t.getId());
        assertFalse(ext.trigger("s1", t.getId()), "非活跃态不能触发");
    }

    // ==================== update ====================

    @Test
    void updateKeepsUnsetFieldsAndDoesNotChangeStatusWhenPaused() {
        GoalExtension ext = attached();
        GoalTask t = ext.create("s1", GoalSpec.of("旧目标").maxTokens(100L).maxDurationMs(5000L).maxIterations(7));
        ext.pause("s1", t.getId());

        AtomicInteger stops = new AtomicInteger();
        GoalTask after = ext.update("s1", t.getId(), GoalPatch.create().objective("新目标").maxTokens(200L),
                stops::incrementAndGet);

        assertEquals("新目标", after.getObjective());
        assertEquals(200L, after.getMaxTokens());
        assertEquals(5000L, after.getMaxDurationMs(), "null 时长不得清空原值");
        assertEquals(7, after.getMaxIterations(), "轮次应保持原值");
        assertEquals(GoalTask.Status.PAUSED, after.getStatus(), "非活跃时只改写、不改状态");
        assertEquals(0, stops.get(), "非活跃时无需停流");

        after = ext.update("s1", t.getId(), GoalPatch.create().objective("  ").maxDurationMs(9000L), null);
        assertEquals("新目标", after.getObjective(), "空白正文视为未设置");
        assertEquals(9000L, after.getMaxDurationMs());
    }

    @Test
    void updateActiveGoalStopsThenResumes() {
        List<String> order = new ArrayList<>();
        GoalExtension ext = GoalExtension.builder()
                .jobManager(null)
                .listener((sid, task, removed) -> order.add(task.getStatus().name()))
                .build();
        engineBuilder().extensionAdd(ext).build();
        GoalTask t = ext.create("s1", GoalSpec.of("目标"));
        order.clear();

        GoalTask after = ext.update("s1", t.getId(), GoalPatch.create().objective("新目标"), () -> order.add("stop"));

        assertEquals(Arrays.asList("PAUSED", "stop", "PAUSED", "PURSUING"), order,
                "时序：暂停 → 停流 → 改写 → 恢复");
        assertEquals(GoalTask.Status.PURSUING, after.getStatus());
        assertEquals("新目标", after.getObjective());
    }

    @Test
    void updateTerminalGoalIsRejected() {
        GoalExtension ext = attached();
        GoalTask t = ext.create("s1", GoalSpec.of("目标"));
        AgentSession s = session("s1");
        GoalState gs = ext.getGoalRuntime().state(s);
        gs.achieve();
        ext.getGoalRuntime().commit(s, gs);

        assertThrows(IllegalStateException.class,
                () -> ext.update("s1", t.getId(), GoalPatch.create().objective("x"), null));
    }

    // ==================== 停等（update 的内部依赖） ====================

    /** 轮次运行中停等会阻塞；轮次结束后被唤醒并返回 true（靠 attrs 上的 notify，不是轮询）。 */
    @Test
    void awaitRoundStoppedIsWokenByRoundEnd() throws Exception {
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);

        GoalRuntime engine = new GoalRuntime((HarnessEngine) null, BudgetThresholds.defaults(), 3, 3, false);
        engine.addExecutor("test", null, (sid, prompt) -> {
            entered.countDown();
            release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return TestOutcomes.ofText("done");
        });

        AgentSession session = InMemoryAgentSession.of("s-running");
        engine.attach(session, "目标", 0, 0, 0);
        assertTrue(engine.awaitRoundStopped(session, 5000L), "空闲时应即时返回");

        Thread worker = new Thread(() -> engine.runRound(session));
        worker.start();
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS), "轮次应已开始");
        assertTrue(engine.isRunning(session));
        assertFalse(engine.awaitRoundStopped(session, 100L), "轮次未结束时应超时返回 false");

        release.countDown();
        assertTrue(engine.awaitRoundStopped(session, 5000L), "轮次结束后应被唤醒");
        worker.join(5000L);
        assertFalse(engine.isRunning(session), "轮次结束后标记应已清除");
    }

    // ==================== 恢复 / 调度 ====================

    @Test
    void recoverIsNullSafeWithoutScheduler() {
        GoalExtension ext = attached();

        assertEquals(0, ext.recover(Arrays.asList("a", "b"), id -> GoalTask.Status.PURSUING));
        assertEquals(0, ext.recover(null, id -> GoalTask.Status.PURSUING));
        assertEquals(0, ext.recover(Collections.singletonList("a"), null));
        assertFalse(ext.hasActiveGoals(), "无调度器时恒为 false");
    }

    // ==================== 开关 / 状态贡献 / 键登记 ====================

    @Test
    void disableHidesCommandButKeepsItRegistered() {
        HarnessEngine engine = engineBuilder().build();
        GoalExtension ext = GoalExtension.builder().jobManager(null).build();
        engine.addExtension(ext);

        assertNotNull(engine.getCommandRegistry().find("goal"), "默认启用时可见");

        ext.setEnabled(false);

        assertNull(engine.getCommandRegistry().find("goal"), "禁用后命令不可见");
        assertNotNull(engine.getCommandRegistry().get("goal"), "禁用后仍可区分「已停用」");
        assertFalse(ext.isEnabled());
    }

    /** goal 状态经开放事实槽位进入 engine.status；不判开关。 */
    @Test
    void goalStatusIsContributedToEngineStatus() {
        GoalExtension ext = GoalExtension.builder().jobManager(null).build();
        HarnessEngine engine = engineBuilder().extensionAdd(ext).build();
        AgentSession session = session("s-status");

        assertNull(engine.status(session).get(GoalExtension.STATUS_GOAL), "无 goal 时为 null");

        GoalTask t = ext.create("s-status", GoalSpec.of("目标"));
        assertEquals(GoalTask.Status.PURSUING, engine.status(session).get(GoalExtension.STATUS_GOAL));

        ext.pause("s-status", t.getId());
        ext.setEnabled(false);
        assertEquals(GoalTask.Status.PAUSED, engine.status(session).get(GoalExtension.STATUS_GOAL),
                "事实口径不受开关影响");

        ext.remove("s-status", null);
        assertNull(engine.status(session).get(GoalExtension.STATUS_GOAL));
    }

    /** 插件键集中登记在 GoalAttrs，持久化键值不变（改值会丢存量 goal）。 */
    @Test
    void goalKeysAreRegisteredInGoalAttrs() {
        assertEquals("session.goal", GoalAttrs.GOAL);
        GoalExtension ext = attached();
        AgentSession session = session("s-attrs");
        ext.create("s-attrs", GoalSpec.of("目标"));

        assertTrue(session.getContext().data().get(GoalAttrs.GOAL) instanceof Map, "投影落在 GoalAttrs.GOAL");
        assertTrue(session.attrs().get(GoalAttrs.GOAL_STATE) instanceof GoalState, "运行态落在 GoalAttrs.GOAL_STATE");
    }
}
