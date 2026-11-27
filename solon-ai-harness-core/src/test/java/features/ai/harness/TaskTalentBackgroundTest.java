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
import org.noear.solon.ai.agent.react.ReActRequest;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.RunEndEvent;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.session.SessionRuns;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatSession;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessExtension;
import org.noear.solon.ai.harness.agent.TaskTalent;
import org.noear.solon.ai.harness.agent.TaskWrapEvent;
import org.noear.solon.ai.harness.i18n.HarnessI18n;
import org.noear.solon.ai.harness.i18n.HarnessI18nKeys;
import org.noear.solon.ai.talents.cli.TerminalTalent;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 后台子任务（task_start / task_wait / task_stop）：先完成的先取回、超时只是先返回、单独停一个、
 * 父代理这一轮结束时取消没完成的并把结果补回索引；被取消的可带 task_id 接着做。
 *
 * <p>本地假模型：提示词含 slow 的第一次请求卡住（模拟子代理卡在长调用里、不再产出事件），其余立即答 done。</p>
 */
public class TaskTalentBackgroundTest {
    private static final Pattern TASK_ID = Pattern.compile("<task_id>(task-[0-9a-f]{32})</task_id>");

    @TempDir
    Path ws;

    private HttpServer server;
    private ExecutorService httpPool;
    private final CountDownLatch slowArrived = new CountDownLatch(1);
    private final CountDownLatch releaseSlow = new CountDownLatch(1);
    private final AtomicBoolean slowSeen = new AtomicBoolean();

    @BeforeEach
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // 卡住的请求不能挡住其他请求
        httpPool = Executors.newCachedThreadPool();
        server.setExecutor(httpPool);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    public void stop() {
        releaseSlow.countDown();
        server.stop(0);
        httpPool.shutdownNow();
    }

    private void handle(HttpExchange ex) throws IOException {
        String body = readBody(ex.getRequestBody());
        if (body.contains("tool-task")) {
            handleToolTask(ex, body);
            return;
        }
        if (body.contains("slow-task") && slowSeen.compareAndSet(false, true)) {
            slowArrived.countDown();
            try {
                releaseSlow.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        String message = "\"role\":\"assistant\",\"content\":\"done\"";
        String usage = "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}";
        if (body.replace(" ", "").contains("\"stream\":true")) {
            send(ex, "text/event-stream; charset=utf-8",
                    "data: {\"id\":\"x\",\"object\":\"chat.completion.chunk\",\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{" + message + "},\"finish_reason\":null}]}\n\n"
                            + "data: {\"id\":\"x\",\"object\":\"chat.completion.chunk\",\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]," + usage + "}\n\n"
                            + "data: [DONE]\n\n");
        } else {
            send(ex, "application/json", "{\"id\":\"x\",\"object\":\"chat.completion\",\"model\":\"m\",\"choices\":[{\"index\":0,\"message\":{"
                    + message + "},\"finish_reason\":\"stop\"}]," + usage + "}");
        }
    }

    /** tool-task 的模型请求数（第 1 次起命令，之后一律长等它） */
    private final AtomicInteger toolTaskRequests = new AtomicInteger();
    private final CountDownLatch waitIssued = new CountDownLatch(1);
    private static final Pattern CMD_ID = Pattern.compile("session_id: ([A-Za-z0-9_-]+)");

    /** 子代理先 bash_start 起一个长命令，之后反复 bash_wait 长等它（模拟卡在吞掉中断的工具里） */
    private void handleToolTask(HttpExchange ex, String body) throws IOException {
        int n = toolTaskRequests.incrementAndGet();
        final String name;
        final String args;
        if (n == 1) {
            name = "bash_start";
            args = "{\\\"command\\\":\\\"sleep 30\\\",\\\"yield_time_ms\\\":100}";
        } else {
            Matcher m = CMD_ID.matcher(body);
            String cmdId = null;
            while (m.find()) {
                cmdId = m.group(1);
            }
            name = "bash_wait";
            args = "{\\\"session_id\\\":\\\"" + cmdId + "\\\",\\\"yield_time_ms\\\":20000}";
            waitIssued.countDown();
        }

        String call = "\"tool_calls\":[{\"index\":0,\"id\":\"c" + n + "\",\"type\":\"function\",\"function\":{\"name\":\""
                + name + "\",\"arguments\":\"" + args + "\"}}]";
        String usage = "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}";
        if (body.replace(" ", "").contains("\"stream\":true")) {
            send(ex, "text/event-stream; charset=utf-8",
                    "data: {\"id\":\"x\",\"object\":\"chat.completion.chunk\",\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"," + call + "},\"finish_reason\":null}]}\n\n"
                            + "data: {\"id\":\"x\",\"object\":\"chat.completion.chunk\",\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]," + usage + "}\n\n"
                            + "data: [DONE]\n\n");
        } else {
            send(ex, "application/json", "{\"id\":\"x\",\"object\":\"chat.completion\",\"model\":\"m\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                    + call + "},\"finish_reason\":\"tool_calls\"}]," + usage + "}");
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
        TaskTalent.SingleTaskOp op = HarnessFixtures.taskOp("explore", n);
        op.prompt = prompt;
        return op;
    }

    private HarnessEngine engine(Map<String, AgentSession> sessions) {
        ChatConfig model = new ChatConfig();
        model.setName("srv");
        model.setModel("m");
        model.setApiUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
        model.setApiKey("test");

        return HarnessFixtures.builder(ws)
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
    }

    /** 模拟父代理执行 task_start：按给定调用 id 调用，并像 ReAct 那样把工具结果放进父 trace 的工作记忆 */
    private static ToolResult start(TaskTalent talent, ReActTrace trace, String callId, TaskTalent.SingleTaskOp op, String cwd) {
        trace.setActingCallId(callId);
        ToolResult r = talent.taskStart(op, cwd, "s1", null);
        trace.getWorkingMemory().addMessage(new ToolMessage(r, TaskTalent.TOOL_TASK_START, callId, false));
        return r;
    }

    private static String cmdIdOf(ToolResult r) {
        Matcher m = Pattern.compile("session_id: (\\S+)").matcher(r.getContent());
        assertTrue(m.find() && r.getContent().contains("status: running"), r.getContent());
        return m.group(1);
    }

    private static String idOf(ToolResult r) {
        Matcher m = TASK_ID.matcher(r.getContent());
        assertTrue(m.find(), r.getContent());
        return m.group(1);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> indexItemOf(ReActTrace trace, String callId) {
        for (ChatMessage message : trace.getWorkingMemory().getMessages()) {
            if (message instanceof ToolMessage && callId.equals(((ToolMessage) message).getToolCallId())) {
                return ((List<Map<String, Object>>) message.getMetadataAs(TaskTalent.META_SUBTASKS)).get(0);
            }
        }
        throw new AssertionError("no tool message: " + callId);
    }

    /**
     * 先完成的先取回；父代理这一轮结束时，没完成的后台任务被取消（经代理拦截器），本轮任务的结果补回各自 task_start 的索引项；
     * 被取消的任务之后仍可带 task_id 接着做。
     */
    @Test
    @SuppressWarnings("unchecked")
    public void firstDoneFirstAndRoundEndCancelsTheRest() throws Throwable {
        Map<String, AgentSession> sessions = new ConcurrentHashMap<>();
        HarnessEngine engine = engine(sessions);
        TaskTalent talent = engine.getTaskTalent();

        AtomicReference<FluxSink<?>> sinkRef = new AtomicReference<>();
        List<Object> parentEvents = new CopyOnWriteArrayList<>();
        Disposable parentStream = Flux.create(sinkRef::set).subscribe(parentEvents::add);
        ReActTrace trace = traceWithSink(sinkRef.get());
        AgentSession parent = engine.getSession("s1");
        HarnessFixtures.bindTrace(parent, trace);
        HarnessFixtures.set(trace, "session", parent);

        try {
            String slowId = idOf(start(talent, trace, "call-slow", op(1, "slow-task"), ws.toString()));
            String fastId = idOf(start(talent, trace, "call-fast", op(2, "fast-task"), ws.toString()));
            assertTrue(slowArrived.await(10, TimeUnit.SECONDS), "慢任务应已发出模型请求");

            // 不指定 task_id：返回最先完成的那个，并列出还没取回的
            ToolResult first = talent.taskWait(null, null, "s1", null);
            assertFalse(first.isError(), first.getContent());
            String out = first.getContent();
            assertTrue(out.contains("<task_id>" + fastId + "</task_id>") && out.contains("<result_status>success</result_status>"), out);
            assertTrue(out.contains("<background_tasks>") && out.contains("<task_id>" + slowId + "</task_id>")
                    && out.contains("<status>running</status>"), out);

            // 超时只是先返回：任务继续跑
            ToolResult timeout = talent.taskWait(slowId, 100, "s1", null);
            assertFalse(timeout.isError(), timeout.getContent());
            assertTrue(timeout.getContent().contains("<status>running</status>"), timeout.getContent());

            // 父代理这一轮结束（拦截器）：慢任务被取消
            talent.getInterceptor().onAgentEnd(trace);

            assertEquals(TaskTalent.STATUS_SUCCESS, indexItemOf(trace, "call-fast").get("status"));
            Map<String, Object> slowItem = indexItemOf(trace, "call-slow");
            assertEquals(TaskTalent.STATUS_FAILURE, slowItem.get("status"), String.valueOf(slowItem));
            assertEquals("cancelled", slowItem.get("failureKind"), String.valueOf(slowItem));

            // 队列已清空
            assertTrue(talent.taskWait(null, null, "s1", null).getContent().startsWith("WARNING: "));
            assertTrue(talent.taskWait(slowId, null, "s1", null).isError(), "上一轮的任务不能再等");

            // 放行后原线程退出；子会话记的结果与索引一致
            releaseSlow.countDown();
            Map<String, String> running = (Map<String, String>) HarnessFixtures.get(talent, "runningTasks");
            assertTrue(waitUntil(() -> running.containsKey(slowId) == false), "原子任务线程应已退出");
            Map<String, Object> meta = (Map<String, Object>) sessions.get(slowId).getContext().get(TaskTalent.META_SUBTASK);
            assertEquals("cancelled", meta.get("failureKind"), String.valueOf(meta));

            // 被取消的可带 task_id 接着做
            TaskTalent.SingleTaskOp again = new TaskTalent.SingleTaskOp();
            again.agent_name = "explore";
            again.prompt = "继续";
            again.task_id = slowId;
            trace.setActingCallId("call-resume");
            ToolResult resumed = talent.task(again, ws.toString(), "s1", null);
            assertFalse(resumed.isError(), resumed.getContent());
            assertTrue(resumed.getContent().contains("<task_id>" + slowId + "</task_id>"), resumed.getContent());

            // 包装事件标明委派方式，以本次调用为准：task_start 发起的为后台，同一任务改用 task 接着做就不是
            Map<String, Boolean> backgroundByCall = new HashMap<>();
            for (Object e : parentEvents) {
                if (e instanceof TaskWrapEvent) {
                    TaskWrapEvent w = (TaskWrapEvent) e;
                    Boolean prev = backgroundByCall.put(w.getParentCallId(), w.isBackground());
                    assertTrue(prev == null || prev == w.isBackground(), "同一次调用的事件标记一致: " + w.getParentCallId());
                }
            }
            assertEquals(Boolean.TRUE, backgroundByCall.get("call-fast"), String.valueOf(backgroundByCall));
            assertEquals(Boolean.TRUE, backgroundByCall.get("call-slow"), String.valueOf(backgroundByCall));
            assertEquals(Boolean.FALSE, backgroundByCall.get("call-resume"), String.valueOf(backgroundByCall));
        } finally {
            parentStream.dispose();
        }
    }

    /**
     * task_stop：运行中的停下、视为已取回（之后不必再等），给出 task_id 可接着做；续跑撞车在入队时就拒绝；
     * 子代理用 bash_start 起的后台命令一并结束，别的会话起的不动。
     */
    @Test
    @SuppressWarnings("unchecked")
    public void stopRunningTask() throws Throwable {
        Map<String, AgentSession> sessions = new ConcurrentHashMap<>();
        HarnessEngine engine = engine(sessions);
        TaskTalent talent = engine.getTaskTalent();

        AtomicReference<FluxSink<?>> sinkRef = new AtomicReference<>();
        Disposable parentStream = Flux.create(sinkRef::set).subscribe();
        ReActTrace trace = traceWithSink(sinkRef.get());
        HarnessFixtures.bindTrace(engine.getSession("s1"), trace);
        TerminalTalent terminal = engine.getTerminalTalent();
        String parentCmd = null;

        try {
            String slowId = idOf(start(talent, trace, "call-slow", op(1, "slow-task"), ws.toString()));
            assertTrue(slowArrived.await(10, TimeUnit.SECONDS), "慢任务应已发出模型请求");

            // 子代理（子会话 id 即 task_id）与父会话各起一个后台命令
            String childCmd = cmdIdOf(terminal.bashStart("sleep 30", null, 0, 1000, 60_000, ws.toString(), slowId, null));
            parentCmd = cmdIdOf(terminal.bashStart("sleep 30", null, 0, 1000, 60_000, ws.toString(), "s1", null));

            // 仍在跑：同一任务再启动会被拒
            TaskTalent.SingleTaskOp dup = op(2, "继续");
            dup.task_id = slowId;
            ToolResult busy = talent.taskStart(dup, ws.toString(), "s1", null);
            assertTrue(busy.isError() && busy.getContent().contains(slowId), busy.getContent());

            // 并发已满：进入排队，提示说已排队而不是已在运行；停掉排队的不影响运行中的
            talent.setDefaultConcurrency(1);
            ToolResult queued = start(talent, trace, "call-queued", op(3, "queued-task"), ws.toString());
            assertTrue(queued.getContent().contains("<status>queued</status>")
                    && queued.getContent().contains(HarnessI18n.get(null, HarnessI18nKeys.TASK_START_QUEUED_HINT)), queued.getContent());
            assertFalse(queued.getContent().contains(HarnessI18n.get(null, HarnessI18nKeys.TASK_START_HINT)), queued.getContent());
            assertFalse(talent.taskStop(idOf(queued), "s1", null).isError());

            ToolResult stopped = talent.taskStop(slowId, "s1", null);
            assertFalse(stopped.isError(), stopped.getContent());
            assertTrue(stopped.getContent().contains("<task_id>" + slowId + "</task_id>")
                    && stopped.getContent().contains("<failure_kind>cancelled</failure_kind>"), stopped.getContent());
            assertTrue(talent.taskWait(null, null, "s1", null).getContent().startsWith("WARNING: "), "停下的视为已取回");

            // 模型请求仍卡在服务端（未放行）：取消后子任务线程照样退出，后台命令随之结束
            Map<String, String> running = (Map<String, String>) HarnessFixtures.get(talent, "runningTasks");
            assertTrue(waitUntil(() -> running.containsKey(slowId) == false), "原子任务线程应已退出");
            Map<String, Object> meta = (Map<String, Object>) sessions.get(slowId).getContext().get(TaskTalent.META_SUBTASK);
            assertEquals(TaskTalent.STATUS_FAILURE, meta.get("status"), String.valueOf(meta));
            assertEquals("cancelled", meta.get("failureKind"), String.valueOf(meta));

            String child = terminal.bashWait(childCmd, null, 0, 1000).getContent();
            assertTrue(child.contains("status: completed") && child.contains("terminated: true"), "子代理的后台命令应已结束: " + child);
            String other = terminal.bashWait(parentCmd, null, 0, 1000).getContent();
            assertTrue(other.contains("status: running"), "别的会话起的命令不动: " + other);
        } finally {
            if (parentCmd != null) {
                terminal.bashStop(parentCmd, null, 1000);
            }
            parentStream.dispose();
        }
    }

    /**
     * 子代理卡在吞掉中断的工具里（bash_wait 长等）时 task_stop：子代理不再往下推理、线程退出，它起的后台命令随之结束。
     * （真实对话 T6：停止后子代理还在反复调 bash_wait，命令一直跑。）
     */
    @Test
    @SuppressWarnings("unchecked")
    public void stopWhileSubagentBlockedInTool() throws Throwable {
        Map<String, AgentSession> sessions = new ConcurrentHashMap<>();
        HarnessEngine engine = engine(sessions);
        engine.setBashAsyncEnabled(true);
        TaskTalent talent = engine.getTaskTalent();

        AtomicReference<FluxSink<?>> sinkRef = new AtomicReference<>();
        List<Object> parentEvents = new CopyOnWriteArrayList<>();
        Disposable parentStream = Flux.create(sinkRef::set).subscribe(parentEvents::add);
        ReActTrace trace = traceWithSink(sinkRef.get());
        HarnessFixtures.bindTrace(engine.getSession("s1"), trace);

        try {
            TaskTalent.SingleTaskOp op = HarnessFixtures.taskOp("general", 1);
            op.prompt = "tool-task";
            String id = idOf(start(talent, trace, "call-tool", op, ws.toString()));
            assertTrue(waitIssued.await(10, TimeUnit.SECONDS), "子代理应已开始 bash_wait");
            // 让 bash_wait 真正进入等待
            Thread.sleep(500);

            ToolResult stopped = talent.taskStop(id, "s1", null);
            assertTrue(stopped.getContent().contains("<failure_kind>cancelled</failure_kind>"), stopped.getContent());
            int requestsAtStop = toolTaskRequests.get();

            Map<String, String> running = (Map<String, String>) HarnessFixtures.get(talent, "runningTasks");
            assertTrue(waitUntil(() -> running.containsKey(id) == false), "子任务线程应已退出");
            assertEquals(requestsAtStop, toolTaskRequests.get(), "停止后子代理不应再请求模型");

            TerminalTalent terminal = engine.getTerminalTalent();
            assertEquals(0, terminal.stopCommandsOf(id, "check"), "子代理起的后台命令应已随停止结束");

            // 结束事件照常转给宿主，并能读出「被停止」（实时显示被中断，不是绿勾）
            RunEndEvent end = null;
            for (Object e : parentEvents) {
                if (e instanceof TaskWrapEvent && ((TaskWrapEvent) e).getRealEvent() instanceof RunEndEvent) {
                    end = (RunEndEvent) ((TaskWrapEvent) e).getRealEvent();
                }
            }
            assertNotNull(end, "被停止的子任务也要转发结束事件");
            assertEquals("cancelled", TaskTalent.failureKindOf(end));
            assertTrue(TaskTalent.isStopKind(TaskTalent.failureKindOf(end)));
        } finally {
            parentStream.dispose();
        }
    }

    /**
     * 同一轮里停掉一个后台任务、再用 task_start 带原 task_id 续跑：轮次结束时两次调用的索引项都要补上各自的结果
     * （真实对话 T6：前一次被后一次覆盖，刷新后没有结果、显示红叉）。
     */
    @Test
    @SuppressWarnings("unchecked")
    public void roundEndSettlesEveryCallOfResumedTask() throws Throwable {
        Map<String, AgentSession> sessions = new ConcurrentHashMap<>();
        HarnessEngine engine = engine(sessions);
        TaskTalent talent = engine.getTaskTalent();

        AtomicReference<FluxSink<?>> sinkRef = new AtomicReference<>();
        Disposable parentStream = Flux.create(sinkRef::set).subscribe();
        ReActTrace trace = traceWithSink(sinkRef.get());
        AgentSession parent = engine.getSession("s1");
        HarnessFixtures.bindTrace(parent, trace);
        HarnessFixtures.set(trace, "session", parent);

        try {
            String id = idOf(start(talent, trace, "call-first", op(1, "slow-task"), ws.toString()));
            assertTrue(slowArrived.await(10, TimeUnit.SECONDS), "慢任务应已发出模型请求");
            assertFalse(talent.taskStop(id, "s1", null).isError());

            Map<String, String> running = (Map<String, String>) HarnessFixtures.get(talent, "runningTasks");
            assertTrue(waitUntil(() -> running.containsKey(id) == false), "原子任务线程应已退出");

            TaskTalent.SingleTaskOp again = op(2, "继续");
            again.task_id = id;
            assertEquals(id, idOf(start(talent, trace, "call-second", again, ws.toString())));
            ToolResult second = talent.taskWait(id, null, "s1", null);
            assertFalse(second.isError(), second.getContent());

            talent.getInterceptor().onAgentEnd(trace);

            Map<String, Object> first = indexItemOf(trace, "call-first");
            assertEquals(TaskTalent.STATUS_FAILURE, first.get("status"), String.valueOf(first));
            assertEquals("cancelled", first.get("failureKind"), String.valueOf(first));
            assertEquals(TaskTalent.STATUS_SUCCESS, indexItemOf(trace, "call-second").get("status"));
        } finally {
            parentStream.dispose();
        }
    }

    /**
     * 父代理异常退出（拦截器的结束回调被跳过）：引擎在运行句柄注销后兜底取消没完成的后台任务；
     * 会话又有运行流（下一轮已开出）时不动，留给那一轮收尾。
     */
    @Test
    @SuppressWarnings("deprecation")
    public void engineBackstopCancelsWhenAgentEndSkipped() throws Throwable {
        Map<String, AgentSession> sessions = new ConcurrentHashMap<>();
        HarnessEngine engine = engine(sessions);
        TaskTalent talent = engine.getTaskTalent();

        AtomicReference<FluxSink<?>> sinkRef = new AtomicReference<>();
        Disposable parentStream = Flux.create(sinkRef::set).subscribe();
        ReActTrace trace = traceWithSink(sinkRef.get());
        AgentSession parent = engine.getSession("s1");
        HarnessFixtures.bindTrace(parent, trace);

        try {
            String slowId = idOf(start(talent, trace, "call-slow", op(1, "slow-task"), ws.toString()));
            assertTrue(slowArrived.await(10, TimeUnit.SECONDS), "慢任务应已发出模型请求");

            // 引擎装配的请求（AdmittedRequest），模拟它的运行已结束、只剩 afterRun 回调
            ReActRequest request = engine.prompt("hi", engine.getMainAgent()).session(parent);

            Disposable nextRun = () -> { };
            SessionRuns.attach(parent, nextRun);
            try {
                HarnessFixtures.invoke(request, "afterRun", new Class<?>[0]);
            } finally {
                SessionRuns.detach(parent, nextRun);
            }
            ToolResult still = talent.taskWait(slowId, 100, "s1", null);
            assertTrue(still.getContent().contains("<status>running</status>"), "下一轮已开出时不能动: " + still.getContent());

            HarnessFixtures.invoke(request, "afterRun", new Class<?>[0]);

            Map<String, Object> slowItem = indexItemOf(trace, "call-slow");
            assertEquals(TaskTalent.STATUS_FAILURE, slowItem.get("status"), String.valueOf(slowItem));
            assertEquals("cancelled", slowItem.get("failureKind"), String.valueOf(slowItem));
            assertTrue(talent.taskWait(null, null, "s1", null).getContent().startsWith("WARNING: "), "兜底后队列已清空");
        } finally {
            parentStream.dispose();
        }
    }

    /** 父轨迹挂一个可随时取消的流 sink（模拟用户按停止） */
    private static ReActTrace traceWithSink(FluxSink<?> sink) throws Throwable {
        ReActTrace trace = HarnessFixtures.trace(null, false);
        HarnessFixtures.invoke(trace.getOptions(), "setStreamSink", new Class<?>[]{FluxSink.class}, sink);
        return trace;
    }

    private static boolean waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }
}
