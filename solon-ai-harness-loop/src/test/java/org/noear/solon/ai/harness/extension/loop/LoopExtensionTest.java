package org.noear.solon.ai.harness.extension.loop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessExtension;
import org.noear.solon.ai.harness.command.AgentTaskIntent;
import org.noear.solon.ai.harness.command.CommandContext;
import org.noear.solon.ai.harness.input.InputOrigin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LoopExtension 装配与钩子契约：
 * 命令注册、加载、onStatus 计数、onDetach 卸载 job 保留记录、job 名工作区前缀、
 * 开关只关工具、D2 任务级连续错误、D3 /loop pause|resume、/loop stop 仅在运行中才中断、
 * P1 摘除后在途一轮不得清空 tasks.json、P3 修改运行中的任务不并发也不丢新定义。
 */
class LoopExtensionTest {
    @TempDir
    Path workspace;

    private final Map<String, AgentSession> sessions = new ConcurrentHashMap<>();

    /** 记录用户级中断（/loop stop 是否打断会话）。 */
    static final class InterruptProbe implements HarnessExtension {
        final AtomicInteger count = new AtomicInteger();

        @Override
        public void onInterrupt(HarnessEngine engine, AgentSession session) {
            count.incrementAndGet();
        }
    }

    private HarnessEngine engine(HarnessExtension... extensions) {
        HarnessEngine.Builder b = HarnessEngine.of(workspace.toString(), workspace.resolve("home").toString())
                .sessionProvider(id -> sessions.computeIfAbsent(id, k -> InMemoryAgentSession.of(k)));
        for (HarnessExtension e : extensions) {
            b.extensionAdd(e);
        }
        return b.build();
    }

    private static LoopExtension.Builder builder(FakeJobManager jm) {
        return LoopExtension.builder("ws/1").jobManager(jm == null ? null : jm.proxy).defaultExecutor(false);
    }

    // ==================== 装配 ====================

    @Test
    void attachRegistersCommandAndLoadsStore() throws Exception {
        Path store = workspace.resolve("tasks.json");
        Files.write(store, ("{\"version\":2,\"tasks\":[{\"id\":\"aaaa0001\",\"prompt\":\"p\",\"intervalMinutes\":5,"
                + "\"createdAt\":\"2026-01-01T00:00:00Z\",\"autoInterval\":false,\"enabled\":true,"
                + "\"sessionId\":\"s1\"}]}").getBytes("UTF-8"));
        FakeJobManager jm = new FakeJobManager();
        LoopExtension loop = builder(jm).storeFile(store).build();

        HarnessEngine engine = engine(loop);

        assertNotNull(engine.getCommandRegistry().find("loop"), "/loop 命令应已注册");
        assertEquals(1, loop.list("s1").size(), "onAttach 必须加载 tasks.json");
        assertTrue(loop.scheduler().isJobRegistered(loop.get("s1", "aaaa0001")), "启用任务必须注册 job");
        assertSame(loop, engine.addExtensionIfAbsent(builder(jm).build()), "按类型幂等");
    }

    @Test
    void jobNameCarriesSanitizedWorkspacePrefix() {
        FakeJobManager jm = new FakeJobManager();
        LoopExtension loop = builder(jm).build();
        LoopTask t = loop.schedule("s1", LoopSpec.of("p").everyMinutes(5));

        String name = loop.scheduler().jobName(t);
        assertEquals("loop-ws_1:" + t.getId(), name, "JobManager 是 JVM 单例，job 名必须带工作区前缀");
        assertTrue(jm.jobs.contains(name));

        LoopExtension other = LoopExtension.builder("ws-2").jobManager(jm.proxy).build();
        assertNotEquals(name, other.scheduler().jobName(t), "不同工作区同 id 不撞名");
    }

    @Test
    void statusContributesTaskCount() {
        LoopExtension loop = builder(null).build();
        HarnessEngine engine = engine(loop);
        AgentSession s = engine.getSession("s1");

        assertEquals(0, engine.status(s).get(LoopExtension.STATUS_TASKS).intValue());
        loop.schedule("s1", LoopSpec.of("a").everyMinutes(5));
        LoopTask b = loop.schedule("s1", LoopSpec.of("b").everyMinutes(5));
        loop.toggle("s1", b.getId()); // 暂停的也计入
        assertEquals(2, engine.status(s).get(LoopExtension.STATUS_TASKS).intValue());
    }

    @Test
    void detachUnloadsJobsButKeepsRecords() throws Exception {
        Path store = workspace.resolve("tasks.json");
        FakeJobManager jm = new FakeJobManager();
        LoopExtension loop = builder(jm).storeFile(store).build();
        HarnessEngine engine = engine(loop);
        loop.schedule("s1", LoopSpec.of("p").everyMinutes(5));
        assertEquals(1, jm.jobs.size());

        engine.shutdown();

        assertTrue(jm.jobs.isEmpty(), "onDetach 必须卸载全部 job（否则带着已废引擎空转）");
        assertFalse(loop.hasActiveTasks());
        String json = new String(Files.readAllBytes(store), "UTF-8");
        assertTrue(json.contains("\"prompt\""), "tasks.json 记录必须保留（重启可恢复）");
    }

    @Test
    void disableOnlyTurnsOffTalent() {
        LoopExtension loop = builder(null).build();
        engine(loop);
        loop.setEnabled(false);

        // 停用扩展 = 引擎不再回调 onRequestAssemble（模型工具不挂），其余照常
        assertFalse(loop.isEnabled());
        assertTrue(loop.command().isEnabled(), "/loop 命令不受开关影响");
        assertNotNull(loop.schedule("s1", LoopSpec.of("p").everyMinutes(5)), "已有/新建定时任务照常");
    }

    // ==================== D2：任务级连续错误 ====================

    @Test
    void errorStreakIsPerTaskAndResetsOnSuccess() {
        FakeJobManager jm = new FakeJobManager();
        LoopExtension loop = builder(jm).maxErrors(() -> 2).build();
        LoopTask a = loop.schedule("s1", LoopSpec.of("a").everyMinutes(5));
        LoopTask b = loop.schedule("s1", LoopSpec.of("b").everyMinutes(5));

        loop.scheduler().handleErrorForTest("s1", a, new RuntimeException("x"));
        assertEquals(1, a.getErrorStreak());
        assertEquals(0, b.getErrorStreak(), "A 的失败不得累计到 B");

        loop.scheduler().handleErrorForTest("s1", b, new RuntimeException("y"));
        assertTrue(a.isEnabled() && b.isEnabled(), "两个任务各失败一次都不应被停用（旧实现共用计数会停掉 B）");

        // A 成功一轮 → 清零
        loop.addExecutor("any", null, (sid, p) -> LoopOutcome.done("ok"));
        loop.scheduler().doTrigger("s1", a, false);
        assertEquals(0, a.getErrorStreak(), "成功一轮必须清零");

        // B 再失败一次 → 达到阈值 2 → 停用并摘 job
        loop.scheduler().handleErrorForTest("s1", b, new RuntimeException("y"));
        assertFalse(b.isEnabled(), "连续失败达到阈值后停用");
        assertFalse(loop.scheduler().isJobRegistered(b));
        assertTrue(a.isEnabled());
    }

    // ==================== P1：摘除后不得清空 tasks.json ====================

    @Test
    void inFlightRoundAfterDetachDoesNotWipeStore() throws Exception {
        Path store = workspace.resolve("tasks.json");
        LoopExtension loop = builder(null).storeFile(store).build();
        HarnessEngine engine = engine(loop);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        loop.addExecutor("any", null, (sid, p) -> {
            entered.countDown();
            await(release);
            return LoopOutcome.done("done");
        });
        LoopTask t = loop.schedule("s1", LoopSpec.of("keep-me").everyMinutes(5));
        loop.schedule("s2", LoopSpec.of("keep-me-too").everyMinutes(5));

        Thread round = new Thread(() -> loop.scheduler().doTrigger("s1", t, false));
        round.start();
        assertTrue(entered.await(3, TimeUnit.SECONDS));

        engine.shutdown();           // onDetach：清空内存表
        release.countDown();         // 在途一轮此刻结束（成功路径会写盘）
        round.join(3000);
        loop.stopAll("s2");          // 宿主删会话等调用，同样不得写盘

        String json = new String(Files.readAllBytes(store), "UTF-8");
        assertTrue(json.contains("keep-me") && json.contains("keep-me-too"),
                "摘除后任何写盘都必须跳过，否则以空快照覆盖文件：" + json);
    }

    @Test
    void reattachLoadsAgainAndPersistsAgain() throws Exception {
        Path store = workspace.resolve("tasks.json");
        LoopExtension loop = builder(null).storeFile(store).build();
        HarnessEngine first = engine(loop);
        loop.schedule("s1", LoopSpec.of("a").everyMinutes(5));
        first.shutdown();

        engine(loop);               // 同一实例重新挂接
        assertEquals(1, loop.list("s1").size(), "重新挂接必须重新加载");
        loop.schedule("s1", LoopSpec.of("b").everyMinutes(5));
        String json = new String(Files.readAllBytes(store), "UTF-8");
        assertTrue(json.contains("\"b\""), "重新挂接后恢复写盘：" + json);
    }

    // ==================== P3：修改运行中的任务 ====================

    @Test
    void updatingRunningTaskNeitherRunsConcurrentlyNorLosesResult() throws Exception {
        LoopExtension loop = builder(new FakeJobManager()).build();
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        loop.addExecutor("any", null, (sid, p) -> {
            executions.incrementAndGet();
            entered.countDown();
            await(release);
            return LoopOutcome.done("result of " + p);
        });
        LoopTask old = loop.schedule("s1", LoopSpec.of("v1").everyMinutes(5));

        Thread round = new Thread(() -> loop.scheduler().doTrigger("s1", old, false));
        round.start();
        assertTrue(entered.await(3, TimeUnit.SECONDS));

        LoopTask updated = loop.update("s1", old.getId(), LoopPatch.create().prompt("v2").everyMinutes(10));
        assertSame(updated, loop.get("s1", old.getId()));
        assertTrue(updated.isRunning(), "新版本必须继承在途一轮的运行标记");

        loop.scheduler().doTrigger("s1", updated, false);
        assertEquals(1, executions.get(), "在途一轮未结束时，新版本不得并发执行");

        release.countDown();
        round.join(3000);
        assertFalse(updated.isRunning(), "在途一轮结束后释放的是同一把锁");
        assertEquals(1, updated.getCurrentIteration(), "本轮结果必须记到新版本上");
        assertEquals("result of v1", updated.getLastResult());
        assertEquals("v2", updated.getPrompt());
    }

    @Test
    void oneShotEditedMidRoundKeepsNewDefinition() throws Exception {
        LoopExtension loop = builder(new FakeJobManager()).build();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        loop.addExecutor("any", null, (sid, p) -> {
            entered.countDown();
            await(release);
            return LoopOutcome.done("ok");
        });
        LoopTask old = loop.schedule("s1", LoopSpec.of("once").cron("0 0 18 * * ?").oneShot(true));

        Thread round = new Thread(() -> loop.scheduler().doTrigger("s1", old, true));
        round.start();
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        loop.update("s1", old.getId(), LoopPatch.create().prompt("once, edited").cron("0 0 20 * * ?"));
        release.countDown();
        round.join(3000);

        LoopTask kept = loop.get("s1", old.getId());
        assertNotNull(kept, "执行期间被修改过的一次性任务，不得按 id 连带删掉新定义");
        assertEquals("0 0 20 * * ?", kept.getCron());
        assertTrue(loop.scheduler().isJobRegistered(kept), "新定义的 job 不得被旧版本摘掉");
    }

    @Test
    void supersededVersionNeverRunsAgain() {
        LoopExtension loop = builder(new FakeJobManager()).build();
        AtomicInteger executions = new AtomicInteger();
        loop.addExecutor("any", null, (sid, p) -> {
            executions.incrementAndGet();
            return LoopOutcome.done("ok");
        });
        LoopTask old = loop.schedule("s1", LoopSpec.of("v1").everyMinutes(5));
        loop.update("s1", old.getId(), LoopPatch.create().prompt("v2"));

        loop.scheduler().doTrigger("s1", old, false); // 旧版本的错误重试 / 迟到回调
        assertEquals(0, executions.get(), "已被替换的旧版本不得再执行旧提示词");
        assertTrue(loop.scheduler().isJobRegistered(loop.get("s1", old.getId())), "也不得摘掉新版本的 job");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ==================== D3 与 stop 中断语义（命令层） ====================

    @Test
    void pauseResumeCommandTogglesTask() throws Exception {
        LoopExtension loop = builder(null).build();
        HarnessEngine engine = engine(loop);
        AgentSession s = engine.getSession("s1");
        LoopTask t = loop.schedule("s1", LoopSpec.of("p").everyMinutes(5));

        StubContext ctx = new StubContext(s, "pause", t.getId());
        loop.command().execute(engine, ctx, InputOrigin.NONE);
        assertFalse(loop.get("s1", t.getId()).isEnabled(), "/loop pause 必须真的暂停");
        assertTrue(ctx.output().contains("paused"), ctx.output());

        loop.command().execute(engine, new StubContext(s, "resume", t.getId()), InputOrigin.NONE);
        assertTrue(loop.get("s1", t.getId()).isEnabled(), "/loop resume 必须真的恢复");

        StubContext missing = new StubContext(s, "pause", "nope");
        loop.command().execute(engine, missing, InputOrigin.NONE);
        assertTrue(missing.output().contains("not found"), missing.output());
    }

    @Test
    void stopInterruptsOnlyWhenTaskRunning() throws Exception {
        InterruptProbe probe = new InterruptProbe();
        LoopExtension loop = builder(null).build();
        HarnessEngine engine = engine(loop, probe);
        AgentSession s = engine.getSession("s1");

        LoopTask idle = loop.schedule("s1", LoopSpec.of("idle").everyMinutes(5));
        loop.command().execute(engine, new StubContext(s, "stop", idle.getId()), InputOrigin.NONE);
        assertNull(loop.get("s1", idle.getId()));
        assertEquals(0, probe.count.get(), "任务空闲时停止不得打断用户当前这一轮");

        LoopTask busy = loop.schedule("s1", LoopSpec.of("busy").everyMinutes(5));
        assertTrue(busy.tryStart());
        loop.command().execute(engine, new StubContext(s, "stop", busy.getId()), InputOrigin.NONE);
        assertEquals(1, probe.count.get(), "任务正在执行时停止必须中断那一轮");
    }

    @Test
    void stopAllInterruptsOnlyWhenAnyRunning() throws Exception {
        InterruptProbe probe = new InterruptProbe();
        LoopExtension loop = builder(null).build();
        HarnessEngine engine = engine(loop, probe);
        AgentSession s = engine.getSession("s1");

        loop.schedule("s1", LoopSpec.of("a").everyMinutes(5));
        loop.command().execute(engine, new StubContext(s, "stop-all"), InputOrigin.NONE);
        assertTrue(loop.list("s1").isEmpty());
        assertEquals(0, probe.count.get());

        LoopTask b = loop.schedule("s1", LoopSpec.of("b").everyMinutes(5));
        b.tryStart();
        loop.command().execute(engine, new StubContext(s, "stop-all"), InputOrigin.NONE);
        assertEquals(1, probe.count.get());
    }

    @Test
    void scheduleCommandParsesIntervalAndCron() throws Exception {
        LoopExtension loop = builder(null).build();
        HarnessEngine engine = engine(loop);
        AgentSession s = engine.getSession("s1");

        loop.command().execute(engine, new StubContext(s, "2h", "--now", "check", "ci"), InputOrigin.NONE);
        LoopTask t = loop.list("s1").get(0);
        assertEquals(120, t.getIntervalMinutes());
        assertTrue(t.isRunNow());
        assertEquals("check ci", t.getPrompt());

        StubContext bad = new StubContext(s, "cron:\"every day\"", "x");
        loop.command().execute(engine, bad, InputOrigin.NONE);
        assertTrue(bad.output().contains("Failed"), bad.output());
        assertEquals(1, loop.list("s1").size());

        assertEquals(Integer.valueOf(1), LoopCommand.parseInterval("30s"));
        assertNull(LoopCommand.parseInterval("abc"));
    }

    // ==================== 命令上下文桩 ====================

    static final class StubContext implements CommandContext {
        final AgentSession session;
        final List<String> args;
        final List<String> out = new ArrayList<>();

        StubContext(AgentSession session, String... args) {
            this.session = session;
            this.args = Arrays.asList(args);
        }

        String output() {
            return String.join("\n", out);
        }

        @Override
        public AgentSession getSession() {
            return session;
        }

        @Override
        public String getRawInput() {
            return null;
        }

        @Override
        public String getCommandName() {
            return "loop";
        }

        @Override
        public List<String> getArgs() {
            return args;
        }

        @Override
        public void println(String text) {
            out.add(text);
        }

        @Override
        public void requestAgentTask(String prompt, String model) {
        }

        @Override
        public AgentTaskIntent getAgentTaskIntent() {
            return null;
        }
    }
}
