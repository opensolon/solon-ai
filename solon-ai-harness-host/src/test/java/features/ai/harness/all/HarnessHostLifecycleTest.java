package features.ai.harness.all;

import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.RejectReason;

import features.ai.harness.all.HarnessHostContractTest.ManualExecutor;
import features.ai.harness.all.HarnessHostContractTest.RecordingSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentEvent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentSessionProvider;
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
import org.noear.solon.ai.harness.session.SessionOwnership;
import org.noear.solon.ai.mount.source.FileMountSource;
import org.noear.solon.ai.mount.source.FindOptions;
import org.noear.solon.ai.mount.source.MountCapabilities;
import org.noear.solon.ai.mount.source.MountEntry;
import org.noear.solon.ai.mount.source.MountSource;
import org.noear.solon.ai.mount.source.MoveOptions;
import org.noear.solon.ai.mount.source.WriteOptions;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    private AgentSession sessionOf(String id, boolean create) {
        return create ? sessions.computeIfAbsent(id, InMemoryAgentSession::of) : sessions.get(id);
    }

    private HarnessEngine engine() throws Exception {
        return engine(null, this::sessionOf);
    }

    /** @param state 状态来源（null = 引擎缺省） */
    private HarnessEngine engine(MountSource state, AgentSessionProvider provider) throws Exception {
        return engine(state, provider, null);
    }

    /** @param ownership 会话归属（null = 引擎缺省） */
    private HarnessEngine engine(MountSource state, AgentSessionProvider provider, SessionOwnership ownership)
            throws Exception {
        ChatConfig model = new ChatConfig();
        model.setName("fake");
        model.setModel("fake-model");
        model.setApiUrl("http://localhost:1/v1");
        model.setApiKey("test");
        HarnessEngine.Builder builder = HarnessEngine.of(Files.createDirectories(tmp.resolve("ws")).toString(), tmp.resolve("home").toString())
                .sessionProvider(provider)
                .modelAdd(model)
                .defaultModel("fake")
                .extensionAdd(QueueExtension.builder().build());
        if (state != null) {
            builder.stateSource(state);
        }
        if (ownership != null) {
            builder.sessionOwnership(ownership);
        }
        return builder.build();
    }

    /** 积压索引里的会话（直接读引擎状态来源里的索引目录；按 sessionId 排序） */
    private static List<String> indexed(HarnessEngine engine) {
        try {
            MountSource state = engine.getStateSource();
            List<String> ids = new ArrayList<>();
            if (state.isDirectory(QueueExtension.INDEX_PATH)) {
                for (MountEntry e : state.list(QueueExtension.INDEX_PATH)) {
                    if (e.isDirectory() == false && e.getName().startsWith(".") == false) {
                        ids.add(URLDecoder.decode(e.getName(), "UTF-8"));
                    }
                }
            }
            Collections.sort(ids);
            return ids;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
        assertEquals(Collections.singletonList("s1"), indexed(before), "入队即登记积压索引");
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
            await(() -> indexed(engine).isEmpty(), "队列空了：索引注销");
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

    /** 请求路径上首次取到引擎：积压恢复转后台，首个请求不等积压会话逐个加载 */
    @Test
    void firstRequestDoesNotWaitForBacklogRecovery() throws Exception {
        HarnessEngine before = engine();
        assertTrue(before.getExtension(QueueExtension.class).get().enqueue(before.getSession("s1"), "left over").isOk());
        before.shutdown();

        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HarnessEngine engine = engine(null, (id, create) -> {
            if ("s1".equals(id) && create == false && loading.getCount() > 0) {
                loading.countDown();   // 恢复正在加载积压会话：卡住它
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return sessionOf(id, create);
        });
        engine.getSession("s2");
        RecordingSink sink = new RecordingSink();
        HarnessHost host = HarnessHost.builder().engines(k -> engine).sink(sink).executor(new ManualExecutor()).build();
        try {
            CompletableFuture<SubmitResult> f = CompletableFuture.supplyAsync(
                    () -> host.submit("t", "s2", HarnessInput.of("hello")));
            assertTrue(loading.await(10, TimeUnit.SECONDS), "恢复已开始加载积压会话");
            assertEquals(Disposition.STARTED, f.get(5, TimeUnit.SECONDS).getDisposition(), "首个请求不等积压恢复");

            release.countDown();
            await(() -> sink.started.size() == 2, "恢复照常续发 s1 的积压");
            assertTrue(sink.started.stream().anyMatch(r -> r.getInput().isDispatched()
                    && "left over".equals(r.getInput().getText())));
        } finally {
            release.countDown();
            engine.shutdown();
        }
    }

    /** 索引一时读不了：后台退避重试，不会当成「没有积压」就此放弃 */
    @Test
    void backlogRecoveryRetriesAfterAnUnreadableIndex() throws Exception {
        MountSource state = FileMountSource.of(Files.createDirectories(tmp.resolve("state")));
        HarnessEngine before = engine(state, this::sessionOf);
        assertTrue(before.getExtension(QueueExtension.class).get().enqueue(before.getSession("s1"), "left over").isOk());
        before.shutdown();

        FlakyListSource flaky = new FlakyListSource(state, 1);   // 第一次列举失败
        HarnessEngine engine = engine(flaky, this::sessionOf);
        RecordingSink sink = new RecordingSink();
        HarnessHost host = HarnessHost.builder().engines(k -> engine).sink(sink).executor(new ManualExecutor()).build();
        try {
            assertEquals(1, host.warmup(Collections.singletonList("t")));
            assertTrue(sink.started.isEmpty(), "第一次读索引失败：此刻还没续发");

            await(() -> sink.started.size() == 1, "退避后重试成功，续发积压");
            assertEquals("left over", sink.started.get(0).getInput().getText());
            assertEquals(2, flaky.lists.get(), "重试一次即止");
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
            assertEquals(Collections.singletonList("s1"), indexed(engine), "积压原样保留给负责它的入口");
        } finally {
            engine.shutdown();
        }
    }

    /**
     * 续发领到一条之后引擎被关闭（池回收 / 强制移除）：这条回到队首，剩下的不被「拒绝即确认」吃掉；
     * 积压与索引都留着，新引擎的恢复照常续发。
     */
    @Test
    void engineClosedDuringDrainKeepsTheBacklogForTheNextEngine() throws Exception {
        MountSource state = FileMountSource.of(Files.createDirectories(tmp.resolve("state")));
        HarnessEngine before = engine(state, this::sessionOf);
        QueueExtension q1 = before.getExtension(QueueExtension.class).get();
        AgentSession session = before.getSession("s1");
        for (String text : Arrays.asList("one", "two", "three")) {
            assertTrue(q1.enqueue(session, text).isOk());
        }

        // 续发领到「one」、还没交给引擎受理时，引擎被关闭
        RecordingSink sink = new RecordingSink();
        HarnessHost host = HarnessHost.builder().engines(k -> before).sink(sink).executor(new ManualExecutor())
                .input((t, in) -> {
                    if ("one".equals(in.getText())) {
                        before.shutdown();
                    }
                })
                .build();
        try {
            host.warmup(Collections.singletonList("t"));
        } finally {
            before.shutdown();   // 幂等：正常情况下已在续发途中关闭
        }

        assertEquals(3, q1.pendingSize(session), "领到的那条回到队首，一条不丢");
        assertEquals("one", q1.list(session).get(0).getText());
        assertTrue(sink.started.isEmpty());
        assertFalse(sink.rejectedReasons.contains(RejectReason.ENGINE_CLOSED),
                "引擎关闭不是这条输入被拒：不通知宿主，也不确认消费");
        assertEquals(Collections.singletonList("s1"), indexed(before), "积压索引保留");

        // 新引擎（同一状态来源与会话存储）：恢复从队首续发
        HarnessEngine engine = engine(state, this::sessionOf);
        RecordingSink sink2 = new RecordingSink();
        HarnessHost host2 = HarnessHost.builder().engines(k -> engine).sink(sink2).executor(new ManualExecutor()).build();
        try {
            host2.warmup(Collections.singletonList("t"));
            assertEquals(1, sink2.started.size());
            assertEquals("one", sink2.started.get(0).getInput().getText());
            assertEquals(2, engine.getExtension(QueueExtension.class).get().pendingSize(engine.getSession("s1")));
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

    /**
     * 预热时的积压恢复也套 wrap：会话存储依赖租户上下文（wrap 里设置）时照常续发，
     * 不会因为查不到会话就把活会话的标记当成死标记删掉。
     */
    @Test
    void warmupRecoversTheBacklogInsideWrap() throws Exception {
        HarnessEngine before = engine();
        assertTrue(before.getExtension(QueueExtension.class).get().enqueue(before.getSession("s1"), "left over").isOk());
        before.shutdown();

        ThreadLocal<String> tenant = new ThreadLocal<>();
        // 会话存储按租户上下文取：上下文缺失时什么都查不到
        HarnessEngine engine = engine(null, (id, create) -> "acme".equals(tenant.get()) ? sessionOf(id, create) : null);
        RecordingSink sink = new RecordingSink();
        HarnessHost host = HarnessHost.builder().engines(k -> engine).sink(sink).executor(new ManualExecutor())
                .wrap((t, r) -> () -> {
                    tenant.set(t);
                    try {
                        r.run();
                    } finally {
                        tenant.remove();
                    }
                })
                .build();
        try {
            assertEquals(1, host.warmup(Collections.singletonList("acme")));
            assertEquals(1, sink.started.size(), "预热在租户上下文里续发积压");
            assertEquals("left over", sink.started.get(0).getInput().getText());
            assertEquals(Collections.singletonList("s1"), indexed(engine), "活会话的标记不被当成死标记删掉");
        } finally {
            engine.shutdown();
        }
    }

    /**
     * 续发领到一条之后会话不归本节点了（租约被别的节点拿走）：这条回到队首，不被「拒绝即确认」吃掉，
     * 剩下的也不会一条条被确认掉；由持有节点续发。
     */
    @Test
    void ownershipLostDuringDrainKeepsTheBacklog() throws Exception {
        AtomicBoolean lost = new AtomicBoolean();
        SessionOwnership ownership = new SessionOwnership() {
            @Override
            public boolean tryAcquire(String sessionId) {
                return lost.get() == false;
            }

            @Override
            public String ownerOf(String sessionId) {
                return lost.get() ? "node-2" : "node-1";
            }

            @Override
            public void release(String sessionId) {
            }
        };
        HarnessEngine engine = engine(null, this::sessionOf, ownership);
        QueueExtension queue = engine.getExtension(QueueExtension.class).get();
        AgentSession session = engine.getSession("s1");
        for (String text : Arrays.asList("one", "two")) {
            assertTrue(queue.enqueue(session, text).isOk());
        }

        RecordingSink sink = new RecordingSink();
        HarnessHost host = HarnessHost.builder().engines(k -> engine).sink(sink).executor(new ManualExecutor())
                .input((t, in) -> {
                    if ("one".equals(in.getText())) {
                        lost.set(true);   // 领到之后、受理之前，租约被别的节点拿走
                    }
                })
                .build();
        try {
            host.warmup(Collections.singletonList("t"));

            assertEquals(2, queue.pendingSize(session), "领到的那条回到队首，一条不丢");
            assertEquals("one", queue.list(session).get(0).getText());
            assertTrue(sink.started.isEmpty());
            assertTrue(sink.notOwners.isEmpty(), "不归本节点不是这条输入被拒：不通知宿主，也不确认消费");
        } finally {
            engine.shutdown();
        }
    }

    /**
     * README 7.4 限流示例的前提：一轮占满一个执行器任务——{@code task.run()} 返回时这一轮已经结束
     * （终态回调已执行），在它返回之后释放许可才能真正限住同时在跑的轮次。
     */
    @Test
    void executorTaskReturnsOnlyAfterTheRunFinished() throws Exception {
        HarnessEngine engine = engine();
        engine.getSession("s1");
        RecordingSink sink = new RecordingSink();
        List<Integer> finishedWhenReturned = new CopyOnWriteArrayList<>();
        HarnessHost host = HarnessHost.builder().engines(k -> engine).sink(sink)
                .executor(task -> CompletableFuture.runAsync(() -> {
                    task.run();
                    finishedWhenReturned.add(sink.finished.size());
                }))
                .build();
        try {
            assertEquals(Disposition.STARTED, host.submit("t", "s1", HarnessInput.of("hello")).getDisposition());
            await(() -> finishedWhenReturned.size() == 1, "执行器任务返回");
            assertEquals(Collections.singletonList(1), finishedWhenReturned, "task.run() 返回时本轮已结束");
        } finally {
            engine.shutdown();
        }
    }

    /** 委托式状态来源：前 n 次列举积压索引目录时失败 */
    private static final class FlakyListSource implements MountSource {
        final MountSource d;
        final int failures;
        final AtomicInteger lists = new AtomicInteger();

        FlakyListSource(MountSource d, int failures) {
            this.d = d;
            this.failures = failures;
        }

        @Override
        public List<MountEntry> list(String path) throws IOException {
            if (path != null && path.startsWith(QueueExtension.INDEX_PATH) && lists.incrementAndGet() <= failures) {
                throw new IOException("index unavailable");
            }
            return d.list(path);
        }

        @Override
        public String getLocation() {
            return d.getLocation();
        }

        @Override
        public String getScheme() {
            return d.getScheme();
        }

        @Override
        public String normalize(String path) {
            return d.normalize(path);
        }

        @Override
        public MountEntry stat(String path) throws IOException {
            return d.stat(path);
        }

        @Override
        public List<MountEntry> find(String path, FindOptions options) throws IOException {
            return d.find(path, options);
        }

        @Override
        public InputStream openRead(String path) throws IOException {
            return d.openRead(path);
        }

        @Override
        public OutputStream openWrite(String path, WriteOptions options) throws IOException {
            return d.openWrite(path, options);
        }

        @Override
        public void delete(String path) throws IOException {
            d.delete(path);
        }

        @Override
        public void move(String source, String target, MoveOptions options) throws IOException {
            d.move(source, target, options);
        }

        @Override
        public MountCapabilities capabilities() {
            return d.capabilities();
        }

        @Override
        public Optional<Path> getLocalRoot() {
            return d.getLocalRoot();
        }

        @Override
        public Optional<Path> getLocalPath(String path) {
            return d.getLocalPath(path);
        }
    }
}
