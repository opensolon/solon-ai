package org.noear.solon.ai.harness.extension.goal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.scheduling.scheduled.JobHandler;
import org.noear.solon.scheduling.scheduled.manager.IJobManager;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 全局节拍调度：N 个活跃 goal 只占 1 个 job；轮次并发不超过 roundConcurrency；同会话不并发
 */
class GoalSchedulerTickTest {
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
                        case "hashCode":
                            return System.identityHashCode(p);
                        case "equals":
                            return p == args[0];
                        case "toString":
                            return "FakeJobManager";
                        default:
                            return method.getReturnType() == boolean.class ? false : null;
                    }
                });
    }

    @Test
    void manyGoalsShareOneTickAndRespectConcurrency() throws Exception {
        GoalExtension ext = GoalExtension.builder().jobManager(jobManager()).roundConcurrency(2).build();
        HarnessEngine.of(workspace.toString(), workspace.resolve("home").toString())
                .sessionProvider((id, c) -> c ? sessions.computeIfAbsent(id, InMemoryAgentSession::of) : sessions.get(id))
                .extensionAdd(ext)
                .build();

        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        AtomicInteger rounds = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        ext.addExecutor("test", null, (sid, prompt, locale) -> {
            int c = concurrent.incrementAndGet();
            peak.accumulateAndGet(c, Math::max);
            rounds.incrementAndGet();
            try {
                release.await(5, TimeUnit.SECONDS);
            } finally {
                concurrent.decrementAndGet();
            }
            return GoalOutcome.skip("test");
        });

        GoalScheduler scheduler = ext.getGoalScheduler();
        AtomicLong now = new AtomicLong(1_000_000);
        scheduler.setClock(now::get);

        for (int i = 0; i < 50; i++) {
            ext.create(sid("s" + i), GoalSpec.of("目标" + i).runNow(true));
        }
        assertEquals(1, jobs.size(), "50 个活跃 goal 只占 1 个节拍 job");

        scheduler.tick();
        scheduler.tick(); // 满额：不再派发，也不重复派发执行中的会话
        waitUntil(() -> concurrent.get() == 2);
        assertEquals(2, scheduler.inflightCount());

        release.countDown();
        waitUntil(() -> scheduler.inflightCount() == 0);
        assertTrue(peak.get() <= 2, "并发不超过 roundConcurrency: " + peak.get());
        assertEquals(2, rounds.get());

        // 执行过的会话按间隔排期：未到期不再执行；其余到期会话继续派发
        scheduler.tick();
        waitUntil(() -> scheduler.inflightCount() == 0 && rounds.get() == 4);

        // 全部注销后节拍 job 移除
        for (int i = 0; i < 50; i++) {
            ext.remove("s" + i);
        }
        assertFalse(ext.hasActiveGoals());
        assertTrue(jobs.isEmpty(), "无活跃 goal 时不占定时任务");
    }

    private static void waitUntil(java.util.function.BooleanSupplier cond) throws InterruptedException {
        long end = System.currentTimeMillis() + 5_000;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > end) {
                fail("condition not met in time");
            }
            Thread.sleep(10);
        }
    }

    @Test
    void activeIndexSurvivesRestartAndRecoverReschedules() {
        Path home = workspace.resolve("home");
        GoalExtension ext1 = GoalExtension.builder().jobManager(jobManager()).build();
        HarnessEngine e1 = HarnessEngine.of(workspace.toString(), home.toString())
                .sessionProvider((id, c) -> c ? sessions.computeIfAbsent(id, InMemoryAgentSession::of) : sessions.get(id))
                .extensionAdd(ext1)
                .build();

        GoalScheduler s1 = ext1.getGoalScheduler();
        s1.schedule("s-a", false);
        s1.schedule("s-b", false);
        s1.unschedule("s-b");
        e1.shutdown(); // 关闭不改索引

        jobs.clear();
        GoalExtension ext2 = GoalExtension.builder().jobManager(jobManager()).build();
        HarnessEngine.of(workspace.toString(), home.toString())
                .sessionProvider((id, c) -> c ? sessions.computeIfAbsent(id, InMemoryAgentSession::of) : sessions.get(id))
                .extensionAdd(ext2)
                .build();

        assertEquals(1, ext2.recover(), "只恢复索引里仍登记的会话");
        assertTrue(ext2.getGoalScheduler().isScheduled("s-a"));
        assertFalse(ext2.getGoalScheduler().isScheduled("s-b"));
        assertEquals(0, ext2.recover(), "幂等");

        // 到期后首拍：会话不存在 → 自动注销，并从索引移除
        long later = System.currentTimeMillis() + 60_000;
        ext2.getGoalScheduler().setClock(() -> later);
        ext2.getGoalScheduler().tick();
        assertFalse(ext2.getGoalScheduler().isScheduled("s-a"));
        assertTrue(ext2.getGoalScheduler().readIndex().isEmpty());
    }

    @Test
    void recoverWithFilterOnlyReschedulesClaimedSessions() {
        Path home = workspace.resolve("home");
        GoalExtension ext1 = GoalExtension.builder().jobManager(jobManager()).build();
        HarnessEngine e1 = HarnessEngine.of(workspace.toString(), home.toString())
                .sessionProvider((id, c) -> c ? sessions.computeIfAbsent(id, InMemoryAgentSession::of) : sessions.get(id))
                .extensionAdd(ext1)
                .build();
        ext1.getGoalScheduler().schedule("cli", false);
        ext1.getGoalScheduler().schedule("web-1", false);
        e1.shutdown();

        jobs.clear();
        GoalExtension ext2 = GoalExtension.builder().jobManager(jobManager()).build();
        HarnessEngine.of(workspace.toString(), home.toString())
                .sessionProvider((id, c) -> c ? sessions.computeIfAbsent(id, InMemoryAgentSession::of) : sessions.get(id))
                .extensionAdd(ext2)
                .build();

        // 同一引擎上只接管 cli 会话的入口：别的入口的 goal 不该被它恢复（否则会落到缺省执行器上跑）
        assertEquals(1, ext2.recover("cli"::equals));
        assertTrue(ext2.getGoalScheduler().isScheduled("cli"));
        assertFalse(ext2.getGoalScheduler().isScheduled("web-1"));
        assertEquals(1, ext2.recover(), "不带过滤时恢复其余全部");
    }

    @Test
    void declaresOwnToolNamesForScopeGuard() {
        GoalExtension ext = GoalExtension.builder().jobManager(jobManager()).build();
        assertTrue(ext.getToolNames().isEmpty(), "未挂载时不声明");
        HarnessEngine.of(workspace.toString(), workspace.resolve("home").toString())
                .sessionProvider((id, c) -> c ? sessions.computeIfAbsent(id, InMemoryAgentSession::of) : sessions.get(id))
                .extensionAdd(ext)
                .build();
        assertTrue(ext.getToolNames().containsAll(Arrays.asList("goal_get", "goal_update")));
    }

    @Test
    void indexFromTwoNodesSharingStateSourceDoesNotOverwrite() {
        Path home = workspace.resolve("home");
        GoalExtension n1 = GoalExtension.builder().jobManager(jobManager()).build();
        GoalExtension n2 = GoalExtension.builder().jobManager(jobManager()).build();
        for (GoalExtension ext : Arrays.asList(n1, n2)) {
            HarnessEngine.of(workspace.toString(), home.toString())
                    .sessionProvider((id, c) -> c ? sessions.computeIfAbsent(id, InMemoryAgentSession::of) : sessions.get(id))
                    .extensionAdd(ext)
                    .build();
        }

        n1.getGoalScheduler().schedule("web-1", false);
        n2.getGoalScheduler().schedule("web-2", false);
        n2.getGoalScheduler().schedule("../x*y", false);
        assertEquals(Arrays.asList("../x*y", "web-1", "web-2"), n1.getGoalScheduler().readIndex(),
                "两个节点各写各的标记文件，互不覆盖；会话 ID 编码后不会路径穿越");

        n2.getGoalScheduler().unschedule("web-2");
        assertEquals(Arrays.asList("../x*y", "web-1"), n1.getGoalScheduler().readIndex());
    }

    private String sid(String id) {
        sessions.computeIfAbsent(id, InMemoryAgentSession::of);
        return id;
    }
}
