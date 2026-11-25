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
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessExtension;
import org.noear.solon.ai.harness.agent.TaskTalent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 运行中子任务的双向关联：委派前在父 trace 登记「调用 id → 子任务索引」并落一次父快照，
 * 子会话记下 parentCallId；宿主在工具返回前（运行中刷新、重启）据此把调用关联回子会话。
 */
public class TaskTalentRunningIndexTest {
    @TempDir
    Path ws;

    private HttpServer server;
    private ExecutorService httpPool;
    private final CountDownLatch slowArrived = new CountDownLatch(1);
    private final CountDownLatch releaseSlow = new CountDownLatch(1);
    private final AtomicBoolean slowSeen = new AtomicBoolean();

    /** 父会话：统计快照次数 */
    private static final class CountingSession extends InMemoryAgentSession {
        final AtomicInteger snapshots = new AtomicInteger();

        CountingSession(String id) {
            super(id);
        }

        @Override
        public void updateSnapshot() {
            snapshots.incrementAndGet();
            super.updateSnapshot();
        }
    }

    @BeforeEach
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
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

    private static TaskTalent.MultiTaskOp op(int index, String prompt) {
        TaskTalent.MultiTaskOp op = HarnessFixtures.taskOp("explore", index);
        op.prompt = prompt;
        return op;
    }

    private static ToolCall call(String name) {
        return new ToolCall("0", "id-" + name, name, "{}", new HashMap<>());
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

    @Test
    @SuppressWarnings("unchecked")
    public void multitaskRegistersOnceBeforeDispatchAndLinksBothWays() throws Throwable {
        Map<String, AgentSession> sessions = new ConcurrentHashMap<>();
        CountingSession parent = new CountingSession("s1");
        sessions.put("s1", parent);
        HarnessEngine engine = engine(sessions);
        TaskTalent talent = engine.getTaskTalent();

        // 同一步里：先跑完的 task（结果还没进工作记忆）+ 正在执行的 multitask；另有上一步留下的旧条目
        ToolCall earlier = call(TaskTalent.TOOL_TASK);
        ToolCall acting = call(TaskTalent.TOOL_MULTITASK);
        ReActTrace trace = HarnessFixtures.trace(null, false);
        trace.setLastReasonMessage(new AssistantMessage("", null, Arrays.asList(earlier, acting), null));
        Map<String, Object> prior = new LinkedHashMap<>();
        prior.put(earlier.getUuid(), new ArrayList<>());
        prior.put("stale-call", new ArrayList<>());
        trace.setExtra(TaskTalent.EXTRA_RUNNING_SUBTASKS, prior);
        trace.setActingCallId(acting.getUuid());
        HarnessFixtures.bindTrace(parent, trace);

        List<TaskTalent.MultiTaskOp> ops = new ArrayList<>();
        ops.add(op(1, "slow-task"));
        ops.add(op(2, "fast-task"));

        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<ToolResult> pending = caller.submit(() -> talent.multitask(ops, ws.toString(), "s1", null));
            assertTrue(slowArrived.await(10, TimeUnit.SECONDS), "第 1 条应已发出模型请求");

            // 运行中：索引已登记，且只落了一次父快照
            Map<String, Object> index = (Map<String, Object>) trace.getExtra(TaskTalent.EXTRA_RUNNING_SUBTASKS);
            assertNotNull(index);
            assertTrue(index.containsKey(earlier.getUuid()), "同一步里先跑完的 task 条目不能丢: " + index);
            assertFalse(index.containsKey("stale-call"), "不属于当前这一步的旧条目应去掉: " + index);
            List<Map<String, Object>> items = (List<Map<String, Object>>) index.get(acting.getUuid());
            assertEquals(2, items.size(), String.valueOf(index));
            assertEquals(1, parent.snapshots.get(), "multitask 只在委派前落一次父快照");

            // 反向：已启动的子会话记下发起它的调用
            String slowId = (String) items.get(0).get("taskId");
            Map<String, Object> slowMeta = (Map<String, Object>) sessions.get(slowId).getContext().get(TaskTalent.META_SUBTASK);
            assertEquals(acting.getUuid(), slowMeta.get("parentCallId"));
            assertEquals("explore", items.get(0).get("agentName"));
            assertEquals(1, items.get(0).get("index"));

            releaseSlow.countDown();
            ToolResult r = pending.get(20, TimeUnit.SECONDS);
            assertFalse(r.isError(), r.getContent());

            // 返回后不删：结果进工作记忆前（同一步后续工具执行中）仍要能找到
            index = (Map<String, Object>) trace.getExtra(TaskTalent.EXTRA_RUNNING_SUBTASKS);
            assertTrue(index.containsKey(acting.getUuid()));
            String fastId = (String) items.get(1).get("taskId");
            Map<String, Object> fastMeta = (Map<String, Object>) sessions.get(fastId).getContext().get(TaskTalent.META_SUBTASK);
            assertEquals(acting.getUuid(), fastMeta.get("parentCallId"));
        } finally {
            caller.shutdownNow();
        }
    }

    @Test
    public void noActingCallIdMeansNoRegistration() throws Throwable {
        Map<String, AgentSession> sessions = new ConcurrentHashMap<>();
        CountingSession parent = new CountingSession("s1");
        sessions.put("s1", parent);
        HarnessEngine engine = engine(sessions);

        ReActTrace trace = HarnessFixtures.trace(null, false);
        HarnessFixtures.bindTrace(parent, trace);

        TaskTalent.SingleTaskOp op = new TaskTalent.SingleTaskOp();
        op.agent_name = "explore";
        op.description = "d";
        op.prompt = "fast-task";
        ToolResult r = engine.getTaskTalent().task(op, ws.toString(), "s1", null);

        assertFalse(r.isError(), r.getContent());
        assertNull(trace.getExtra(TaskTalent.EXTRA_RUNNING_SUBTASKS));
        assertEquals(0, parent.snapshots.get());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void invalidPlansAreNotRegistered() throws Throwable {
        Map<String, AgentSession> sessions = new ConcurrentHashMap<>();
        CountingSession parent = new CountingSession("s1");
        sessions.put("s1", parent);
        HarnessEngine engine = engine(sessions);

        ToolCall acting = call(TaskTalent.TOOL_TASK);
        ReActTrace trace = HarnessFixtures.trace(null, false);
        trace.setLastReasonMessage(new AssistantMessage("", null, Arrays.asList(acting), null));
        trace.setActingCallId(acting.getUuid());
        HarnessFixtures.bindTrace(parent, trace);

        TaskTalent.SingleTaskOp op = new TaskTalent.SingleTaskOp();
        op.agent_name = "no-such-agent";
        op.description = "d";
        op.prompt = "fast-task";
        ToolResult r = engine.getTaskTalent().task(op, ws.toString(), "s1", null);

        assertTrue(r.isError(), r.getContent());
        assertNull(trace.getExtra(TaskTalent.EXTRA_RUNNING_SUBTASKS), "跑不起来的任务不登记");
        assertEquals(0, parent.snapshots.get());
    }
}
