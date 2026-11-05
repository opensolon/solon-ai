package org.noear.solon.ai.harness.session.loop;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 执行通道契约：无认领就是「无认领」，绝不假装成功；谓词化认领与穿透；注入屏障。
 */
class LoopExecutorTest {

    /** 无引擎、无调度、无缺省执行器：只覆盖「投递」语义。 */
    private static LoopExtension ext() {
        return LoopExtension.builder("ws").jobManager(null).defaultExecutor(false).build();
    }

    private static LoopScheduler scheduler() {
        return ext().scheduler();
    }

    @Test
    void executeReturnsNullWhenNoExecutorRegistered() {
        LoopScheduler scheduler = scheduler();
        assertNull(scheduler.execute("cli", "prompt"),
                "无执行器必须返回 null（调用方据此不记 ok、不计迭代），不得伪造成功");
    }

    @Test
    void firstClaimingExecutorWins() {
        LoopScheduler scheduler = scheduler();
        scheduler.addExecutor(null, null, (sessionId, prompt) -> "web:" + prompt);
        scheduler.addExecutor(null, null, (sessionId, prompt) -> "cli:" + prompt);
        assertEquals("web:prompt", scheduler.execute("web-1", "prompt"), "按注册顺序取首个认领者");
    }

    @Test
    void nonClaimingExecutorFallsThroughToNext() {
        LoopScheduler scheduler = scheduler();
        scheduler.addExecutor(null, null, (sessionId, prompt) ->
                sessionId.startsWith("web-") ? "web:" + prompt : null);
        scheduler.addExecutor(null, null, (sessionId, prompt) -> "cli:" + prompt);
        assertEquals("cli:prompt", scheduler.execute("cli", "prompt"), "不认领时须继续尝试下一个执行器");
    }

    @Test
    void channelPredicateFiltersBeforeHandler() {
        LoopExtension ext = ext()
                .addExecutor("web", id -> id.startsWith("web-"), (sid, p) -> "web:" + p);
        ext.addExecutor("cli", "cli"::equals, (sid, p) -> "cli:" + p);

        assertEquals("web:x", ext.scheduler().execute("web-1", "x"));
        assertEquals("cli:x", ext.scheduler().execute("cli", "x"));
        assertNull(ext.scheduler().execute("desktop-1", "x"), "谓词都不命中 = 无认领");
    }

    @Test
    void predicateHitButHandlerNullFallsThrough() {
        LoopExtension ext = ext()
                .addExecutor("web", id -> true, (sid, p) -> null) // 运行时端口暂不可用
                .addExecutor("any", id -> true, (sid, p) -> "fallback");
        assertEquals("fallback", ext.scheduler().execute("web-1", "x"));
    }

    @Test
    void throwingPredicateCountsAsMiss() {
        LoopExtension ext = ext()
                .addExecutor("bad", id -> { throw new IllegalStateException("boom"); }, (sid, p) -> "bad")
                .addExecutor("ok", id -> true, (sid, p) -> "ok");
        assertEquals("ok", ext.scheduler().execute("s", "x"));
    }

    /** 执行器签名不得再有 agentName 参数（子代理归属是会话属性）。 */
    @Test
    void executorSignatureHasNoAgentNameParameter() throws Exception {
        Method handle = LoopExecutor.class.getMethod("handle", String.class, String.class);
        Parameter[] params = handle.getParameters();
        assertEquals(2, params.length, "执行器只接收 sessionId 与 prompt");
    }

    /** 无执行器认领时，不得把 "ok" 写进 lastResult、也不得计迭代。 */
    @Test
    void noExecutorMustNotRecordOk() {
        LoopScheduler scheduler = scheduler();
        LoopTask task = scheduler.schedule("cli", new LoopTask("prompt", 5));

        scheduler.doTrigger("cli", task, false);

        assertNull(task.getLastResult(), "无执行器认领时不得伪造成功");
        assertEquals(0, task.getCurrentIteration(), "未真正执行的轮次不得计入迭代");
    }

    @Test
    void claimingExecutorRecordsResultAndCountsIteration() {
        LoopScheduler scheduler = scheduler();
        scheduler.addExecutor(null, null, (sessionId, prompt) -> "done:" + prompt);
        LoopTask task = scheduler.schedule("cli", new LoopTask("p", 5));

        scheduler.doTrigger("cli", task, false);

        assertEquals("done:p", task.getLastResult());
        assertEquals(1, task.getCurrentIteration());
    }

    @Test
    void injectionGateDefaultsOpenAndBlocksRoundWhenClosed() {
        LoopScheduler scheduler = scheduler();
        scheduler.addExecutor(null, null, (sessionId, prompt) -> "done:" + prompt);

        LoopTask openTask = scheduler.schedule("cli", new LoopTask("p", 5));
        scheduler.doTrigger("cli", openTask, false);
        assertEquals("done:p", openTask.getLastResult(), "缺省必须允许注入");

        scheduler.setInjectionGate(() -> false);
        LoopTask blockedTask = scheduler.schedule("cli", new LoopTask("p", 5));
        scheduler.doTrigger("cli", blockedTask, false);
        assertNull(blockedTask.getLastResult(), "终端被占用时不得注入");
        assertEquals(0, blockedTask.getCurrentIteration());
    }

    @Test
    void triggerWrapperWrapsEveryRound() {
        java.util.concurrent.atomic.AtomicInteger wrapped = new java.util.concurrent.atomic.AtomicInteger();
        LoopExtension ext = LoopExtension.builder("ws").jobManager(null).defaultExecutor(false)
                .triggerWrapper(r -> () -> { wrapped.incrementAndGet(); r.run(); })
                .build()
                .addExecutor("any", null, (sid, p) -> "ok");
        LoopTask task = ext.schedule("s1", new LoopTask("p", 5));

        assertTrue(ext.trigger("s1", task.getId()));
        long deadline = System.currentTimeMillis() + 3000;
        while (task.getCurrentIteration() == 0 && System.currentTimeMillis() < deadline) {
            Thread.yield();
        }
        assertEquals(1, task.getCurrentIteration());
        assertEquals(1, wrapped.get(), "手动触发也必须经过宿主包装器（日志归属等上下文）");
    }

    /** oneShot：调度触发执行一轮后自动注销；手动触发不消耗配额。 */
    @Test
    void oneShotUnregistersOnlyOnScheduledRound() {
        LoopExtension ext = ext().addExecutor("any", null, (sid, p) -> "ok");
        LoopTask task = new LoopTask("p", 0, "0 0 18 * * ?", false);
        task.setOneShot(true);
        ext.schedule("s1", task);

        ext.scheduler().doTrigger("s1", task, false);
        assertNotNull(ext.get("s1", task.getId()), "手动触发不消耗一次性配额");

        ext.scheduler().doTrigger("s1", task, true);
        assertNull(ext.get("s1", task.getId()), "调度触发执行一轮后必须注销");
    }
}
