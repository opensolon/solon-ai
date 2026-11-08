package features.ai.harness.all;

import org.noear.solon.ai.harness.input.BusyPolicy;

import org.noear.solon.ai.harness.input.HarnessInput;

import features.ai.harness.all.HarnessHostContractTest.ManualExecutor;
import features.ai.harness.all.HarnessHostContractTest.RecordingSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessExtension;
import org.noear.solon.ai.harness.HarnessResult;
import org.noear.solon.ai.harness.extension.goal.GoalOutcome;
import org.noear.solon.ai.harness.extension.loop.LoopExtension;
import org.noear.solon.ai.harness.extension.loop.LoopSpec;
import org.noear.solon.ai.harness.extension.loop.LoopTask;
import org.noear.solon.ai.harness.extension.queue.QueueExtension;
import org.noear.solon.ai.harness.extension.steer.SteerExtension;
import org.noear.solon.ai.harness.host.HarnessHost;
import org.noear.solon.ai.harness.host.HostRun;
import org.noear.solon.ai.harness.host.HostSignal;
import org.noear.solon.ai.harness.host.HostSink;
import org.noear.solon.ai.harness.host.HostInput;
import org.noear.solon.ai.harness.host.RunOutcome;
import org.noear.solon.ai.harness.host.RunPrep;
import org.noear.solon.ai.harness.host.RunSink;
import org.noear.solon.ai.harness.host.SessionAdmin;
import org.noear.solon.ai.harness.host.SubmitResult;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.input.InputOutcome.Kind;
import org.noear.solon.ai.harness.input.InputOutcome.RejectReason;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HarnessHost 扩展点契约（会话级回调上下文、主体校验、忙时策略、每轮 RunSink、两段式中断、按请求拿结果、后台轮次）。
 */
class HarnessHostExtensionPointsTest {

    @TempDir
    Path tmp;

    private final Map<String, AgentSession> sessions = new ConcurrentHashMap<>();

    private HarnessEngine engine(HarnessExtension... extras) throws Exception {
        ChatConfig model = new ChatConfig();
        model.setName("fake");
        model.setModel("fake-model");
        model.setApiUrl("http://localhost:1/v1");
        model.setApiKey("test");
        HarnessEngine.Builder b = HarnessEngine.of(Files.createDirectories(tmp.resolve("ws")).toString(), tmp.resolve("home").toString())
                .sessionProvider((id, create) -> create ? sessions.computeIfAbsent(id, InMemoryAgentSession::of) : sessions.get(id))
                .modelAdd(model)
                .defaultModel("fake")
                .extensionAdd(QueueExtension.builder().build())
                .extensionAdd(new SteerExtension());
        for (HarnessExtension e : extras) {
            b.extensionAdd(e);
        }
        return b.build();
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

    // ==================== M1 会话级上下文与安全 ====================

    @Test
    void sessionCallbacksCarryTenantSessionAndInput() throws Exception {
        HarnessEngine engine = engine();
        engine.getSession("s1");
        RecordingSink sink = new RecordingSink();
        ManualExecutor executor = new ManualExecutor();
        HarnessHost host = HarnessHost.builder().engines(k -> engine).sink(sink).executor(executor).build();
        try {
            host.submit("acme", "ghost", HarnessInput.of("x").origin(InputOrigin.channel("web")));
            HostInput rejected = sink.rejectedInputs.get(0);
            assertEquals("acme", rejected.getTenant());
            assertEquals("ghost", rejected.getSessionId(), "拒绝也知道是哪个会话（按会话推送的宿主才能投递）");
            assertNull(rejected.getKind(), "受理之前就结束的回合没有引擎结局");

            host.submit("acme", "s1", HarnessInput.of("first").origin(InputOrigin.channel("a")));
            host.submit("acme", "s1", HarnessInput.of("second").origin(InputOrigin.channel("b")));
            HostInput deferred = sink.deferredInputs.get(0);
            assertEquals("acme", deferred.getTenant());
            assertEquals("s1", deferred.getSessionId());
            assertEquals("second", deferred.getText(), "排队时可回放用户消息");

            assertEquals(1, sink.admittedInputs.size(), "admitted 只在 TASK / REPLY / HITL / IGNORED 时调用");
            assertEquals(Kind.TASK, sink.admittedInputs.get(0).getKind());

            // 续发的一轮：回合带上队列项（文本、id、来源）
            assertTrue(executor.runNext(5_000));
            await(() -> sink.finished.size() == 1, "第一条结束");
            assertTrue(executor.runNext(30_000));
            await(() -> sink.finished.size() == 2, "第二条结束");
            HostRun dispatched = sink.started.get(1);
            assertNotNull(dispatched.getInput().getQueueItem(), "续发轮次可取回队列项");
            assertEquals("second", dispatched.getInput().getText());
            assertTrue(dispatched.getInput().isDispatched());
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void emptyInputIsAdmittedAndIgnored() throws Exception {
        HarnessEngine engine = engine();
        engine.getSession("s1");
        RecordingSink sink = new RecordingSink();
        HarnessHost host = HarnessHost.builder().engines(k -> engine).sink(sink).build();
        try {
            assertEquals(SubmitResult.Kind.IGNORED, host.submit("t", "s1", HarnessInput.of("")).getKind());
            assertEquals(1, sink.admittedInputs.size());
            assertEquals(Kind.IGNORED, sink.admittedInputs.get(0).getKind());
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void hostInputCarriesSelectionAndAttrsIncludingDispatched() throws Exception {
        HarnessEngine engine = engine();
        engine.getSession("s1");
        RecordingSink sink = new RecordingSink();
        ManualExecutor executor = new ManualExecutor();
        HarnessHost host = HarnessHost.builder().engines(k -> engine).sink(sink).executor(executor).build();
        try {
            host.submit("t", "s1", HarnessInput.of("first").selectedModel("fake").attr("effort", "high"));
            HostInput first = sink.admittedInputs.get(0);
            assertEquals("fake", first.getSelectedModel(), "admitted 里能看到「用户这次选了什么」");
            assertEquals("high", first.getAttr("effort"));
            assertThrows(UnsupportedOperationException.class, () -> first.getAttrs().put("x", "y"), "只读");

            host.submit("t", "s1", HarnessInput.of("second").attr("effort", "low"));
            assertEquals("low", sink.deferredInputs.get(0).getAttr("effort"), "排队时也能看到");

            assertTrue(executor.runNext(5_000));
            await(() -> sink.finished.size() == 1, "第一条结束");
            assertTrue(executor.runNext(30_000));
            await(() -> sink.finished.size() == 2, "第二条结束");
            HostInput dispatched = sink.started.get(1).getInput();
            assertTrue(dispatched.isDispatched());
            assertEquals("low", dispatched.getAttr("effort"), "续发轮次带回排队时保存的属性");
            assertTrue(sink.admittedInputs.stream().anyMatch(t -> t.isDispatched() && "low".equals(t.getAttr("effort"))));
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void submittedInputIsCopiedAndHasOneOrigin() throws Exception {
        HarnessEngine engine = engine();
        engine.getSession("s1");
        RecordingSink sink = new RecordingSink();
        HarnessHost host = HarnessHost.builder().engines(k -> engine).sink(sink).executor(new ManualExecutor())
                .input((tenant, in) -> in.origin(InputOrigin.channel("decorated")))
                .build();
        try {
            HarnessInput in = HarnessInput.of("hello").origin(InputOrigin.channel("web"));
            host.submit("t", "s1", in);
            assertNull(in.getSession(), "调用方的对象不被绑定（交给引擎的是副本）");
            assertEquals("web", in.getOrigin().getChannel(), "调用方的对象不被装饰器改动");
            assertEquals("decorated", sink.admittedInputs.get(0).getOrigin().getChannel(),
                    "回调看到的来源与交给引擎的是同一份");

            AgentSession other = engine.getSession("s2");
            assertThrows(IllegalArgumentException.class, () -> host.submit("t", "s1", HarnessInput.of(other, "x")),
                    "输入带的会话与 sessionId 不一致");
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void principalMismatchIsRejectedBeforeHandling() throws Exception {
        HarnessEngine engine = engine();
        RecordingSink sink = new RecordingSink();
        HarnessHost host = HarnessHost.builder().engines(k -> engine).sink(sink).executor(new ManualExecutor()).build();
        try {
            host.openSession("t", "s1", "u1");

            SubmitResult idor = host.submit("t", "s1", HarnessInput.of("hello").principal("u2"));
            assertEquals(SubmitResult.Kind.REJECTED, idor.getKind());
            assertEquals(RejectReason.SCOPE_UNAVAILABLE, idor.getRejectReason());
            assertEquals(403, idor.httpStatus());
            assertFalse(engine.status(engine.getSessionOrNull("s1")).isOccupied(), "被拒的输入不占位");

            assertEquals(SubmitResult.Kind.STARTED, host.submit("t", "s1", HarnessInput.of("hello").principal("u1")).getKind());
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void inputDecoratorCoversSubmitAndDrain() throws Exception {
        HarnessEngine engine = engine();
        engine.getSession("s1");
        List<String> decorated = new CopyOnWriteArrayList<>();
        ManualExecutor executor = new ManualExecutor();
        RecordingSink sink = new RecordingSink();
        HarnessHost host = HarnessHost.builder().engines(k -> engine).sink(sink).executor(executor)
                .input((tenant, in) -> decorated.add(tenant + ":" + in.getText()))
                .build();
        try {
            host.submit("t", "s1", HarnessInput.of("first"));
            host.submit("t", "s1", HarnessInput.of("second"));
            assertTrue(executor.runNext(5_000));
            await(() -> sink.finished.size() == 1, "第一条结束");
            assertTrue(executor.runNext(30_000));
            await(() -> sink.finished.size() == 2, "续发的一轮结束");

            assertTrue(decorated.contains("t:first"));
            assertEquals(2, decorated.stream().filter("t:second"::equals).count(), "提交时一次、续发时一次");
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void busyPolicyRejectDoesNotQueue() throws Exception {
        HarnessEngine engine = engine();
        AgentSession session = engine.getSession("s1");
        RecordingSink sink = new RecordingSink();
        HarnessHost host = HarnessHost.builder().engines(k -> engine).sink(sink).executor(new ManualExecutor()).build();
        try {
            host.submit("t", "s1", HarnessInput.of("first"));
            SubmitResult r = host.submit("t", "s1", HarnessInput.of("second").whenBusy(BusyPolicy.REJECT));
            assertEquals(RejectReason.BUSY, r.getRejectReason());
            assertEquals(0, engine.getExtension(QueueExtension.class).get().pendingSize(session), "一问一答：不排队");
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void busyPolicySteerFallsBackToQueueWhenNoStreamIsRunning() throws Exception {
        HarnessEngine engine = engine();
        engine.getSession("s1");
        RecordingSink sink = new RecordingSink();
        HarnessHost host = HarnessHost.builder().engines(k -> engine).sink(sink).executor(new ManualExecutor()).build();
        try {
            host.submit("t", "s1", HarnessInput.of("first"));
            // 受理窗口里还没开流：插话无处可去，回落到排队
            SubmitResult r = host.submit("t", "s1", HarnessInput.of("more detail").whenBusy(BusyPolicy.STEER));
            assertEquals(SubmitResult.Kind.DEFERRED, r.getKind());
            assertTrue(sink.steeredInputs.isEmpty());
        } finally {
            engine.shutdown();
        }
    }

    // ==================== M2 每轮链路 ====================

    @Test
    void runSinkOrderAndWrapContext() throws Exception {
        HarnessEngine engine = engine();
        engine.getSession("s1");
        ThreadLocal<String> tenantCtx = new ThreadLocal<>();
        List<String> calls = new CopyOnWriteArrayList<>();
        HarnessHost host = HarnessHost.builder().engines(k -> engine)
                .wrap((tenant, r) -> () -> {
                    tenantCtx.set(tenant);
                    try {
                        r.run();
                    } finally {
                        tenantCtx.remove();
                    }
                })
                .sink(new HostSink() {
                    @Override
                    public RunSink open(HostRun run) {
                        calls.add("open");
                        return new RunSink() {
                            @Override
                            public void prepare(RunPrep prep) {
                                calls.add("prepare:" + tenantCtx.get());
                                assertNotNull(prep.request());
                                assertSame(run, prep.run());
                            }

                            @Override
                            public void finished(RunOutcome outcome) {
                                calls.add("finished:" + outcome.getEnd());
                            }
                        };
                    }
                }).build();
        try {
            RunOutcome o = host.submit("acme", "s1", HarnessInput.of("hello")).getRun().await(Duration.ofSeconds(30));
            assertTrue(o.isStreamOpened());
            assertEquals("open", calls.get(0));
            assertEquals("prepare:acme", calls.get(1), "执行线程上能读到 wrap 注入的租户上下文");
            assertTrue(calls.get(calls.size() - 1).startsWith("finished:"));
            assertEquals(1, calls.stream().filter(c -> c.startsWith("finished:")).count(), "finished 恰好一次");
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void prepareFailureFinishesAsFailedAndReleasesSession() throws Exception {
        HarnessEngine engine = engine();
        AgentSession session = engine.getSession("s1");
        HarnessHost host = HarnessHost.builder().engines(k -> engine)
                .sink(new HostSink() {
                    @Override
                    public RunSink open(HostRun run) {
                        return new RunSink() {
                            @Override
                            public void prepare(RunPrep prep) {
                                throw new IllegalStateException("bad model config");
                            }
                        };
                    }
                }).build();
        try {
            RunOutcome o = host.submit("t", "s1", HarnessInput.of("hello")).getRun().await(Duration.ofSeconds(30));
            assertEquals(RunOutcome.End.FAILED, o.getEnd());
            assertEquals("bad model config", o.getError().getMessage());
            assertFalse(o.isStreamOpened());
            await(() -> engine.status(session).isOccupied() == false, "开流前失败必须释放受理占位");
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void interruptDuringPrepareFinishesAsCancelled() throws Exception {
        HarnessEngine engine = engine();
        AgentSession session = engine.getSession("s1");
        CountDownLatch inPrepare = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HarnessHost host = HarnessHost.builder().engines(k -> engine)
                .sink(new HostSink() {
                    @Override
                    public RunSink open(HostRun run) {
                        return new RunSink() {
                            @Override
                            public void prepare(RunPrep prep) {
                                inPrepare.countDown();
                                try {
                                    release.await(10, TimeUnit.SECONDS);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                        };
                    }
                }).build();
        try {
            SubmitResult r = host.submit("t", "s1", HarnessInput.of("hello"));
            assertTrue(inPrepare.await(10, TimeUnit.SECONDS));
            HarnessResult<Boolean> stopped = host.interrupt("t", "s1");
            assertTrue(stopped.isOk());
            release.countDown();

            RunOutcome o = r.getRun().await(Duration.ofSeconds(30));
            assertEquals(RunOutcome.End.CANCELLED, o.getEnd(), "停止不是错误");
            assertNull(o.getError());
            await(() -> engine.status(session).isOccupied() == false, "被取消的一轮释放占位");
        } finally {
            engine.shutdown();
        }
    }

    // ==================== M3 按请求拿结果 ====================

    @Test
    void observeAndRunHandleReceiveTheSameOutcome() throws Exception {
        HarnessEngine engine = engine();
        engine.getSession("s1");
        RecordingSink sink = new RecordingSink();
        List<RunOutcome> observed = new CopyOnWriteArrayList<>();
        HarnessHost host = HarnessHost.builder().engines(k -> engine).sink(sink).build();
        try {
            SubmitResult r = host.submit("t", "s1", HarnessInput.of("hello"), new RunSink() {
                @Override
                public void finished(RunOutcome outcome) {
                    observed.add(outcome);
                }
            });
            RunOutcome o = r.getRun().await(Duration.ofSeconds(30));
            assertSame(o, observed.get(0), "本次监听与 RunHandle 拿到同一个终态");
            await(() -> sink.outcomes.size() == 1, "全局 sink 同样收到终态");
            assertSame(o, sink.outcomes.get(0));
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void streamEndsWithFinishedSignal() throws Exception {
        HarnessEngine engine = engine();
        engine.getSession("s1");
        HarnessHost host = HarnessHost.builder().engines(k -> engine).build();
        try {
            List<HostSignal> signals = host.stream("t", "s1", HarnessInput.of("hello"))
                    .collectList().block(Duration.ofSeconds(30));
            assertNotNull(signals);
            assertEquals(HostSignal.Type.FINISHED, signals.get(signals.size() - 1).getType());
            assertEquals(1, signals.stream().filter(s -> s.getType() == HostSignal.Type.FINISHED).count());
            assertTrue(signals.stream().limit(signals.size() - 1)
                    .allMatch(s -> s.getType() == HostSignal.Type.EVENT || s.getType() == HostSignal.Type.REPLY));
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void streamOfDeferredOrRejectedInputIsASingleSignal() throws Exception {
        HarnessEngine engine = engine();
        engine.getSession("s1");
        HarnessHost host = HarnessHost.builder().engines(k -> engine).executor(new ManualExecutor()).build();
        try {
            host.submit("t", "s1", HarnessInput.of("first"));
            List<HostSignal.Type> deferred = host.stream("t", "s1", HarnessInput.of("second"))
                    .map(HostSignal::getType).collectList().block(Duration.ofSeconds(5));
            assertEquals(1, deferred.size());
            assertEquals(HostSignal.Type.DEFERRED, deferred.get(0));

            List<HostSignal> rejected = host.stream("t", "ghost", HarnessInput.of("x"))
                    .collectList().block(Duration.ofSeconds(5));
            assertEquals(1, rejected.size());
            assertEquals(RejectReason.SESSION_NOT_FOUND, rejected.get(0).getRejectReason());
        } finally {
            engine.shutdown();
        }
    }

    // ==================== M4 后台轮次 ====================

    @Test
    void programRunsGoThroughTheSameRunChain() throws Exception {
        HarnessEngine engine = engine(LoopExtension.builder().jobManager(null).build());
        engine.getSession("s1");
        List<String> chain = new CopyOnWriteArrayList<>();
        HarnessHost host = HarnessHost.builder().engines(k -> engine)
                .sink(new HostSink() {
                    @Override
                    public RunSink open(HostRun run) {
                        if (run.isProgram() == false) {
                            return RunSink.NONE;
                        }
                        chain.add("open:" + run.getProgramChannel());
                        return new RunSink() {
                            @Override
                            public void prepare(RunPrep prep) {
                                chain.add("prepare");
                            }

                            @Override
                            public void finished(RunOutcome outcome) {
                                chain.add("finished");
                            }
                        };
                    }
                }).build();
        try {
            SessionAdmin admin = host.admin("t");
            HarnessResult<LoopTask> task = admin.call("s1", LoopExtension.class,
                    (l, s) -> l.schedule("s1", LoopSpec.of("ping").everyMinutes(5)));
            assertTrue(task.isOk(), task.toString());
            assertTrue(admin.call("s1", LoopExtension.class, (l, s) -> l.trigger("s1", task.getValue().getId())).isOk());

            await(() -> chain.contains("finished"), "后台轮次走同一条链");
            assertEquals("open:loop", chain.get(0));
            assertEquals("prepare", chain.get(1), "后台轮次同样可以在开流前补充请求");
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void programRunsFilterLeavesOtherSessionsToPluginExecutor() throws Exception {
        HarnessEngine engine = engine(LoopExtension.builder().jobManager(null).build());
        engine.getSession("mine");
        engine.getSession("other");
        RecordingSink sink = new RecordingSink();
        HarnessHost host = HarnessHost.builder().engines(k -> engine).sink(sink)
                .programRuns("mine"::equals)
                .build();
        try {
            SessionAdmin admin = host.admin("t");
            for (String sid : new String[]{"other", "mine"}) {
                HarnessResult<LoopTask> task = admin.call(sid, LoopExtension.class,
                        (l, s) -> l.schedule(sid, LoopSpec.of("ping").everyMinutes(5)));
                assertTrue(task.isOk(), task.toString());
                assertTrue(admin.call(sid, LoopExtension.class, (l, s) -> l.trigger(sid, task.getValue().getId())).isOk());
            }
            await(() -> sink.finished.stream().anyMatch(HostRun::isProgram), "mine 的后台轮次经骨架");
            Thread.sleep(500);
            List<String> viaHost = sink.finished.stream().filter(HostRun::isProgram)
                    .map(HostRun::getSessionId).distinct().collect(Collectors.toList());
            assertEquals(1, viaHost.size());
            assertEquals("mine", viaHost.get(0), "不在过滤范围内的会话不被骨架接管");
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void internalToolsAreNotActionEvidence() {
        assertTrue(GoalOutcome.isEvidenceTool("bash"));
        assertTrue(GoalOutcome.isEvidenceTool("write"));
        assertFalse(GoalOutcome.isEvidenceTool("task"));
        assertFalse(GoalOutcome.isEvidenceTool("multitask"));
        assertFalse(GoalOutcome.isEvidenceTool("goal_update"));
        assertFalse(GoalOutcome.isEvidenceTool(null));
    }
}
