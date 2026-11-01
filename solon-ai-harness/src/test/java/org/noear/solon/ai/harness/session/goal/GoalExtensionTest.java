package org.noear.solon.ai.harness.session.goal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.runtime.ExecutionBridge;

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
 * 4. TaskHandler/BusyChecker 注册到位；listener 预注册生效；
 * 5. 门面操作中转正确（state/attach 走组装出的 GoalEngine）；
 * 6. 命令注册幂等（宿主已注册同名命令时不覆盖）。
 */
class GoalExtensionTest {
    @TempDir
    Path workspace;

    /** 会话实例共享（与 ExecutionBridgeTest 同口径）：::of 会忽略 id 每次新建，sessionOf(id) 将解析不到已 attach 的会话。 */
    private final java.util.Map<String, AgentSession> sessions = new java.util.concurrent.ConcurrentHashMap<>();

    private HarnessEngine.Builder engineBuilder() {
        return HarnessEngine.of(workspace.toString(), workspace.resolve("home").toString())
                .sessionProvider(id -> sessions.computeIfAbsent(id, k -> InMemoryAgentSession.of()));
    }

    @Test
    void builderAssemblesFullChainAndRegistersCommand() {
        AtomicInteger executed = new AtomicInteger();
        GoalExtension ext = GoalExtension.builder("ws-test")
                .taskExecutor((sid, prompt, agent) -> {
                    executed.incrementAndGet();
                    return "done";
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
    void sharedBridgeIsReusedNotRebuilt() {
        HarnessEngine engine = engineBuilder().build();
        ExecutionBridge shared = new ExecutionBridge(engine);
        AtomicInteger calls = new AtomicInteger();

        GoalExtension ext = GoalExtension.builder("ws-share")
                .bridge(shared)
                .taskExecutor((sid, prompt, agent) -> {
                    calls.incrementAndGet();
                    return "ok";
                })
                .scheduler(null)
                .build();
        engine.addExtension(ext);

        // 经共享桥执行一轮：TaskHandler 应已注册到注入的桥上
        shared.execute("any-session", "prompt", null);
        assertEquals(1, calls.get(), "taskExecutor 应注册到宿主注入的共享桥");
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
        GoalEngine manual = new GoalEngine(new ExecutionBridge(engine), BudgetThresholds.defaults(), 3, 3, true);
        // 命令只持 GoalExtension（存量形态 of(...) 不装配命令与调度，正好用于手工注册）
        GoalExtension manualExt = GoalExtension.of(manual, engine);
        engine.getCommandRegistry().register(
                new org.noear.solon.ai.harness.command.builtin.GoalCommand(manualExt));

        GoalExtension ext = GoalExtension.builder("ws-dup")
                .taskExecutor((sid, p, a) -> "x")
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
            org.noear.solon.ai.harness.session.goal.GoalExtension holder =
                    (org.noear.solon.ai.harness.session.goal.GoalExtension) f.get(command);
            return holder.getGoalEngine();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 登记一个有确定 id 的会话（同时写入 provider 缓存，sessionOf 才能解析到） */
    private AgentSession session(String id) {
        return sessions.computeIfAbsent(id, k -> InMemoryAgentSession.of(k));
    }

    // ==================== 新增门面（宿主 41 处调用点清理配套） ====================

    @Test
    void findResolvesGoalRowBySessionAndTaskId() {
        GoalExtension ext = GoalExtension.builder("ws-find").scheduler(null).build();
        engineBuilder().extensionAdd(ext).build();

        AgentSession session = session("s-find");
        GoalState gs = ext.attachAndSchedule(session, "目标", 0, 0, 0);

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
        GoalState gs = ext.attachAndSchedule(session, "旧目标", 100L, 5000L, 7);

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
        ext.attachAndSchedule(session, "目标", 0, 0, 0);
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
        ext.attachAndSchedule(session, "目标", 0, 0, 0);
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
        GoalEngine external = new GoalEngine(new ExecutionBridge(engine), BudgetThresholds.defaults(), 3, 3, true);
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
}
