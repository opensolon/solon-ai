package org.noear.solon.ai.harness.extension.loop;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 执行通道契约：三态结论（done/skip/pass + failed/抛异常）、谓词化认领与穿透、无认领绝不假装成功。
 */
class LoopExecutorTest {

    /** 无引擎、无调度、无缺省执行器：只覆盖「投递」语义。 */
    private static LoopExtension ext() {
        return LoopExtension.builder("ws").jobManager(null).defaultExecutor(false).build();
    }

    private static LoopScheduler scheduler() {
        return ext().scheduler();
    }

    private static LoopSpec every5(String prompt) {
        return LoopSpec.of(prompt).everyMinutes(5);
    }

    @Test
    void executeSkipsWhenNoExecutorRegistered() throws Exception {
        LoopOutcome outcome = scheduler().execute("cli", "prompt");
        assertTrue(outcome.isSkipped(), "无执行器必须是 skip（调用方据此不记成功、不计迭代），不得伪造成功");
    }

    @Test
    void firstClaimingExecutorWins() throws Exception {
        LoopScheduler scheduler = scheduler();
        scheduler.addExecutor(null, null, (sessionId, prompt) -> LoopOutcome.done("web:" + prompt));
        scheduler.addExecutor(null, null, (sessionId, prompt) -> LoopOutcome.done("cli:" + prompt));
        assertEquals("web:prompt", scheduler.execute("web-1", "prompt").getText(), "按注册顺序取首个认领者");
    }

    @Test
    void claimPredicateDecidesOwnership() throws Exception {
        LoopScheduler scheduler = scheduler();
        scheduler.addExecutor("web", id -> id.startsWith("web-"), (sessionId, prompt) -> LoopOutcome.done("web:" + prompt));
        scheduler.addExecutor("cli", null, (sessionId, prompt) -> LoopOutcome.done("cli:" + prompt));
        assertEquals("cli:prompt", scheduler.execute("cli", "prompt").getText(), "谓词不命中时由下一个认领者执行");
        assertEquals("web:prompt", scheduler.execute("web-1", "prompt").getText());
    }

    @Test
    void skipIsClaimedAndDoesNotFallThrough() throws Exception {
        LoopExtension ext = ext()
                .addExecutor("cli", null, (sid, p) -> LoopOutcome.skip("terminal busy"))
                .addExecutor("any", null, (sid, p) -> LoopOutcome.done("other"));
        LoopOutcome outcome = ext.scheduler().execute("cli", "x");
        assertTrue(outcome.isSkipped(), "skip = 本通道认领但此刻不宜执行，不得交给别的通道抢跑");
        assertEquals("terminal busy", outcome.getText());
    }

    @Test
    void channelPredicateFiltersBeforeHandler() throws Exception {
        LoopExtension ext = ext()
                .addExecutor("web", id -> id.startsWith("web-"), (sid, p) -> LoopOutcome.done("web:" + p));
        ext.addExecutor("cli", "cli"::equals, (sid, p) -> LoopOutcome.done("cli:" + p));

        assertEquals("web:x", ext.scheduler().execute("web-1", "x").getText());
        assertEquals("cli:x", ext.scheduler().execute("cli", "x").getText());
        assertTrue(ext.scheduler().execute("desktop-1", "x").isSkipped(), "谓词都不命中 = 无认领");
    }

    @Test
    void throwingPredicateCountsAsMiss() throws Exception {
        LoopExtension ext = ext()
                .addExecutor("bad", id -> { throw new IllegalStateException("boom"); }, (sid, p) -> LoopOutcome.done("bad"))
                .addExecutor("ok", id -> true, (sid, p) -> LoopOutcome.done("ok"));
        assertEquals("ok", ext.scheduler().execute("s", "x").getText());
    }

    /** 执行器签名不得再有 agentName 参数（子代理归属是会话属性），且返回三态结论。 */
    @Test
    void executorSignature() throws Exception {
        Method handle = LoopExecutor.class.getMethod("handle", String.class, String.class);
        Parameter[] params = handle.getParameters();
        assertEquals(2, params.length, "执行器只接收 sessionId 与 prompt");
        assertEquals(LoopOutcome.class, handle.getReturnType(), "执行器必须返回 LoopOutcome（单个 String 区分不了跳过与失败）");
    }

    /** 无执行器认领时，不得写 lastResult、也不得计迭代或错误。 */
    @Test
    void noExecutorMustNotRecordAnything() {
        LoopScheduler scheduler = scheduler();
        LoopTask task = scheduler.schedule("cli", every5("prompt"));

        scheduler.doTrigger("cli", task, false);

        assertNull(task.getLastResult(), "无执行器认领时不得伪造成功");
        assertEquals(0, task.getCurrentIteration(), "未真正执行的轮次不得计入迭代");
        assertEquals(0, task.getErrorStreak());
    }

    @Test
    void doneRecordsResultAndCountsIteration() {
        LoopScheduler scheduler = scheduler();
        scheduler.addExecutor(null, null, (sessionId, prompt) -> LoopOutcome.done("done:" + prompt));
        LoopTask task = scheduler.schedule("cli", every5("p"));

        scheduler.doTrigger("cli", task, false);

        assertEquals("done:p", task.getLastResult());
        assertEquals(1, task.getCurrentIteration());
    }

    @Test
    void skipDoesNotCountAsErrorOrIteration() {
        LoopScheduler scheduler = scheduler();
        scheduler.addExecutor("cli", null, (sid, p) -> LoopOutcome.skip("terminal busy"));
        LoopTask task = scheduler.schedule("cli", every5("p"));

        scheduler.doTrigger("cli", task, false);

        assertNull(task.getLastResult());
        assertEquals(0, task.getCurrentIteration());
        assertEquals(0, task.getErrorStreak(), "跳过不是失败：不得累计错误、不得触发熔断");
        assertTrue(task.isEnabled());
    }

    /** 回归：宿主执行器吞掉异常返回「不认领」会绕过熔断。抛出的异常必须计入任务级错误。 */
    @Test
    void thrownExceptionCountsAsFailure() {
        LoopScheduler scheduler = scheduler();
        scheduler.addExecutor("cli", null, (sid, p) -> { throw new java.io.IOException("model down"); });
        LoopTask task = scheduler.schedule("cli", every5("p"));

        scheduler.doTrigger("cli", task, false);

        assertEquals(1, task.getErrorStreak(), "执行器抛异常必须计错误（退避重试、达到阈值停用）");
        assertTrue(task.getLastResult().contains("model down"), task.getLastResult());
        assertEquals(0, task.getCurrentIteration());
    }

    @Test
    void failedOutcomeCountsAsFailure() {
        LoopScheduler scheduler = scheduler();
        scheduler.addExecutor("web", null, (sid, p) -> LoopOutcome.failed("stream error"));
        LoopTask task = scheduler.schedule("web-1", every5("p"));

        scheduler.doTrigger("web-1", task, false);

        assertEquals(1, task.getErrorStreak());
        assertTrue(task.getLastResult().contains("stream error"), task.getLastResult());
    }

    @Test
    void nullOutcomeIsTreatedAsFailureNotSilentSkip() {
        LoopScheduler scheduler = scheduler();
        scheduler.addExecutor("buggy", null, (sid, p) -> null);
        LoopTask task = scheduler.schedule("s", every5("p"));

        scheduler.doTrigger("s", task, false);

        assertEquals(1, task.getErrorStreak(), "返回 null 是编程错误，必须在错误计数与 lastResult 上可见");
        assertTrue(task.getLastResult().contains("buggy"), task.getLastResult());
    }

    @Test
    void failuresReachThresholdDisableTask() {
        LoopScheduler scheduler = LoopExtension.builder("ws").jobManager(null).defaultExecutor(false)
                .maxErrors(() -> 1).build().scheduler();
        scheduler.addExecutor("cli", null, (sid, p) -> { throw new IllegalStateException("boom"); });
        LoopTask task = scheduler.schedule("cli", every5("p"));

        scheduler.doTrigger("cli", task, false);

        assertFalse(task.isEnabled(), "连续失败达到阈值必须自动停用");
    }

    @Test
    void triggerWrapperWrapsEveryRound() {
        java.util.concurrent.atomic.AtomicInteger wrapped = new java.util.concurrent.atomic.AtomicInteger();
        LoopExtension ext = LoopExtension.builder("ws").jobManager(null).defaultExecutor(false)
                .triggerWrapper(r -> () -> { wrapped.incrementAndGet(); r.run(); })
                .build()
                .addExecutor("any", null, (sid, p) -> LoopOutcome.done("ok"));
        LoopTask task = ext.schedule("s1", every5("p"));

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
        LoopExtension ext = ext().addExecutor("any", null, (sid, p) -> LoopOutcome.done("ok"));
        LoopTask task = ext.schedule("s1", LoopSpec.of("p").cron("0 0 18 * * ?").oneShot(true));

        ext.scheduler().doTrigger("s1", task, false);
        assertNotNull(ext.get("s1", task.getId()), "手动触发不消耗一次性配额");

        ext.scheduler().doTrigger("s1", task, true);
        assertNull(ext.get("s1", task.getId()), "调度触发执行一轮后必须注销");
    }
}
