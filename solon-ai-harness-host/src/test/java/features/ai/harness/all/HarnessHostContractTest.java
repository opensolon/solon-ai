package features.ai.harness.all;

import org.noear.solon.ai.harness.HarnessResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentEvent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.intercept.HITLAction;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessExtension;
import org.noear.solon.ai.harness.extension.goal.GoalExtension;
import org.noear.solon.ai.harness.extension.loop.LoopExtension;
import org.noear.solon.ai.harness.extension.loop.LoopSpec;
import org.noear.solon.ai.harness.extension.loop.LoopTask;
import org.noear.solon.ai.harness.extension.queue.QueueExtension;
import org.noear.solon.ai.harness.extension.steer.SteerExtension;
import org.noear.solon.ai.harness.hitl.HitlCard;
import org.noear.solon.ai.harness.host.AdminGateway;
import org.noear.solon.ai.harness.host.HarnessHost;
import org.noear.solon.ai.harness.host.HostRun;
import org.noear.solon.ai.harness.host.HostSink;
import org.noear.solon.ai.harness.InterruptMode;
import org.noear.solon.ai.harness.host.Submitted;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.input.InputOutcome;
import org.noear.solon.ai.harness.spi.SessionOwnership;
import org.noear.solon.ai.mount.source.FileMountSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HarnessHost（L0 宿主骨架）契约：宿主直连 engine.handle 时必须自己写对的约定，由骨架代办。
 *
 * <p>假模型指向不可达地址：开流后很快以 error 终止，足以覆盖受理、开流、终态、续发的全部编排。</p>
 */
class HarnessHostContractTest {

    @TempDir
    Path tmp;

    // ==================== 夹具 ====================

    /** 记录型输出通道 */
    static final class RecordingSink implements HostSink {
        final List<String> replies = new CopyOnWriteArrayList<>();
        final List<InputOrigin> replyOrigins = new CopyOnWriteArrayList<>();
        final List<HostRun> started = new CopyOnWriteArrayList<>();
        final List<HostRun> finished = new CopyOnWriteArrayList<>();
        final List<Throwable> errors = new CopyOnWriteArrayList<>();
        final List<String> forwards = new CopyOnWriteArrayList<>();
        final List<List<HitlCard>> hitls = new CopyOnWriteArrayList<>();
        final List<AgentEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void reply(InputOrigin origin, String text) {
            replyOrigins.add(origin);
            replies.add(text);
        }

        @Override
        public void event(HostRun run, AgentEvent event) {
            events.add(event);
        }

        @Override
        public void runStarted(HostRun run) {
            started.add(run);
        }

        @Override
        public void runFinished(HostRun run, Throwable error) {
            finished.add(run);
            if (error != null) {
                errors.add(error);
            }
        }

        @Override
        public void hitl(InputOrigin origin, List<HitlCard> cards) {
            hitls.add(cards);
        }

        @Override
        public void forward(InputOrigin origin, String owner) {
            forwards.add(owner);
        }
    }

    /** 手动执行器：开流动作先挂起，测试决定何时执行（制造「调度窗口」） */
    static final class ManualExecutor implements Executor {
        final BlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        boolean runNext(long timeoutMs) throws InterruptedException {
            Runnable r = tasks.poll(timeoutMs, TimeUnit.MILLISECONDS);
            if (r == null) {
                return false;
            }
            r.run();
            return true;
        }
    }

    private final Map<String, AgentSession> sessions = new ConcurrentHashMap<>();

    private static ChatConfig model() {
        ChatConfig model = new ChatConfig();
        model.setName("fake");
        model.setModel("fake-model");
        model.setApiUrl("http://localhost:1/v1");
        model.setApiKey("test");
        return model;
    }

    /** 会话提供者遵守 createIfAbsent=false 无副作用的契约 */
    private HarnessEngine.Builder builder() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        return HarnessEngine.of(ws.toString(), tmp.resolve("home").toString())
                .sessionProvider((id, create) -> create
                        ? sessions.computeIfAbsent(id, InMemoryAgentSession::of)
                        : sessions.get(id))
                .modelAdd(model())
                .defaultModel("fake");
    }

    /** 普通构建链：queue + steer，再加上用例需要的扩展 */
    private HarnessEngine engine(HarnessExtension... extras) throws Exception {
        HarnessEngine.Builder b = builder()
                .extensionAdd(QueueExtension.builder().build())
                .extensionAdd(new SteerExtension());
        for (HarnessExtension e : extras) {
            b.extensionAdd(e);
        }
        return b.build();
    }

    /** 入队（管理态的典型写法） */
    private static HarnessResult<Integer> enqueue(AdminGateway admin, String sid, String text, InputOrigin origin) {
        return admin.call(sid, "queue.enqueue", QueueExtension.class, (q, s) -> {
            return q.enqueue(s, text, origin);
        });
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

    // ==================== 使用态 ====================

    @Test
    void unknownSessionIsRejectedWithoutCreatingIt() throws Exception {
        HarnessEngine engine = engine();
        RecordingSink sink = new RecordingSink();
        HarnessHost host = HarnessHost.builder().engine(engine).sink(sink).build();
        try {
            Submitted r = host.submit("t", "nope", "hello", InputOrigin.channel("web"));

            assertEquals(Submitted.Kind.REJECTED, r.getKind());
            assertEquals(InputOutcome.RejectReason.SESSION_NOT_FOUND, r.getRejectReason());
            assertEquals(404, r.httpStatus());
            assertEquals(1, sink.replies.size(), "提示已经经 sink 发出");
            assertNull(engine.getSessionOrNull("nope"), "只查不建：不得凭空新建会话");
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void emptyInputIsIgnored() throws Exception {
        HarnessEngine engine = engine();
        engine.getSession("s1");
        RecordingSink sink = new RecordingSink();
        HarnessHost host = HarnessHost.builder().engine(engine).sink(sink).build();
        try {
            assertEquals(Submitted.Kind.IGNORED, host.submit("t", "s1", "", null).getKind());
            assertTrue(sink.started.isEmpty());
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void everyRunStartedPairsWithExactlyOneRunFinished() throws Exception {
        HarnessEngine engine = engine();
        AgentSession session = engine.getSession("s1");
        RecordingSink sink = new RecordingSink();
        HarnessHost host = HarnessHost.builder().engine(engine).sink(sink).build();
        try {
            Submitted r = host.submit("t", "s1", "hello", InputOrigin.channel("web"));
            assertEquals(Submitted.Kind.STARTED, r.getKind());
            assertEquals(1, sink.started.size(), "runStarted 在 submit 返回前已调用");

            await(() -> sink.finished.size() == 1, "流终止后 runFinished 必须调用");
            await(() -> engine.status(session).isOccupied() == false, "终态后会话不再被占用");
            Thread.sleep(200);
            assertEquals(1, sink.finished.size(), "runFinished 恰好一次");
            assertSame(sink.started.get(0), sink.finished.get(0));
            assertEquals("web", sink.finished.get(0).getOrigin().getChannel());
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void busyInputIsDeferredThenDrainedWithItsOwnOrigin() throws Exception {
        HarnessEngine engine = engine();
        AgentSession session = engine.getSession("s1");
        RecordingSink sink = new RecordingSink();
        ManualExecutor executor = new ManualExecutor();
        HarnessHost host = HarnessHost.builder().engine(engine).sink(sink).executor(executor).build();
        try {
            assertEquals(Submitted.Kind.STARTED, host.submit("t", "s1", "first", InputOrigin.channel("a")).getKind());

            // 开流动作尚未执行：会话被受理占位占用，第二条进积压
            Submitted second = host.submit("t", "s1", "second", InputOrigin.channel("b"));
            assertEquals(Submitted.Kind.DEFERRED, second.getKind());
            assertEquals(1, second.getBacklogPosition());
            assertEquals(202, second.httpStatus());
            assertEquals("b", sink.replyOrigins.get(sink.replyOrigins.size() - 1).getChannel(), "排队提示回给 b");

            // 执行第一条 → 终止后 afterRun 续发第二条（又一次开流动作）
            assertTrue(executor.runNext(5_000));
            await(() -> sink.finished.size() == 1, "第一条结束");
            assertTrue(executor.runNext(30_000), "第一条结束后应自动续发第二条");
            await(() -> sink.finished.size() == 2, "第二条结束");

            HostRun dispatched = sink.started.get(1);
            assertTrue(dispatched.isDispatched(), "第二条是队列续发");
            assertEquals("b", dispatched.getOrigin().getChannel(), "续发的一轮按当初排队那条的来源投递");
            await(() -> engine.status(session).isOccupied() == false, "全部结束后会话空闲");
            assertEquals(0, engine.getExtension(QueueExtension.class).get().pendingSize(session));
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void cancelledInSchedulingWindowDoesNotOpenStream() throws Exception {
        HarnessEngine engine = engine();
        AgentSession session = engine.getSession("s1");
        RecordingSink sink = new RecordingSink();
        ManualExecutor executor = new ManualExecutor();
        HarnessHost host = HarnessHost.builder().engine(engine).sink(sink).executor(executor).build();
        try {
            assertEquals(Submitted.Kind.STARTED, host.submit("t", "s1", "hello", null).getKind());

            // 开流前被停止
            assertTrue(host.interrupt("t", "s1", InterruptMode.CANCEL_STREAM).isOk());
            assertTrue(executor.runNext(5_000));

            assertEquals(1, sink.finished.size(), "被取消的一轮同样恰好收尾一次");
            assertTrue(sink.errors.isEmpty(), "停止不是错误");
            assertTrue(sink.events.isEmpty(), "不开流");
            assertFalse(engine.status(session).isOccupied(), "discard 已释放受理占位");
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void startFailureReleasesSessionAndFinishesWithError() throws Exception {
        HarnessEngine engine = engine();
        AgentSession session = engine.getSession("s1");
        RecordingSink sink = new RecordingSink();
        Executor rejecting = r -> {
            throw new RejectedExecutionException("pool closed");
        };
        HarnessHost host = HarnessHost.builder().engine(engine).sink(sink).executor(rejecting).build();
        try {
            host.submit("t", "s1", "hello", null);

            assertEquals(1, sink.finished.size());
            assertEquals(1, sink.errors.size(), "启动失败以错误收尾");
            assertFalse(engine.status(session).isOccupied(), "开流前失败必须 discard，否则会话长期被判忙");
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void notOwnerIsForwardedWithoutLoadingSession() throws Exception {
        SessionOwnership other = new SessionOwnership() {
            @Override
            public boolean tryAcquire(String sessionId) {
                return false;
            }

            @Override
            public String ownerOf(String sessionId) {
                return "node-b";
            }

            @Override
            public void release(String sessionId) {
            }
        };
        HarnessEngine engine = builder().sessionOwnership(other)
                .extensionAdd(QueueExtension.builder().build())
                .build();
        RecordingSink sink = new RecordingSink();
        HarnessHost host = HarnessHost.builder().engine(engine).sink(sink).build();
        try {
            Submitted r = host.submit("t", "s1", "hello", null);
            assertEquals(InputOutcome.RejectReason.NOT_OWNER, r.getRejectReason());
            assertEquals(307, r.httpStatus());
            assertEquals("node-b", sink.forwards.get(0));
            assertFalse(sessions.containsKey("s1"), "非持有节点不加载（更不新建）会话");

            HarnessResult<Integer> admin = enqueue(host.admin("t"), "s1", "x", null);
            assertEquals(HarnessResult.Code.NOT_OWNER, admin.getCode());
            assertEquals("node-b", admin.getOwner());
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void staleHitlDecisionIsReportedAsExpired() throws Exception {
        HarnessEngine engine = engine();
        engine.getSession("s1");
        RecordingSink sink = new RecordingSink();
        HarnessHost host = HarnessHost.builder().engine(engine).sink(sink).build();
        try {
            Submitted r = host.decide("t", "s1", "no-such-call", HITLAction.APPROVE, null, null);
            assertEquals(Submitted.Kind.HITL, r.getKind(), "过期卡不得续流（否则会把上一轮问题重跑）");
            assertTrue(sink.started.isEmpty());
            assertEquals(new RecordingSink().hitlExpiredText(), sink.replies.get(0), "提示卡已失效");
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void closedEngineIsRetriedOnFreshOne() throws Exception {
        HarnessEngine stale = engine();
        HarnessEngine fresh = engine();
        fresh.getSession("s1");
        stale.shutdown();

        RecordingSink sink = new RecordingSink();
        HarnessEngine[] seq = {stale, fresh};
        int[] calls = {0};
        HarnessHost host = HarnessHost.builder()
                .engines(k -> seq[Math.min(calls[0]++, 1)])
                .sink(sink).build();
        try {
            Submitted r = host.submit("t", "s1", "hello", null);
            assertNotEquals(InputOutcome.RejectReason.ENGINE_CLOSED, r.getRejectReason(), "engine_closed 应换新引擎重试");
            assertEquals(Submitted.Kind.STARTED, r.getKind());
            await(() -> sink.finished.size() == 1, "新引擎上的一轮正常收尾");
        } finally {
            fresh.shutdown();
        }
    }

    // ==================== 管理态 ====================

    @Test
    void adminEnqueueOnIdleSessionDrainsImmediately() throws Exception {
        HarnessEngine engine = engine();
        AgentSession session = engine.getSession("s1");
        RecordingSink sink = new RecordingSink();
        HarnessHost host = HarnessHost.builder().engine(engine).sink(sink).build();
        try {
            HarnessResult<Integer> r = enqueue(host.admin("t"), "s1", "from console", InputOrigin.channel("console"));
            assertTrue(r.isOk(), r.toString());

            await(() -> sink.finished.size() == 1, "空闲会话入队后应立即续发执行");
            assertEquals("console", sink.started.get(0).getOrigin().getChannel());
            await(() -> engine.status(session).isOccupied() == false, "执行结束");
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void adminResultCodes() throws Exception {
        HarnessEngine engine = engine();
        engine.getSession("s1");
        RecordingSink sink = new RecordingSink();
        HarnessHost host = HarnessHost.builder().engine(engine).sink(sink)
                .adminAuthorizer((tenant, principal, sid, op) -> "admin".equals(principal))
                .build();
        try {
            AdminGateway admin = host.admin("t", "admin");
            assertEquals(HarnessResult.Code.NOT_FOUND, enqueue(admin, "ghost", "x", null).getCode(), "会话不存在");
            assertNull(engine.getSessionOrNull("ghost"), "管理门面同样只查不建");
            assertEquals(HarnessResult.Code.DISABLED,
                    admin.call("s1", "goal.get", GoalExtension.class, (g, s) -> HarnessResult.ok(g.get("s1"))).getCode(),
                    "goal 未装配");
            assertEquals(HarnessResult.Code.INVALID,
                    admin.call("s1", "x", QueueExtension.class, (q, s) -> {
                        throw new IllegalArgumentException("bad");
                    }).getCode(), "扩展抛参数异常译为 INVALID");
            assertEquals(HarnessResult.Code.NOT_FOUND, admin.interrupt("ghost", InterruptMode.INTERRUPT).getCode());
            assertTrue(admin.call("s1", "queue.list", QueueExtension.class, (q, s) -> HarnessResult.ok(q.list(s))).isOk());

            assertEquals(HarnessResult.Code.FORBIDDEN, enqueue(host.admin("t", "guest"), "s1", "x", null).getCode());
            assertEquals(HarnessResult.Code.FORBIDDEN, host.admin("t", "guest").interrupt("s1", InterruptMode.INTERRUPT).getCode());
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void loopRoundRunsThroughHost() throws Exception {
        HarnessEngine engine = engine(LoopExtension.builder().jobManager(null).build());
        engine.getSession("s1");
        RecordingSink sink = new RecordingSink();
        HarnessHost host = HarnessHost.builder().engine(engine).sink(sink).build();
        try {
            AdminGateway admin = host.admin("t");
            HarnessResult<LoopTask> task = admin.call("s1", "loop.schedule", LoopExtension.class,
                    (l, s) -> l.schedule("s1", LoopSpec.of("ping").everyMinutes(5)));
            assertTrue(task.isOk(), task.toString());

            String id = task.getValue().getId();
            assertTrue(admin.call("s1", "loop.trigger", LoopExtension.class,
                    (l, s) -> l.trigger("s1", id)).isOk());
            await(() -> sink.finished.stream().anyMatch(HostRun::isProgram), "loop 后台轮次经骨架收尾");
            HostRun run = sink.finished.stream().filter(HostRun::isProgram).findFirst().get();
            assertEquals("loop", run.getProgramChannel());
            assertEquals("loop", run.getOrigin().getChannel());
        } finally {
            engine.shutdown();
        }
    }
}
