package features.ai.harness;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import features.ai.harness.i18n.HarnessFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActOptionsAmend;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.session.FileAgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.session.SessionRuns;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessExtension;
import org.noear.solon.ai.harness.agent.TaskTalent;
import org.noear.solon.ai.harness.agent.TaskWrapEvent;
import org.noear.solon.ai.agent.react.RunEndEvent;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用户按停止（SessionRuns.cancelAll(父会话)：只打断父代理执行线程，父流本身未取消）时，
 * 子代理要真的停下：卡在长工具里的被打断、不再走下一步，排队未启动的不再启动，运行锁随即释放。
 *
 * <p>本地假模型：含 sleep-task 的第一次请求让子代理调 bash 长命令，其余立即答 done。</p>
 */
public class TaskTalentStopSubtaskTest {
    private static final Pattern TASK_ID = Pattern.compile("<task_id>(task-[0-9a-f]{32})</task_id>");

    @TempDir
    Path ws;

    private HttpServer server;
    private final AtomicInteger sleepRequests = new AtomicInteger();
    private final AtomicInteger queuedRequests = new AtomicInteger();

    @BeforeEach
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    public void stop() {
        server.stop(0);
    }

    /** 含 gate-task 的请求等它放行再答复（把「停止」卡在子代理结束之前） */
    private final CountDownLatch gate = new CountDownLatch(1);
    private final CountDownLatch gateReached = new CountDownLatch(1);

    private void handle(HttpExchange ex) throws IOException {
        String body = readBody(ex.getRequestBody());
        if (body.contains("queued-task")) {
            queuedRequests.incrementAndGet();
        }
        if (body.contains("gate-task")) {
            gateReached.countDown();
            try {
                gate.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        String message;
        String finish;
        if (body.contains("sleep-task") && sleepRequests.getAndIncrement() == 0) {
            message = "\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"bash\",\"arguments\":\"{\\\"command\\\":\\\"touch started && sleep 20\\\"}\"}}]";
            finish = "tool_calls";
        } else {
            message = "\"role\":\"assistant\",\"content\":\"done\"";
            finish = "stop";
        }

        String usage = "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}";
        if (body.replace(" ", "").contains("\"stream\":true")) {
            send(ex, "text/event-stream; charset=utf-8",
                    "data: {\"id\":\"x\",\"object\":\"chat.completion.chunk\",\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{" + message + "},\"finish_reason\":null}]}\n\n"
                            + "data: {\"id\":\"x\",\"object\":\"chat.completion.chunk\",\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"" + finish + "\"}]," + usage + "}\n\n"
                            + "data: [DONE]\n\n");
        } else {
            send(ex, "application/json", "{\"id\":\"x\",\"object\":\"chat.completion\",\"model\":\"m\",\"choices\":[{\"index\":0,\"message\":{"
                    + message + "},\"finish_reason\":\"" + finish + "\"}]," + usage + "}");
        }
    }

    private static String readBody(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        for (int n; (n = in.read(buf)) > 0; ) {
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void send(HttpExchange ex, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static TaskTalent.SingleTaskOp op(int n, String prompt) {
        TaskTalent.SingleTaskOp op = HarnessFixtures.taskOp("bash", n);
        op.prompt = prompt;
        return op;
    }

    @Test
    @SuppressWarnings("unchecked")
    public void stopInterruptsStartedSubtaskAndSkipsQueuedOnes() throws Throwable {
        ChatConfig model = new ChatConfig();
        model.setName("srv");
        model.setModel("m");
        model.setApiUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
        model.setApiKey("test");

        Map<String, AgentSession> sessions = new ConcurrentHashMap<>();
        HarnessEngine engine = HarnessFixtures.builder(ws)
                .modelAdd(model)
                .defaultModel("srv")
                .autoRethink(false)
                .sessionProvider((id, parentId, create) -> create ? sessions.computeIfAbsent(id, InMemoryAgentSession::of) : sessions.get(id))
                .extensionAdd(new HarnessExtension() {
                    @Override
                    public void onRequestAssemble(HarnessEngine engine, String agentName, boolean isDelegated, ReActOptionsAmend o) {
                        o.retryConfig(0, 0);
                    }
                })
                .build();
        TaskTalent talent = engine.getTaskTalent();
        // 并发 1：第 2 条排在队列里，等第 1 条结束才会被取走
        talent.setDefaultConcurrency(1);

        // 父流活着（停止只打断父执行线程，不取消父流）
        AtomicReference<FluxSink<?>> sinkRef = new AtomicReference<>();
        Disposable parentStream = Flux.create(sinkRef::set).subscribe();
        ReActTrace trace = HarnessFixtures.trace(null, false);
        HarnessFixtures.invoke(trace.getOptions(), "setStreamSink", new Class<?>[]{FluxSink.class}, sinkRef.get());
        HarnessFixtures.bindTrace(engine.getSession("s1"), trace);

        // 父代理：后台启动两个（第 2 个排队），然后等结果
        AtomicReference<ToolResult> first = new AtomicReference<>();
        AtomicReference<ToolResult> result = new AtomicReference<>();
        Thread parent = parentRun(engine.getSession("s1"), () -> {
            first.set(talent.taskStart(op(1, "sleep-task"), ws.toString(), "s1", null));
            talent.taskStart(op(2, "queued-task"), ws.toString(), "s1", null);
            result.set(talent.taskWait(null, null, "s1", null));
        });
        try {
            parent.start();

            assertTrue(waitUntil(() -> Files.exists(ws.resolve("started")), 10_000), "第 1 个应已进入长命令");
            // 用户按停止：与宿主 interrupt 同一入口
            assertTrue(SessionRuns.cancelAll(engine.getSession("s1")));

            parent.join(5_000);
            assertFalse(parent.isAlive(), "停止后 task_wait 应立即返回");
            assertTrue(result.get().isError(), result.get().getContent());
            Matcher m = TASK_ID.matcher(first.get().getContent());
            assertTrue(m.find(), first.get().getContent());
            String id = m.group(1);

            // 子代理被停下：长命令被打断（远早于 20 秒），运行锁释放后马上可以续跑
            Map<String, String> running = (Map<String, String>) HarnessFixtures.get(talent, "runningTasks");
            assertTrue(waitUntil(() -> running.isEmpty(), 5_000), "子代理应已停下、释放运行锁");

            // 停下后不再走下一步，排队的第 2 条也不再启动
            Thread.sleep(500);
            assertEquals(1, sleepRequests.get(), "被打断的子代理不应再请求模型");
            assertEquals(0, queuedRequests.get(), "排队未启动的子任务不应再启动");

            // 子会话记的结果与返回给模型的一致
            Map<String, Object> meta = (Map<String, Object>) sessions.get(id).getContext().get(TaskTalent.META_SUBTASK);
            assertEquals(TaskTalent.STATUS_FAILURE, meta.get("status"), String.valueOf(meta));
            assertEquals("interrupted", meta.get("failureKind"), String.valueOf(meta));
        } finally {
            parent.interrupt();
            parentStream.dispose();
        }
    }

    /** 单个 task 在父线程上直接跑子代理：父线程被中断即打断子代理，同样不再走下一步 */
    @Test
    @SuppressWarnings("unchecked")
    public void stopInterruptsSingleTask() throws Throwable {
        ChatConfig model = new ChatConfig();
        model.setName("srv");
        model.setModel("m");
        model.setApiUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
        model.setApiKey("test");

        HarnessEngine engine = HarnessFixtures.builder(ws)
                .modelAdd(model)
                .defaultModel("srv")
                .autoRethink(false)
                .extensionAdd(new HarnessExtension() {
                    @Override
                    public void onRequestAssemble(HarnessEngine engine, String agentName, boolean isDelegated, ReActOptionsAmend o) {
                        o.retryConfig(0, 0);
                    }
                })
                .build();
        TaskTalent talent = engine.getTaskTalent();

        List<Object> events = new CopyOnWriteArrayList<>();
        AtomicReference<FluxSink<?>> sinkRef = new AtomicReference<>();
        Disposable parentStream = Flux.create(sinkRef::set).subscribe(events::add);
        ReActTrace trace = HarnessFixtures.trace(null, false);
        HarnessFixtures.invoke(trace.getOptions(), "setStreamSink", new Class<?>[]{FluxSink.class}, sinkRef.get());
        HarnessFixtures.bindTrace(engine.getSession("s1"), trace);

        TaskTalent.SingleTaskOp op = new TaskTalent.SingleTaskOp();
        op.agent_name = "bash";
        op.description = "d";
        op.prompt = "sleep-task";

        AtomicReference<ToolResult> result = new AtomicReference<>();
        Thread parent = parentRun(engine.getSession("s1"), () -> result.set(talent.task(op, ws.toString(), "s1", null)));
        try {
            parent.start();

            assertTrue(waitUntil(() -> Files.exists(ws.resolve("started")), 10_000), "应已进入长命令");
            // 用户按停止：与宿主 interrupt 同一入口
            assertTrue(SessionRuns.cancelAll(engine.getSession("s1")));

            parent.join(5_000);
            assertFalse(parent.isAlive(), "子代理应已停下");
            String out = result.get().getContent();
            assertTrue(result.get().isError(), out);
            assertTrue(out.contains("<failure_kind>interrupted</failure_kind>") && out.contains("<task_id>"), out);
            assertTrue(stillInterrupted.get(), "中断标志应还给父代理线程，父代理才会停下");
            assertTrue(subEndsReadAsStopped(events), "转给前端的结束事件要能读出被停止（否则实时显示成功）");
            Thread.sleep(500);
            assertEquals(1, sleepRequests.get(), "被打断的子代理不应再请求模型");
        } finally {
            parent.interrupt();
            parentStream.dispose();
        }
    }

    /**
     * 不推进取消代数的停止（cancelActive，即 PREEMPT_STREAM）：只靠代数判断不出父代理被停了，
     * 要靠父会话上的联动句柄停下子代理，并把中断标志还给父线程。
     */
    @Test
    public void cancelActiveStopsSingleTask() throws Throwable {
        HarnessEngine engine = engine(null);
        TaskTalent talent = engine.getTaskTalent();

        List<Object> events = new CopyOnWriteArrayList<>();
        Disposable parentStream = bindParentStream(engine, events, null);

        AtomicReference<ToolResult> result = new AtomicReference<>();
        Thread parent = parentRun(engine.getSession("s1"), () -> result.set(talent.task(singleOp("sleep-task"), ws.toString(), "s1", null)));
        try {
            parent.start();

            assertTrue(waitUntil(() -> Files.exists(ws.resolve("started")), 10_000), "应已进入长命令");
            assertTrue(SessionRuns.cancelActive(engine.getSession("s1")));

            parent.join(5_000);
            assertFalse(parent.isAlive(), "子代理应已停下");
            String out = result.get().getContent();
            assertTrue(result.get().isError(), out);
            assertTrue(out.contains("<failure_kind>interrupted</failure_kind>") && out.contains("<task_id>"), out);
            assertTrue(stillInterrupted.get(), "中断标志应还给父代理线程，父代理才不会多跑一步");
            assertTrue(subEndsReadAsStopped(events), "转给前端的结束事件要能读出被停止（否则实时显示成功）");
        } finally {
            parent.interrupt();
            parentStream.dispose();
        }
    }

    /**
     * 与宿主同序的两段式停止：先认领（推进取消代数、暂不打断），子代理恰好在这之后正常结束。
     * 结束一刻判定父代理已停止：结束事件照常转发但读得出被停止，结果按中断报，两边一致。
     */
    @Test
    @SuppressWarnings("unchecked")
    public void endAfterClaimIsReportedAsInterrupted() throws Throwable {
        Map<String, AgentSession> sessions = new ConcurrentHashMap<>();
        HarnessEngine engine = engine(sessions);
        TaskTalent talent = engine.getTaskTalent();

        List<Object> events = new CopyOnWriteArrayList<>();
        Disposable parentStream = bindParentStream(engine, events, null);

        AtomicReference<ToolResult> result = new AtomicReference<>();
        Thread parent = new Thread(() -> result.set(talent.task(singleOp("gate-task"), ws.toString(), "s1", null)));
        SessionRuns.Claim claim = null;
        try {
            parent.start();
            assertTrue(gateReached.await(10, TimeUnit.SECONDS), "子代理应已在请求模型");
            // 宿主 prepareInterrupt：认领（推进代数），还没 commit（没打断）
            claim = SessionRuns.claim(engine.getSession("s1"));
            gate.countDown();

            parent.join(5_000);
            assertFalse(parent.isAlive());
            String out = result.get().getContent();
            assertTrue(result.get().isError(), out);
            assertTrue(out.contains("<failure_kind>interrupted</failure_kind>"), out);
            assertTrue(subEndsReadAsStopped(events), "转给前端的结束事件要能读出被停止（否则实时显示成功）");

            Matcher m = TASK_ID.matcher(out);
            assertTrue(m.find(), out);
            Map<String, Object> meta = (Map<String, Object>) sessions.get(m.group(1)).getContext().get(TaskTalent.META_SUBTASK);
            assertEquals(TaskTalent.STATUS_FAILURE, meta.get("status"), String.valueOf(meta));
            assertEquals("interrupted", meta.get("failureKind"), String.valueOf(meta));
        } finally {
            if (claim != null) {
                claim.close();
            }
            gate.countDown();
            parent.interrupt();
            parentStream.dispose();
        }
    }

    /**
     * 后台任务：结束事件已转给前端（已定为成功）之后父代理才被停止。停止不再改判为中断，
     * 子会话记下的与前端看到的一致（都是成功）。
     */
    @Test
    @SuppressWarnings("unchecked")
    public void settledSubtaskStaysSuccessWhenStoppedAfterEnd() throws Throwable {
        Map<String, AgentSession> sessions = new ConcurrentHashMap<>();
        HarnessEngine engine = engine(sessions);
        TaskTalent talent = engine.getTaskTalent();

        List<Object> events = new CopyOnWriteArrayList<>();
        // 前端一收到子代理的结束事件就按停止（在 worker 线程上同步发生，落在「已转发、未收尾」的窗口里）
        Disposable parentStream = bindParentStream(engine, events, () -> SessionRuns.cancelAll(engine.getSession("s1")));

        AtomicReference<ToolResult> started = new AtomicReference<>();
        Thread parent = parentRun(engine.getSession("s1"), () -> {
            started.set(talent.taskStart(op(1, "quick-task"), ws.toString(), "s1", null));
            talent.taskWait(null, null, "s1", null);
        });
        try {
            parent.start();
            parent.join(10_000);
            assertFalse(parent.isAlive());

            assertTrue(hasSubEnd(events), "结束事件应已转给前端");
            for (Object e : events) {
                if (isSubEnd(e)) {
                    assertNull(TaskTalent.failureKindOf((RunEndEvent) ((TaskWrapEvent) e).getRealEvent()), "正常结束不能读成失败或被停止");
                }
            }
            Matcher m = TASK_ID.matcher(started.get().getContent());
            assertTrue(m.find(), started.get().getContent());
            Map<String, String> running = (Map<String, String>) HarnessFixtures.get(talent, "runningTasks");
            assertTrue(waitUntil(running::isEmpty, 5_000), "子代理应已收尾");
            Map<String, Object> meta = (Map<String, Object>) sessions.get(m.group(1)).getContext().get(TaskTalent.META_SUBTASK);
            assertEquals(TaskTalent.STATUS_SUCCESS, meta.get("status"), String.valueOf(meta));
            assertTrue(stillInterrupted.get(), "停止留下的中断标志应还在父代理线程上");
        } finally {
            parent.interrupt();
            parentStream.dispose();
        }
    }

    private HarnessEngine engine(Map<String, AgentSession> sessions) {
        ChatConfig model = new ChatConfig();
        model.setName("srv");
        model.setModel("m");
        model.setApiUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
        model.setApiKey("test");

        HarnessEngine.Builder builder = HarnessFixtures.builder(ws)
                .modelAdd(model)
                .defaultModel("srv")
                .autoRethink(false)
                .extensionAdd(new HarnessExtension() {
                    @Override
                    public void onRequestAssemble(HarnessEngine engine, String agentName, boolean isDelegated, ReActOptionsAmend o) {
                        o.retryConfig(0, 0);
                    }
                });
        if (sessions != null) {
            builder.sessionProvider((id, parentId, create) -> create ? sessions.computeIfAbsent(id, InMemoryAgentSession::of) : sessions.get(id));
        }
        return builder.build();
    }

    /** 父流活着并记下收到的事件；onSubEnd 非空时在收到子代理结束事件那一刻同步执行 */
    private static Disposable bindParentStream(HarnessEngine engine, List<Object> events, Runnable onSubEnd) throws Throwable {
        AtomicReference<FluxSink<?>> sinkRef = new AtomicReference<>();
        Disposable parentStream = Flux.create(sinkRef::set).subscribe(e -> {
            events.add(e);
            if (onSubEnd != null && isSubEnd(e)) {
                onSubEnd.run();
            }
        });
        ReActTrace trace = HarnessFixtures.trace(null, false);
        HarnessFixtures.invoke(trace.getOptions(), "setStreamSink", new Class<?>[]{FluxSink.class}, sinkRef.get());
        HarnessFixtures.bindTrace(engine.getSession("s1"), trace);
        return parentStream;
    }

    private static boolean isSubEnd(Object e) {
        return e instanceof TaskWrapEvent && ((TaskWrapEvent) e).getRealEvent() instanceof RunEndEvent;
    }

    /** 转给前端的子代理结束事件（可能没有：父流已取消时不转）都读得出「被停止」 */
    private static boolean subEndsReadAsStopped(List<Object> events) {
        for (Object e : events) {
            if (isSubEnd(e) && TaskTalent.isStopKind(TaskTalent.failureKindOf((RunEndEvent) ((TaskWrapEvent) e).getRealEvent())) == false) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasSubEnd(List<Object> events) {
        for (Object e : events) {
            if (isSubEnd(e)) {
                return true;
            }
        }
        return false;
    }

    private static TaskTalent.SingleTaskOp singleOp(String prompt) {
        TaskTalent.SingleTaskOp op = new TaskTalent.SingleTaskOp();
        op.agent_name = "bash";
        op.description = "d";
        op.prompt = prompt;
        return op;
    }

    /**
     * 落盘会话（与 soloncode 同为 FileAgentSession）：单个 task 停止后，磁盘上的子会话状态要与返回给模型的一致。
     * 线程带着中断标志写快照会被 Files.write 拒绝（ClosedByInterruptException），只记一条日志，状态就停在 success。
     */
    @Test
    @SuppressWarnings("unchecked")
    public void stopSingleTaskPersistsInterruptedToDisk() throws Throwable {
        ChatConfig model = new ChatConfig();
        model.setName("srv");
        model.setModel("m");
        model.setApiUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
        model.setApiKey("test");

        final String dir = ws.resolve(".sessions").toString();
        Map<String, AgentSession> sessions = new ConcurrentHashMap<>();
        HarnessEngine engine = HarnessFixtures.builder(ws)
                .modelAdd(model)
                .defaultModel("srv")
                .autoRethink(false)
                .sessionProvider((id, parentId, create) -> create
                        ? sessions.computeIfAbsent(id, k -> new FileAgentSession(k, dir))
                        : sessions.get(id))
                .extensionAdd(new HarnessExtension() {
                    @Override
                    public void onRequestAssemble(HarnessEngine engine, String agentName, boolean isDelegated, ReActOptionsAmend o) {
                        o.retryConfig(0, 0);
                    }
                })
                .build();
        TaskTalent talent = engine.getTaskTalent();

        AtomicReference<FluxSink<?>> sinkRef = new AtomicReference<>();
        Disposable parentStream = Flux.create(sinkRef::set).subscribe();
        ReActTrace trace = HarnessFixtures.trace(null, false);
        HarnessFixtures.invoke(trace.getOptions(), "setStreamSink", new Class<?>[]{FluxSink.class}, sinkRef.get());
        HarnessFixtures.bindTrace(engine.getSession("s1"), trace);

        TaskTalent.SingleTaskOp op = new TaskTalent.SingleTaskOp();
        op.agent_name = "bash";
        op.description = "d";
        op.prompt = "sleep-task";

        AtomicReference<ToolResult> result = new AtomicReference<>();
        Thread parent = parentRun(engine.getSession("s1"), () -> result.set(talent.task(op, ws.toString(), "s1", null)));
        try {
            parent.start();

            assertTrue(waitUntil(() -> Files.exists(ws.resolve("started")), 10_000), "应已进入长命令");
            assertTrue(SessionRuns.cancelAll(engine.getSession("s1")));

            parent.join(5_000);
            assertFalse(parent.isAlive(), "子代理应已停下");
            String out = result.get().getContent();
            assertTrue(out.contains("<failure_kind>interrupted</failure_kind>"), out);
            Matcher m = TASK_ID.matcher(out);
            assertTrue(m.find(), out);

            // 重新从磁盘读：不是内存里的那个对象
            AgentSession reloaded = new FileAgentSession(m.group(1), dir);
            Map<String, Object> meta = (Map<String, Object>) reloaded.getContext().get(TaskTalent.META_SUBTASK);
            assertNotNull(meta, "子会话快照应已落盘");
            assertEquals(TaskTalent.STATUS_FAILURE, meta.get("status"), String.valueOf(meta));
            assertEquals("interrupted", meta.get("failureKind"), String.valueOf(meta));
        } finally {
            parent.interrupt();
            parentStream.dispose();
        }
    }

    private final AtomicBoolean stillInterrupted = new AtomicBoolean();

    /**
     * 模拟父代理的执行线程：像 ReActRequest 那样在父会话上登记「中断本线程」的运行句柄，
     * 停止经 SessionRuns.cancelAll(父会话) 触发（只打断父线程，不取消父流）。
     */
    private Thread parentRun(AgentSession parentSession, Runnable body) {
        return new Thread(() -> {
            final Thread self = Thread.currentThread();
            final AtomicBoolean disposed = new AtomicBoolean();
            Disposable run = new Disposable() {
                @Override
                public void dispose() {
                    if (disposed.compareAndSet(false, true)) {
                        self.interrupt();
                    }
                }

                @Override
                public boolean isDisposed() {
                    return disposed.get();
                }
            };
            SessionRuns.attach(parentSession, run);
            try {
                body.run();
                stillInterrupted.set(Thread.currentThread().isInterrupted());
            } finally {
                SessionRuns.detach(parentSession, run);
            }
        });
    }

    private static boolean waitUntil(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }
}
