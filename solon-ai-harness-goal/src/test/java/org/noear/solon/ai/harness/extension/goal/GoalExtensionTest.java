package org.noear.solon.ai.harness.extension.goal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.harness.HarnessEngine;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GoalExtension 一键装配验证：
 * 1. builder 形态经 addExtension 后完成 bridge→GoalEngine→GoalScheduler→/goal 命令全链装配；
 * 2. 无 Solon 容器（纯 JUnit）时 scheduler(null) 关闭调度仍可装配（JobManager 不可用场景）；
 * 3. 共享 bridge 注入时不自建（与 loop 共用组合根资源）；
 * 4. TaskHandler 注册到位（宿主不传时内置缺省）；listener 预注册生效；
 * 5. 门面操作中转正确（state/attach 走组装出的 GoalEngine）；
 * 6. 命令注册幂等（宿主已注册同名命令时不覆盖）。
 */
class GoalExtensionTest {
    @TempDir
    Path workspace;

    /** 会话实例共享：::of 会忽略 id 每次新建，sessionOf(id) 将解析不到已 attach 的会话。 */
    private final java.util.Map<String, AgentSession> sessions = new java.util.concurrent.ConcurrentHashMap<>();

    private HarnessEngine.Builder engineBuilder() {
        return HarnessEngine.of(workspace.toString(), workspace.resolve("home").toString())
                .sessionProvider(id -> sessions.computeIfAbsent(id, k -> InMemoryAgentSession.of()));
    }

    @Test
    void builderAssemblesFullChainAndRegistersCommand() {
        AtomicInteger executed = new AtomicInteger();
        GoalExtension ext = GoalExtension.builder("ws-test")
                .executor((sid, prompt) -> {
                    executed.incrementAndGet();
                    return GoalRoundResult.ofText("done");
                })
                .scheduler(null)   // 纯 JUnit 无 Solon 容器：JobManager.getInstance() 会 NPE，显式关闭
                .build();

        HarnessEngine engine = engineBuilder().extensionAdd(ext).build();

        assertNotNull(ext.getGoalEngine(), "initialize 后 GoalEngine 应已组装");
        assertNull(ext.getGoalScheduler(), "scheduler(null) 应关闭调度");
        assertNotNull(engine.getCommandRegistry().find("goal"), "/goal 命令应已注册");

        // 门面操作走组装出的引擎
        AgentSession session = InMemoryAgentSession.of();
        assertNull(ext.state(session));
        GoalState gs = ext.getGoalEngine().attach(session, "测试目标", 0, 0, 0);
        assertEquals("测试目标", ext.state(session).getCondition());
        assertTrue(gs.getStatus().isActive());
    }

    @Test
    void taskExecutorIsRegisteredOnGoalEngine() {
        HarnessEngine engine = engineBuilder().build();
        AtomicInteger calls = new AtomicInteger();

        GoalExtension ext = GoalExtension.builder("ws-share")
                .executor((sid, prompt) -> {
                    calls.incrementAndGet();
                    return GoalRoundResult.ofText("ok");
                })
                .scheduler(null)
                .build();
        engine.addExtension(ext);

        // 执行通道已内联进 GoalEngine（不再有跨域共享桥）
        ext.getGoalEngine().execute("any-session", "prompt");
        assertEquals(1, calls.get(), "host taskExecutor 应注册到 GoalEngine 自己的执行通道");
    }

    /**
     * 宿主执行器必须优先于内置兜底中被考虑。
     *
     * <p>扩展在 {@code addExtension} 时装配、宿主入口（WsGate / CliShell）在构造时注册，
     * 因而宿主注册总是晚于内置兜底。若两者同在「按顺序取首个认领者」的同一个列表里，
     * 恒认领的兜底排在首位，宿主的流式投递永无机会被调用——而这一失败完全静默（仍能跑完轮次，
     * 只是输出丢失推流）。本用例锁定「兑底只能是最后一个被考虑的」。</p>
     */
    @Test
    void hostExecutorWinsOverDefaultFallback() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        GoalExtension ext = GoalExtension.builder("ws-priority")
                .executor((sid, prompt) -> GoalRoundResult.ofText("host"))
                .scheduler(null)
                .build();
        engineBuilder().extensionAdd(ext).build();

        GoalEngine ge = ext.getGoalEngine();
        // 模拟内置兑底（恒认领）——即使它先于宿主执行器存在，也不得抢走回合
        ge.setDefaultExecutor((sid, prompt) -> {
            fallbackCalls.incrementAndGet();
            return GoalRoundResult.ofText("fallback");
        });

        GoalRoundResult r = ge.execute("any", "p");

        assertEquals("host", r.getFinalResult(), "宿主执行器必须优先");
        assertEquals(0, fallbackCalls.get(), "兑底不得被调用");
    }

    @Test
    void defaultTaskExecutorIsInstalledWhenHostProvidesNone() {
        GoalExtension ext = GoalExtension.builder("ws-default")
                .scheduler(null)
                .build();
        engineBuilder().extensionAdd(ext).build();

        assertTrue(ext.getGoalEngine().hasExecutor(),
                "宿主未提供执行者时必须装入内置缺省，否则单测/无宿主场景无法跑轮次");
    }

    @Test
    void listenerIsPreregisteredThroughBuilder() {
        AtomicInteger notified = new AtomicInteger();
        GoalExtension ext = GoalExtension.builder("ws-l")
                .scheduler(null)
                .listener((sid, state, removed) -> notified.incrementAndGet())
                .build();

        HarnessEngine engine = engineBuilder().extensionAdd(ext).build();

        AgentSession session = InMemoryAgentSession.of();
        ext.getGoalEngine().attach(session, "目标", 0, 0, 0);
        assertEquals(1, notified.get(), "builder 预注册的 listener 应收到 attach 通知");
    }

    @Test
    void commandRegistrationIsIdempotentWhenHostAlreadyRegistered() {
        // 先手动注册（模拟迁移期宿主双写）
        HarnessEngine engine = engineBuilder().build();
        GoalEngine manual = new GoalEngine(engine, BudgetThresholds.defaults(), 3, 3, true);
        // 命令只持 GoalExtension（存量形态 of(...) 不装配命令与调度，正好用于手工注册）
        GoalExtension manualExt = GoalExtension.of(manual, engine);
        engine.getCommandRegistry().register(
                new GoalCommand(manualExt));

        GoalExtension ext = GoalExtension.builder("ws-dup")
                .executor((sid, p) -> GoalRoundResult.ofText("x"))
                .scheduler(null)
                .build();
        engine.addExtension(ext);

        // 幂等：扩展不得覆盖宿主已注册的命令
        assertSame(manual, commandEngineOf(engine), "已注册的同名命令应保持原实例");
    }

    /** 从已注册的 /goal 命令里取出其 GoalExtension 背后的 GoalEngine（字段名已由 goalEngine 改为 goal）。 */
    private Object commandEngineOf(HarnessEngine engine) {
        try {
            Object command = engine.getCommandRegistry().find("goal");
            java.lang.reflect.Field f = command.getClass().getDeclaredField("goal");
            f.setAccessible(true);
            GoalExtension holder =
                    (GoalExtension) f.get(command);
            return holder.getGoalEngine();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 登记一个有确定 id 的会话（同时写入 provider 缓存，sessionOf 才能解析到） */
    private AgentSession session(String id) {
        return sessions.computeIfAbsent(id, k -> InMemoryAgentSession.of(k));
    }

    // ==================== 停等 / 展示正文 / 执行器直通 ====================

    /** 无轮次在跑时停等应即时返回 true（不得白等到超时）。 */
    @Test
    void awaitRoundStoppedReturnsImmediatelyWhenIdle() {
        GoalExtension ext = GoalExtension.builder("ws-await").scheduler(null).build();
        engineBuilder().extensionAdd(ext).build();

        AgentSession session = InMemoryAgentSession.of("s-idle");
        long start = System.currentTimeMillis();
        assertTrue(ext.awaitRoundStopped(session, 5000L));
        assertTrue(System.currentTimeMillis() - start < 1000L, "空闲时应即时返回");
        assertTrue(ext.awaitRoundStopped("unknown-session", 5000L), "会话不存在视为已停稳");
    }

    /** 轮次运行中停等会阻塞；轮次结束后被唤醒并返回 true（靠 attrs 上的 notify，不是轮询）。 */
    @Test
    void awaitRoundStoppedIsWokenByRoundEnd() throws Exception {
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);

        GoalEngine engine = new GoalEngine((HarnessEngine) null, BudgetThresholds.defaults(), 3, 3, false);
        engine.addExecutor((sid, prompt) -> {
            entered.countDown();
            try {
                release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return GoalRoundResult.ofText("done");
        });

        AgentSession session = InMemoryAgentSession.of("s-running");
        engine.attach(session, "目标", 0, 0, 0);

        Thread worker = new Thread(() -> engine.runRound(session));
        worker.start();
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS), "轮次应已开始");
        assertTrue(engine.isRunning(session));

        // 超时短于轮次时长：应返回 false
        assertFalse(engine.awaitRoundStopped(session, 100L), "轮次未结束时应超时返回 false");

        release.countDown();
        assertTrue(engine.awaitRoundStopped(session, 5000L), "轮次结束后应被唤醒");
        worker.join(5000L);
        assertFalse(engine.isRunning(session), "轮次结束后标记应已清除");
    }

    /** pauseAndAwait：顺序为 pause → interrupter → 停等；中断回调必被调用。 */
    @Test
    void pauseAndAwaitInvokesInterrupterAfterPause() {
        GoalExtension ext = GoalExtension.builder("ws-pa").scheduler(null).build();
        engineBuilder().extensionAdd(ext).build();

        AgentSession session = InMemoryAgentSession.of("s-pa");
        GoalState gs = ext.attachAndSchedule(session, GoalSpec.of("目标"));

        java.util.List<String> order = new java.util.ArrayList<>();
        boolean ok = ext.pauseAndAwait(session, gs, () -> order.add("interrupt"), 1000L);

        assertTrue(ok, "无在跑轮次时应即时停稳");
        assertEquals(java.util.Collections.singletonList("interrupt"), order,
                "中断回调应被调用一次");
        assertEquals(GoalState.Status.PAUSED, gs.getStatus(), "pause 应先于停等生效");
    }

    /** 展示正文与执行正文分离：一次落盘、一次广播，列表不会看到附件全文的中间态。 */
    @Test
    void attachWithDisplayObjectiveBroadcastsOnceAndKeepsDisplayText() {
        AtomicInteger notified = new AtomicInteger();
        java.util.List<String> conditions = new java.util.ArrayList<>();
        GoalExtension ext = GoalExtension.builder("ws-display")
                .scheduler(null)
                .listener((sid, state, removed) -> {
                    notified.incrementAndGet();
                    conditions.add(state.getCondition());
                })
                .build();
        engineBuilder().extensionAdd(ext).build();

        AgentSession session = InMemoryAgentSession.of("s-display");
        GoalState gs = ext.attach(session, GoalSpec.of("执行正文（含附件全文）"), "展示正文");

        assertEquals("展示正文", gs.getCondition(), "状态里的正文应为展示正文");
        assertEquals(1, notified.get(), "只能广播一次（不得先广播执行正文再广播展示正文）");
        assertEquals(java.util.Collections.singletonList("展示正文"), conditions);
    }

    /** 展示正文与执行正文相同时不覆写、行为与无展示正文的旧路径一致。 */
    @Test
    void attachWithSameDisplayObjectiveKeepsObjective() {
        GoalExtension ext = GoalExtension.builder("ws-same").scheduler(null).build();
        engineBuilder().extensionAdd(ext).build();

        AgentSession session = InMemoryAgentSession.of("s-same");
        GoalState gs = ext.attach(session, GoalSpec.of("同一个目标"), "同一个目标");
        assertEquals("同一个目标", gs.getCondition());

        AgentSession session2 = InMemoryAgentSession.of("s-same2");
        GoalState gs2 = ext.attach(session2, GoalSpec.of("另一个目标"), null);
        assertEquals("另一个目标", gs2.getCondition(), "null 展示正文不得覆写");
    }

    /** addExecutor 门面直通：宿主无需再取 getGoalEngine()。 */
    @Test
    void addExecutorIsAvailableOnFacade() {
        GoalExtension ext = GoalExtension.builder("ws-exec").scheduler(null).build();
        engineBuilder().extensionAdd(ext).build();

        AtomicInteger calls = new AtomicInteger();
        GoalExtension returned = ext.addExecutor((sid, prompt) -> {
            calls.incrementAndGet();
            return GoalRoundResult.ofText("claimed");
        });

        assertSame(ext, returned, "应支持链式调用");
        assertTrue(ext.getGoalEngine().hasExecutor());

        AgentSession session = InMemoryAgentSession.of("s-exec");
        ext.attachAndSchedule(session, GoalSpec.of("目标"));
        ext.getGoalEngine().runRound(session);
        assertTrue(calls.get() > 0, "注册的执行器应被认领");
    }

    /** pause/resume/clear 的 String 重载：宿主可以完全不接触 AgentSession。 */
    @Test
    void stringOverloadsWorkWithoutSessionHandling() {
        GoalExtension ext = GoalExtension.builder("ws-str").scheduler(null).build();
        engineBuilder().extensionAdd(ext).build();

        AgentSession session = session("s-str");
        GoalState gs = ext.attachAndSchedule(session, GoalSpec.of("目标"));

        assertTrue(ext.pause(session.getSessionId()));
        assertEquals(GoalState.Status.PAUSED, ext.stateOf(session.getSessionId()).getStatus());
        assertTrue(ext.resume(session.getSessionId()));
        assertEquals(GoalState.Status.PURSUING, ext.stateOf(session.getSessionId()).getStatus());

        // taskId 不匹配时不得误伤（混合任务列表的安全版）
        assertFalse(ext.pause(session.getSessionId(), "not-a-goal-id"));
        assertTrue(ext.pause(session.getSessionId(), gs.getId()));

        ext.clear(session.getSessionId());
        assertNull(ext.stateOf(session.getSessionId()));

        // 未知会话一律返回 false / 静默
        assertFalse(ext.pause("unknown"));
        assertFalse(ext.resume("unknown"));
        assertFalse(ext.update("unknown", "x", null, null));
        ext.clear("unknown");
    }

    /** recoverActiveSchedules：无调度器时为 0（纯测试宿主），状态读取异常不得中断整个恢复。 */
    @Test
    void recoverActiveSchedulesIsNullSafeWithoutScheduler() {
        GoalExtension ext = GoalExtension.builder("ws-rec").scheduler(null).build();
        engineBuilder().extensionAdd(ext).build();

        assertEquals(0, ext.recoverActiveSchedules(
                java.util.Arrays.asList("a", "b"), id -> GoalState.Status.PURSUING));
        assertEquals(0, ext.recoverActiveSchedules(null, id -> GoalState.Status.PURSUING));
        assertEquals(0, ext.recoverActiveSchedules(java.util.Arrays.asList("a"), null));
    }

    // ==================== 新增门面（宿主 41 处调用点清理配套） ====================

    @Test
    void findResolvesGoalRowBySessionAndTaskId() {
        GoalExtension ext = GoalExtension.builder("ws-find").scheduler(null).build();
        engineBuilder().extensionAdd(ext).build();

        AgentSession session = session("s-find");
        GoalState gs = ext.attachAndSchedule(session, GoalSpec.of("目标"));

        assertNull(ext.find("session-x", gs.getId()), "未知会话返回 null");
        assertNull(ext.find(session.getSessionId(), "not-a-goal-id"), "taskId 不匹配返回 null");
        assertNull(ext.find(session.getSessionId(), null), "空 taskId 返回 null");
        assertSame(gs, ext.find(session.getSessionId(), gs.getId()), "匹配时应返回同一 GoalState");
        assertSame(gs, ext.stateOf(session.getSessionId()), "stateOf 应等价于 state(sessionOf(id))");
        assertNotNull(ext.sessionOf(session.getSessionId()), "sessionOf 应能解析会话");
    }

    @Test
    void updateAndRescheduleKeepsDurationWhenNull() {
        GoalExtension ext = GoalExtension.builder("ws-upd").scheduler(null).build();
        engineBuilder().extensionAdd(ext).build();

        AgentSession session = InMemoryAgentSession.of();
        GoalState gs = ext.attachAndSchedule(session, GoalSpec.of("旧目标").maxTokens(100L).maxDurationMs(5000L).maxIterations(7));

        // maxDurationMs 传 null：只能改正文与 token，时长与轮次不得被清空
        ext.updateAndReschedule(session, gs, "新目标", 200L, null, false);
        GoalState after = ext.state(session);
        assertEquals("新目标", after.getCondition());
        assertEquals(200L, after.getMaxTokens());
        assertEquals(5000L, after.getMaxDurationMs(), "null 时长不得清空原值");
        assertEquals(7, after.getMaxIterations(), "轮次应保持原值");

        // 显式传值时才覆盖
        ext.updateAndReschedule(session, gs, "新目标2", null, 9000L, false);
        assertEquals(9000L, ext.state(session).getMaxDurationMs());
        assertEquals(200L, ext.state(session).getMaxTokens(), "null token 应保持原值");
    }

    @Test
    void clearUnscheduleBeforeRemoveKeepsSlotEmpty() {
        GoalExtension ext = GoalExtension.builder("ws-clr").scheduler(null).build();
        engineBuilder().extensionAdd(ext).build();

        AgentSession session = InMemoryAgentSession.of();
        ext.attachAndSchedule(session, GoalSpec.of("目标"));
        assertNotNull(ext.state(session));

        ext.clear(session);
        assertNull(ext.state(session), "clear 后单槽应为空");
        assertNull(ext.stateOf(session.getSessionId()));
        assertFalse(ext.hasGoal(session));
    }

    @Test
    void schedulerFacadeMethodsAreNullSafeWithoutScheduler() {
        // 无调度（纯 JUnit）时调度门面必须全部 no-op，不能 NPE
        GoalExtension ext = GoalExtension.builder("ws-null").scheduler(null).build();
        engineBuilder().extensionAdd(ext).build();

        AgentSession session = InMemoryAgentSession.of();
        ext.attachAndSchedule(session, GoalSpec.of("目标"));
        ext.resumeScheduleOnly(session.getSessionId());
        ext.scheduleNow(session.getSessionId());
        ext.unschedule(session.getSessionId());
        ext.resumeScheduleOnly(null);
        ext.scheduleNow(null);
        ext.unschedule(null);

        assertFalse(ext.hasScheduledGoals(), "无调度器时恒为 false");
    }

    @Test
    void legacyConstructorStillAttachesTalentOnly() {
        // 存量形态：外部 GoalEngine 直接接管，不装配命令与调度
        HarnessEngine engine = engineBuilder().build();
        GoalEngine external = new GoalEngine(engine, BudgetThresholds.defaults(), 3, 3, true);
        GoalExtension ext = GoalExtension.of(external, engine);
        engine.addExtension(ext);

        assertSame(external, ext.getGoalEngine());
        assertNull(engine.getCommandRegistry().find("goal"), "存量构造不注册命令");
        assertNotNull(ext.getGoalTalent(), "Talent 仍应可用");
    }

    @Test
    void disableStopsSchedulerButKeepsCommandRegistered() {
        // 新语义：命令照常注册，禁用后对用户不可见（而不是不注册）
        HarnessEngine engine = engineBuilder().build();
        GoalExtension ext = GoalExtension.builder("ws-switch").scheduler(null).build();
        engine.addExtension(ext);

        assertNotNull(engine.getCommandRegistry().get("goal"), "命令应已注册");
        assertTrue(engine.getCommandRegistry().find("goal") != null, "默认启用时可见");

        ext.setEnabled(false);

        assertNull(engine.getCommandRegistry().find("goal"), "禁用后命令不可见");
        assertNotNull(engine.getCommandRegistry().get("goal"), "禁用后仍可区分“已停用”");
        assertFalse(ext.isEnabled());
    }

    // ==================== 状态贡献（onStatus）与键登记 ====================

    /** goal 状态经开放事实槽位进入 engine.status，宿主无需单独查门面；不判开关。 */
    @Test
    void goalStatusIsContributedToEngineStatus() {
        GoalExtension ext = GoalExtension.builder("ws-status").scheduler(null).build();
        HarnessEngine engine = engineBuilder().extensionAdd(ext).build();
        AgentSession session = InMemoryAgentSession.of("s-status");

        assertNull(engine.status(session).get(GoalExtension.STATUS_GOAL), "无 goal 时为 null");

        GoalState gs = ext.attach(session, GoalSpec.of("目标"));
        assertEquals(GoalState.Status.PURSUING, engine.status(session).get(GoalExtension.STATUS_GOAL));

        ext.pause(session, gs);
        ext.setEnabled(false);
        assertEquals(GoalState.Status.PAUSED, engine.status(session).get(GoalExtension.STATUS_GOAL),
                "事实口径不受开关影响");

        ext.clear(session);
        assertNull(engine.status(session).get(GoalExtension.STATUS_GOAL));
    }

    /** 插件键集中登记在 GoalAttrs，持久化键值不变（改值会丢存量 goal）。 */
    @Test
    void goalKeysAreRegisteredInGoalAttrs() {
        assertEquals("session.goal", GoalAttrs.GOAL);
        GoalExtension ext = GoalExtension.builder("ws-attrs").scheduler(null).build();
        engineBuilder().extensionAdd(ext).build();
        AgentSession session = InMemoryAgentSession.of("s-attrs");
        ext.attach(session, GoalSpec.of("目标"));

        assertTrue(session.getContext().data().get(GoalAttrs.GOAL) instanceof java.util.Map, "投影落在 GoalAttrs.GOAL");
        assertTrue(session.attrs().get(GoalAttrs.GOAL_STATE) instanceof GoalState, "运行态落在 GoalAttrs.GOAL_STATE");
    }
}
