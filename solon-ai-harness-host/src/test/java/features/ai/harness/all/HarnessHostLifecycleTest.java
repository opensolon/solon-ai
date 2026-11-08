package features.ai.harness.all;

import org.noear.solon.ai.harness.input.HarnessInput;

import features.ai.harness.all.HarnessHostContractTest.ManualExecutor;
import features.ai.harness.all.HarnessHostContractTest.RecordingSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentEvent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessResult;
import org.noear.solon.ai.harness.extension.queue.QueueExtension;
import org.noear.solon.ai.harness.host.HarnessHost;
import org.noear.solon.ai.harness.host.HostRun;
import org.noear.solon.ai.harness.host.HostSink;
import org.noear.solon.ai.harness.host.RunOutcome;
import org.noear.solon.ai.harness.host.RunPrep;
import org.noear.solon.ai.harness.host.RunSink;
import org.noear.solon.ai.harness.host.Disposition;
import org.noear.solon.ai.harness.host.SubmitResult;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 每轮生命周期契约：回调串行、open 不输出、取消屏障、interrupt 返回值、取消原因、积压索引恢复。
 */
class HarnessHostLifecycleTest {

    @TempDir
    Path tmp;

    private final Map<String, AgentSession> sessions = new ConcurrentHashMap<>();

    private HarnessEngine engine() throws Exception {
        ChatConfig model = new ChatConfig();
        model.setName("fake");
        model.setModel("fake-model");
        model.setApiUrl("http://localhost:1/v1");
        model.setApiKey("test");
        return HarnessEngine.of(Files.createDirectories(tmp.resolve("ws")).toString(), tmp.resolve("home").toString())
                .sessionProvider((id, create) -> create ? sessions.computeIfAbsent(id, InMemoryAgentSession::of) : sessions.get(id))
                .modelAdd(model)
                .defaultModel("fake")
                .extensionAdd(QueueExtension.builder().build())
                .build();
    }

    private static void await(Supplier<Boolean> cond, String message) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (cond.get()) {
                return;
            }
            Thread.sleep(20);
        }
        fail(message);
    }

    @Test
    void interruptInTheWindowReturnsTrueAndFinishesAsCancelled() throws Exception {
        HarnessEngine engine = engine();
        engine.getSession("s1");
        RecordingSink sink = new RecordingSink();
        ManualExecutor executor = new ManualExecutor();
        HarnessHost host = HarnessHost.builder().engines(k -> engine).sink(sink).executor(executor).build();
        try {
            SubmitResult r = host.submit("t", "s1", HarnessInput.of("hello"));
            HarnessResult<Boolean> stopped = host.interrupt("t", "s1");
            assertTrue(stopped.isOk());
            assertEquals(Boolean.TRUE, stopped.getValue(), "调度窗口里的那一轮也算：它一定会以 finished(CANCELLED) 收尾");

            assertTrue(executor.runNext(5_000));
            RunOutcome o = r.getRun().await(Duration.ofSeconds(10));
            assertEquals(RunOutcome.End.CANCELLED, o.getEnd());
            assertEquals(RunOutcome.CancelCause.INTERRUPT_ALL, o.getCancelCause());
            assertFalse(o.isStreamOpened());
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void interruptWithoutRunReturnsFalse() throws Exception {
        HarnessEngine engine = engine();
        engine.getSession("s1");
        HarnessHost host = HarnessHost.builder().engines(k -> engine).build();
        try {
            HarnessResult<Boolean> r = host.interrupt("t", "s1");
            assertTrue(r.isOk());
            assertEquals(Boolean.FALSE, r.getValue(), "没有在跑的轮次：宿主自己补终态");
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void handleCancelIsDistinguishedFromUserStop() throws Exception {
        HarnessEngine engine = engine();
        engine.getSession("s1");
        ManualExecutor executor = new ManualExecutor();
        HarnessHost host = HarnessHost.builder().engines(k -> engine).executor(executor).build();
        try {
            SubmitResult r = host.submit("t", "s1", HarnessInput.of("hello"));
            r.getRun().cancel();
            assertTrue(executor.runNext(5_000));
            RunOutcome o = r.getRun().await(Duration.ofSeconds(10));
            assertEquals(RunOutcome.End.CANCELLED, o.getEnd());
            assertEquals(RunOutcome.CancelCause.HANDLE, o.getCancelCause(), "客户端断开 / 程序取消与用户停止可区分");
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void openDoesNotOutputAndCallbacksNeverOverlap() throws Exception {
        HarnessEngine engine = engine();
        engine.getSession("s1");
        List<String> calls = new CopyOnWriteArrayList<>();
        AtomicInteger inFlight = new AtomicInteger();
        AtomicBoolean overlapped = new AtomicBoolean();
        String caller = Thread.currentThread().getName();
        HarnessHost host = HarnessHost.builder().engines(k -> engine).sink(new HostSink() {
            @Override
            public RunSink open(HostRun run) {
                calls.add("open");
                return new RunSink() {
                    private void enter(String name) {
                        if (inFlight.incrementAndGet() > 1) {
                            overlapped.set(true);
                        }
                        calls.add(name + (Thread.currentThread().getName().equals(caller) ? "@caller" : ""));
                        inFlight.decrementAndGet();
                    }

                    @Override
                    public void prepare(RunPrep prep) {
                        enter("prepare");
                    }

                    @Override
                    public void event(AgentEvent event) {
                        enter("event");
                    }

                    @Override
                    public void finished(RunOutcome outcome) {
                        enter("finished");
                    }
                };
            }
        }).build();
        try {
            host.submit("t", "s1", HarnessInput.of("hello")).getRun().await(Duration.ofSeconds(30));
            assertEquals("open", calls.get(0));
            assertEquals("prepare", calls.get(1), "prepare 在执行线程上，不在调用线程上");
            assertEquals("finished", calls.get(calls.size() - 1));
            assertFalse(overlapped.get(), "同一轮的回调不并发");
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void eventsAfterInterruptAreNotDelivered() throws Exception {
        HarnessEngine engine = engine();
        engine.getSession("s1");
        CountDownLatch firstEvent = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicBoolean stopped = new AtomicBoolean();
        AtomicInteger afterStop = new AtomicInteger();
        HarnessHost host = HarnessHost.builder().engines(k -> engine).sink(new HostSink() {
            @Override
            public RunSink open(HostRun run) {
                return new RunSink() {
                    @Override
                    public void event(AgentEvent event) {
                        if (stopped.get()) {
                            afterStop.incrementAndGet();
                            return;
                        }
                        firstEvent.countDown();
                        try {
                            interrupted.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                };
            }
        }).build();
        try {
            SubmitResult r = host.submit("t", "s1", HarnessInput.of("hello"));
            if (firstEvent.await(10, TimeUnit.SECONDS)) {
                assertTrue(host.interrupt("t", "s1").isOk());
                stopped.set(true);
                interrupted.countDown();
                RunOutcome o = r.getRun().await(Duration.ofSeconds(30));
                assertEquals(RunOutcome.End.CANCELLED, o.getEnd(), "停止受理后终态一定是 CANCELLED");
                assertEquals(0, afterStop.get(), "停止之后不再投递事件");
            } else {
                r.getRun().await(Duration.ofSeconds(30)); // 假模型没有产出事件：本用例退化为不挂起
            }
        } finally {
            interrupted.countDown();
            engine.shutdown();
        }
    }

    @Test
    void backlogIsResumedFromIndexWhenTheEngineIsFirstBound() throws Exception {
        HarnessEngine before = engine();
        AgentSession session = before.getSession("s1");
        QueueExtension q1 = before.getExtension(QueueExtension.class).get();
        assertTrue(q1.enqueue(session, "left over").isOk());
        assertEquals(Collections.singletonList("s1"), q1.pendingSessions(), "入队即登记积压索引");
        before.shutdown();

        // 进程重启：新引擎（同一状态来源、同一会话存储），宿主首次绑定时按索引续发
        HarnessEngine engine = engine();
        RecordingSink sink = new RecordingSink();
        ManualExecutor executor = new ManualExecutor();
        HarnessHost host = HarnessHost.builder().engines(k -> engine).sink(sink).executor(executor).build();
        try {
            assertEquals(1, host.warmup(Collections.singletonList("t")));
            assertEquals(1, sink.started.size(), "首次绑定即续发积压，不需要宿主枚举会话");
            assertTrue(sink.started.get(0).getInput().isDispatched());
            assertEquals("left over", sink.started.get(0).getInput().getText());

            assertTrue(executor.runNext(5_000));
            await(() -> sink.finished.size() == 1, "续发的一轮结束");
            QueueExtension q2 = engine.getExtension(QueueExtension.class).get();
            await(() -> q2.pendingSessions().isEmpty(), "队列空了：索引注销");
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void backlogRecoveryHonorsTheSessionFilter() throws Exception {
        HarnessEngine before = engine();
        QueueExtension q1 = before.getExtension(QueueExtension.class).get();
        assertTrue(q1.enqueue(before.getSession("desk-1"), "not mine").isOk());
        before.shutdown();

        HarnessEngine engine = engine();
        RecordingSink sink = new RecordingSink();
        HarnessHost host = HarnessHost.builder().engines(k -> engine).sink(sink).executor(new ManualExecutor())
                .programRuns(sid -> sid.startsWith("web-"))
                .build();
        try {
            host.warmup(Collections.singletonList("t"));
            assertTrue(sink.started.isEmpty(), "同一引擎上的其它宿主的会话不归本骨架续发");
        } finally {
            engine.shutdown();
        }
    }

    /** 不接管后台工作（programRuns(false)）的骨架也不恢复：否则与别的入口共用引擎时会跑掉不属于它的积压 */
    @Test
    void hostWithoutProgramRunsDoesNotResumeBacklog() throws Exception {
        HarnessEngine before = engine();
        QueueExtension q1 = before.getExtension(QueueExtension.class).get();
        assertTrue(q1.enqueue(before.getSession("s1"), "belongs to another entry").isOk());
        before.shutdown();

        HarnessEngine engine = engine();
        RecordingSink sink = new RecordingSink();
        HarnessHost host = HarnessHost.builder().engines(k -> engine).sink(sink).executor(new ManualExecutor())
                .programRuns(false)
                .build();
        try {
            host.warmup(Collections.singletonList("t"));
            assertTrue(sink.started.isEmpty(), "programRuns(false)：首次绑定不续发积压");
            assertEquals(Collections.singletonList("s1"),
                    engine.getExtension(QueueExtension.class).get().pendingSessions(), "积压原样保留给负责它的入口");
        } finally {
            engine.shutdown();
        }
    }

    /** 受理中（submit 还没返回）的停止不会丢：开出的那一轮以 CANCELLED 收尾，宿主不必自己处理句柄发布前的竞态 */
    @Test
    void interruptDuringAdmissionCancelsTheRunItOpens() throws Exception {
        HarnessEngine engine = engine();
        engine.getSession("s1");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ManualExecutor executor = new ManualExecutor();
        HarnessHost host = HarnessHost.builder().engines(k -> engine).executor(executor)
                .input((t, in) -> {
                    entered.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                })
                .build();
        try {
            CompletableFuture<SubmitResult> f = CompletableFuture.supplyAsync(
                    () -> host.submit("t", "s1", HarnessInput.of("hello")));
            assertTrue(entered.await(10, TimeUnit.SECONDS));

            HarnessResult<Boolean> stopped = host.interrupt("t", "s1");
            assertEquals(Boolean.TRUE, stopped.getValue(), "受理中的输入也算：它开出的那一轮会以 CANCELLED 收尾");
            release.countDown();

            SubmitResult r = f.get(10, TimeUnit.SECONDS);
            assertEquals(Disposition.STARTED, r.getDisposition());
            assertTrue(executor.runNext(5_000));
            RunOutcome o = r.getRun().await(Duration.ofSeconds(10));
            assertEquals(RunOutcome.End.CANCELLED, o.getEnd());
            assertEquals(RunOutcome.CancelCause.INTERRUPT_ALL, o.getCancelCause());
            assertFalse(o.isStreamOpened());
        } finally {
            release.countDown();
            engine.shutdown();
        }
    }

    /** stream() 在受理期间就被下游取消（客户端断开）：拿到句柄后补做取消，本轮不开流 */
    @Test
    void streamCancelledDuringAdmissionStopsTheRun() throws Exception {
        HarnessEngine engine = engine();
        engine.getSession("s1");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RecordingSink sink = new RecordingSink();
        ManualExecutor executor = new ManualExecutor();
        HarnessHost host = HarnessHost.builder().engines(k -> engine).sink(sink).executor(executor)
                .input((t, in) -> {
                    entered.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                })
                .build();
        try {
            Disposable d = host.stream("t", "s1", HarnessInput.of("hello"))
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe();
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            d.dispose();
            release.countDown();

            await(() -> sink.started.size() == 1, "受理照常开出一轮");
            assertTrue(executor.runNext(5_000));
            await(() -> sink.outcomes.size() == 1, "本轮收尾");
            RunOutcome o = sink.outcomes.get(0);
            assertEquals(RunOutcome.End.CANCELLED, o.getEnd());
            assertEquals(RunOutcome.CancelCause.HANDLE, o.getCancelCause());
            assertFalse(o.isStreamOpened());
        } finally {
            release.countDown();
            engine.shutdown();
        }
    }
}
