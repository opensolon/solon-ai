package features.ai.harness.all;

import org.noear.solon.ai.harness.input.HarnessInput;

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
import org.noear.solon.ai.harness.host.HarnessHost;
import org.noear.solon.ai.harness.host.HostRun;
import org.noear.solon.ai.harness.host.HostSink;
import org.noear.solon.ai.harness.host.HostInput;
import org.noear.solon.ai.harness.host.RunOutcome;
import org.noear.solon.ai.harness.host.RunSink;
import org.noear.solon.ai.harness.host.SessionAdmin;
import org.noear.solon.ai.harness.InterruptMode;
import org.noear.solon.ai.harness.host.Disposition;
import org.noear.solon.ai.harness.host.SubmitResult;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.input.RejectReason;
import org.noear.solon.ai.harness.session.SessionOwnership;

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
        final List<HostInput> replyInputs = new CopyOnWriteArrayList<>();
        final List<HostRun> started = new CopyOnWriteArrayList<>();
        final List<HostRun> finished = new CopyOnWriteArrayList<>();
        final List<RunOutcome> outcomes = new CopyOnWriteArrayList<>();
        final List<Throwable> errors = new CopyOnWriteArrayList<>();
        final List<String> notOwners = new CopyOnWriteArrayList<>();
        final List<HostInput> notOwnerInputs = new CopyOnWriteArrayList<>();
        final List<RejectReason> rejectedReasons = new CopyOnWriteArrayList<>();
        final List<HostInput> rejectedInputs = new CopyOnWriteArrayList<>();
        final List<Integer> deferredPositions = new CopyOnWriteArrayList<>();
        final List<HostInput> deferredInputs = new CopyOnWriteArrayList<>();
        final List<HostInput> admittedInputs = new CopyOnWriteArrayList<>();
        final List<HostInput> steeredInputs = new CopyOnWriteArrayList<>();
        final List<HostInput> hitlExpiredInputs = new CopyOnWriteArrayList<>();
        final List<List<HitlCard>> hitls = new CopyOnWriteArrayList<>();
        final List<AgentEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void admitted(HostInput input) {
            admittedInputs.add(input);
        }

        @Override
        public void reply(HostInput input, String text) {
            replyInputs.add(input);
            replies.add(text);
        }

        @Override
        public RunSink open(HostRun run) {
            started.add(run);
            return RunSink.forwardTo(this, run);
        }

        @Override
        public void event(HostRun run, AgentEvent event) {
            events.add(event);
        }

        @Override
        public void runFinished(HostRun run, RunOutcome outcome) {
            finished.add(run);
            outcomes.add(outcome);
            if (outcome.getError() != null) {
                errors.add(outcome.getError());
            }
        }

        @Override
        public void hitl(HostInput input, List<HitlCard> cards) {
            hitls.add(cards);
        }

        @Override
        public void notOwner(HostInput input, String owner) {
            notOwnerInputs.add(input);
            notOwners.add(owner);
        }

        @Override
        public void rejected(HostInput input, RejectReason reason, String detail) {
            rejectedInputs.add(input);
            rejectedReasons.add(reason);
        }

        @Override
        public void deferred(HostInput input, int position) {
            deferredInputs.add(input);
            deferredPositions.add(position);
        }

        @Override
        public void steered(HostInput input) {
            steeredInputs.add(input);
        }

        @Override
        public void hitlExpired(HostInput input) {
            hitlExpiredInputs.add(input);
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

    /** 入队（管理态的典型写法；操作名由宿主自己在控制器层决定，不再进框架） */
    private static HarnessResult<Integer> enqueue(SessionAdmin admin, String sid, String text, InputOrigin origin) {
        return admin.call(sid, QueueExtension.class, (q, s) -> q.enqueue(s, text, origin));
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
        HarnessHost host = HarnessHost.builder().engines(k -> engine).sink(sink).build();
        try {
            SubmitResult r = host.submit("t", "nope", HarnessInput.of("hello").origin(InputOrigin.channel("web")));

            assertEquals(Disposition.REJECTED, r.getDisposition());
            assertEquals(RejectReason.SESSION_NOT_FOUND, r.getRejectReason());
            assertEquals(404, r.httpStatus());
            assertEquals(1, sink.rejectedReasons.size(), "拒绝原因已经经结构化回调发出");
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
        HarnessHost host = HarnessHost.builder().engines(k -> engine).sink(sink).build();
        try {
            assertEquals(Disposition.IGNORED, host.submit("t", "s1", HarnessInput.of("")).getDisposition());
            assertTrue(sink.started.isEmpty());
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void defaultSinkIsEnoughToRun() throws Exception {
        // 不传 sink：缺省通道只记日志，一轮仍然完整跑通（首次接入的零代码门槛）
        HarnessEngine engine = engine();
        engine.getSession("s1");
        HarnessHost host = HarnessHost.builder().engines(k -> engine).build();
        try {
            SubmitResult r = host.submit("t", "s1", HarnessInput.of("hello").origin(InputOrigin.channel("web")));
            assertEquals(Disposition.STARTED, r.getDisposition(), r.toString());
            AgentSession session = engine.getSessionOrNull("s1");
            await(() -> engine.status(session).isOccupied() == false, "缺省 sink 下也正常收尾");
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void everyRunStartedPairsWithExactlyOneRunFinished() throws Exception {
        HarnessEngine engine = engine();
        AgentSession session = engine.getSession("s1");
        RecordingSink sink = new RecordingSink();
        HarnessHost host = HarnessHost.builder().engines(k -> engine).sink(sink).build();
        try {
            SubmitResult r = host.submit("t", "s1", HarnessInput.of("hello").origin(InputOrigin.channel("web")));
            assertEquals(Disposition.STARTED, r.getDisposition());
            assertEquals(1, sink.started.size(), "open 在 submit 返回前已调用");
            assertSame(sink.started.get(0), r.getRun().getRun(), "RunHandle 指向同一轮");

            await(() -> sink.finished.size() == 1, "流终止后 runFinished 必须调用");
            await(() -> engine.status(session).isOccupied() == false, "终态后会话不再被占用");
            Thread.sleep(200);
            assertEquals(1, sink.finished.size(), "runFinished 恰好一次");
            assertSame(sink.started.get(0), sink.finished.get(0));
            assertEquals("web", sink.finished.get(0).getInput().getOrigin().getChannel());
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
        HarnessHost host = HarnessHost.builder().engines(k -> engine).sink(sink).executor(executor).build();
        try {
            assertEquals(Disposition.STARTED, host.submit("t", "s1", HarnessInput.of("first").origin(InputOrigin.channel("a"))).getDisposition());

            // 开流动作尚未执行：会话被受理占位占用，第二条进积压
            SubmitResult second = host.submit("t", "s1", HarnessInput.of("second").origin(InputOrigin.channel("b")));
            assertEquals(Disposition.DEFERRED, second.getDisposition());
            assertEquals(1, second.getBacklogPosition());
            assertEquals(202, second.httpStatus());
            assertEquals("b", sink.deferredInputs.get(sink.deferredInputs.size() - 1).getOrigin().getChannel(), "排队回调回给 b");

            // 执行第一条 → 终止后 afterRun 续发第二条（又一次开流动作）
            assertTrue(executor.runNext(5_000));
            await(() -> sink.finished.size() == 1, "第一条结束");
            assertTrue(executor.runNext(30_000), "第一条结束后应自动续发第二条");
            await(() -> sink.finished.size() == 2, "第二条结束");

            HostRun dispatched = sink.started.get(1);
            assertTrue(dispatched.getInput().isDispatched(), "第二条是队列续发");
            assertEquals("b", dispatched.getInput().getOrigin().getChannel(), "续发的一轮按当初排队那条的来源投递");
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
        HarnessHost host = HarnessHost.builder().engines(k -> engine).sink(sink).executor(executor).build();
        try {
            assertEquals(Disposition.STARTED, host.submit("t", "s1", HarnessInput.of("hello")).getDisposition());

            // 开流前被停止
            assertTrue(host.admin("t").interrupt("s1", InterruptMode.CANCEL_STREAM).isOk());
            assertTrue(executor.runNext(5_000));

            assertEquals(1, sink.finished.size(), "被取消的一轮同样恰好收尾一次");
            assertTrue(sink.errors.isEmpty(), "停止不是错误");
            assertEquals(RunOutcome.End.CANCELLED, sink.outcomes.get(0).getEnd());
            assertFalse(sink.outcomes.get(0).isStreamOpened(), "窗口内被取消：从未开流");
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
        HarnessHost host = HarnessHost.builder().engines(k -> engine).sink(sink).executor(rejecting).build();
        try {
            host.submit("t", "s1", HarnessInput.of("hello"));

            assertEquals(1, sink.finished.size());
            assertEquals(1, sink.errors.size(), "启动失败以错误收尾");
            assertEquals(RunOutcome.End.FAILED, sink.outcomes.get(0).getEnd());
            assertFalse(engine.status(session).isOccupied(), "开流前失败必须 discard，否则会话长期被判忙");
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void notOwnerIsReportedWithoutLoadingSession() throws Exception {
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
        HarnessHost host = HarnessHost.builder().engines(k -> engine).sink(sink).build();
        try {
            SubmitResult r = host.submit("t", "s1", HarnessInput.of("hello"));
            assertEquals(RejectReason.NOT_OWNER, r.getRejectReason());
            assertEquals("node-b", r.getOwner(), "持有者随结果返回，网关不必自己解析拒绝详情");
            assertEquals(307, r.httpStatus());
            assertEquals("node-b", sink.notOwners.get(0));
            assertFalse(sessions.containsKey("s1"), "非持有节点不加载（更不新建）会话");

            host.submit("t", "s1", HarnessInput.of("hello").selectedModel("m1").attr("reqId", "r1"));
            HostInput rejected = sink.notOwnerInputs.get(1);
            assertEquals("m1", rejected.getSelectedModel(), "非持有节点的拒绝也带着调用方的选择");
            assertEquals("r1", rejected.getAttr("reqId"), "宿主靠属性做请求关联");
            assertNull(rejected.getSession());

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
        HarnessHost host = HarnessHost.builder().engines(k -> engine).sink(sink).build();
        try {
            SubmitResult r = host.decide("t", "s1", "no-such-call", HITLAction.APPROVE, null, null);
            assertEquals(Disposition.HITL, r.getDisposition(), "过期卡不得续流（否则会把上一轮问题重跑）");
            assertTrue(sink.started.isEmpty());
            assertEquals(1, sink.hitlExpiredInputs.size(), "提示卡已失效（文案由宿主决定）");
            assertTrue(sink.hitlExpiredInputs.get(0).isResume(), "decide 总是续跑：界面续用原来的输出流");
            assertTrue(sink.hitlExpiredInputs.get(0).isHitlDecision());

            // submit + hitl 不带续跑：是审批但不是续跑（与 HarnessInput 同名方法同义）
            host.submit("t", "s1", HarnessInput.of((String) null).hitl(HITLAction.APPROVE, "no-such-call").resume(false));
            assertEquals(2, sink.hitlExpiredInputs.size());
            assertFalse(sink.hitlExpiredInputs.get(1).isResume());
            assertTrue(sink.hitlExpiredInputs.get(1).isHitlDecision());
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
            SubmitResult r = host.submit("t", "s1", HarnessInput.of("hello"));
            assertNotEquals(RejectReason.ENGINE_CLOSED, r.getRejectReason(), "engine_closed 应换新引擎重试");
            assertEquals(Disposition.STARTED, r.getDisposition());
            await(() -> sink.finished.size() == 1, "新引擎上的一轮正常收尾");
        } finally {
            fresh.shutdown();
        }
    }

    // ==================== 会话生命周期（宿主该做的事，窄门面） ====================

    @Test
    void openSessionCreatesAndSetsPrincipalInOneCall() throws Exception {
        HarnessEngine engine = engine();
        HarnessHost host = HarnessHost.builder().engines(k -> engine).build();
        try {
            AgentSession session = host.openSession("t", "s1", "u1");
            assertSame(session, engine.getSessionOrNull("s1"));
            assertEquals("u1", engine.getSessionPrincipal(session));

            // 同一主体重复开通是空操作；不同主体必须被拒绝（防止把 A 的会话交给 B 用）
            host.openSession("t", "s1", "u1");
            assertThrows(SecurityException.class, () -> host.openSession("t", "s1", "u2"));
            // 引擎不要求作用域（本地 / 单用户部署）：主体可空
            assertNotNull(host.openSession("t", "s9", null));
            assertNull(engine.getSessionPrincipal(engine.getSessionOrNull("s9")));
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void closeSessionRefusesWhileBusyThenRemoves() throws Exception {
        HarnessEngine engine = engine(GoalExtension.builder().jobManager(null).build());
        ManualExecutor executor = new ManualExecutor();
        HarnessHost host = HarnessHost.builder().engines(k -> engine).executor(executor).build();
        try {
            host.openSession("t", "s1", "u1");
            assertEquals(Disposition.STARTED, host.submit("t", "s1", HarnessInput.of("hello")).getDisposition());

            assertFalse(host.closeSession("t", "s1"), "忙时拒绝删除，且不做任何事");
            assertNotNull(engine.getSessionOrNull("s1"));

            assertTrue(executor.runNext(5_000));
            await(() -> engine.status(engine.getSessionOrNull("s1")).isOccupied() == false, "跑完");

            boolean[] cleaned = {false};
            assertTrue(host.closeSession("t", "s1", () -> cleaned[0] = true));
            assertTrue(cleaned[0], "存储删除回调在 removeSession 之后执行");
            assertTrue(host.closeSession("t", "ghost"), "本来就不存在也算关闭成功");
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void sessionsOfAnotherTenantAreNotFound() throws Exception {
        // 隔离来自「每个租户一个会话提供者」：这是宿主侧的责任（每个租户一个工作区 / 一个存储命名空间）。
        // 框架删掉了「单引擎宿主」捷径，但不会替你校验提供者的隔离性 —— 多租户共用一个引擎 + 共用存储，
        // 不同租户的会话仍然互相可见，且不会报错。
        Map<String, AgentSession> storeA = new ConcurrentHashMap<>();
        Map<String, AgentSession> storeB = new ConcurrentHashMap<>();
        HarnessEngine engineA = builder()
                .extensionAdd(QueueExtension.builder().build())
                .sessionProvider((id, create) -> create
                        ? storeA.computeIfAbsent(id, InMemoryAgentSession::of) : storeA.get(id)).build();
        HarnessEngine engineB = builder()
                .extensionAdd(QueueExtension.builder().build())
                .sessionProvider((id, create) -> create
                        ? storeB.computeIfAbsent(id, InMemoryAgentSession::of) : storeB.get(id)).build();
        engineB.getSession("sb");

        Map<String, HarnessEngine> byTenant = new ConcurrentHashMap<>();
        byTenant.put("a", engineA);
        byTenant.put("b", engineB);

        HarnessHost host = HarnessHost.builder().engines(byTenant::get).build();
        try {
            assertEquals(HarnessResult.Code.NOT_FOUND,
                    enqueue(host.admin("a"), "sb", "x", null).getCode(),
                    "A 的引擎看不到 B 的会话（提供者按租户隔离）");
            assertEquals(Disposition.REJECTED,
                    host.submit("a", "sb", HarnessInput.of("x")).getDisposition());
            assertNull(engineA.getSessionOrNull("sb"), "A 侧不得因此建出会话");

            // B 自己看得到
            assertTrue(enqueue(host.admin("b"), "sb", "x", null).isOk());
        } finally {
            engineA.shutdown();
            engineB.shutdown();
        }
    }

    // ==================== 管理态 ====================

    @Test
    void adminEnqueueOnIdleSessionDrainsImmediately() throws Exception {
        HarnessEngine engine = engine();
        AgentSession session = engine.getSession("s1");
        RecordingSink sink = new RecordingSink();
        HarnessHost host = HarnessHost.builder().engines(k -> engine).sink(sink).build();
        try {
            HarnessResult<Integer> r = enqueue(host.admin("t"), "s1", "from console", InputOrigin.channel("console"));
            assertTrue(r.isOk(), r.toString());

            await(() -> sink.finished.size() == 1, "空闲会话入队后应立即续发执行");
            assertEquals("console", sink.started.get(0).getInput().getOrigin().getChannel());
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
        HarnessHost host = HarnessHost.builder().engines(k -> engine).sink(sink).build();
        try {
            SessionAdmin admin = host.admin("t");
            assertEquals(HarnessResult.Code.NOT_FOUND, enqueue(admin, "ghost", "x", null).getCode(), "会话不存在");
            assertNull(engine.getSessionOrNull("ghost"), "管理门面同样只查不建");
            assertEquals(HarnessResult.Code.DISABLED,
                    admin.call("s1", GoalExtension.class, (g, s) -> HarnessResult.ok(g.get("s1"))).getCode(),
                    "goal 未装配");
            assertEquals(HarnessResult.Code.INVALID,
                    admin.call("s1", QueueExtension.class, (q, s) -> {
                        throw new IllegalArgumentException("bad");
                    }).getCode(), "扩展抛参数异常译为 INVALID");
            assertEquals(HarnessResult.Code.NOT_FOUND, admin.interrupt("ghost", InterruptMode.INTERRUPT_ALL).getCode());
            assertTrue(admin.call("s1", QueueExtension.class, (q, s) -> HarnessResult.ok(q.list(s))).isOk());
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void loopRoundRunsThroughHost() throws Exception {
        HarnessEngine engine = engine(LoopExtension.builder().jobManager(null).build());
        engine.getSession("s1");
        RecordingSink sink = new RecordingSink();
        HarnessHost host = HarnessHost.builder().engines(k -> engine).sink(sink).build();
        try {
            SessionAdmin admin = host.admin("t");
            HarnessResult<LoopTask> task = admin.call("s1", LoopExtension.class,
                    (l, s) -> l.schedule("s1", LoopSpec.of("ping").everyMinutes(5)));
            assertTrue(task.isOk(), task.toString());

            String id = task.getValue().getId();
            assertTrue(admin.call("s1", LoopExtension.class, (l, s) -> l.trigger("s1", id)).isOk());
            await(() -> sink.finished.stream().anyMatch(HostRun::isProgram), "loop 后台轮次经骨架收尾");
            HostRun run = sink.finished.stream().filter(HostRun::isProgram).findFirst().get();
            assertEquals("loop", run.getProgramChannel());
            assertEquals("loop", run.getInput().getOrigin().getChannel());
        } finally {
            engine.shutdown();
        }
    }
}
