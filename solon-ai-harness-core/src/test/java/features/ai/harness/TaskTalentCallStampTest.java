package features.ai.harness;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import features.ai.harness.i18n.HarnessFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.agent.react.ReActOptionsAmend;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.session.FileAgentSession;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.UserMessage;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 子任务按调用切分：同一 task_id 续跑多次时，子会话消息带调用戳（{@link TaskTalent#META_PARENT_CALL_ID}），
 * 父结果索引（{@link TaskTalent#META_SUBTASKS}）记每次调用自己的成败，宿主据此「每次调用一组、各回放各的」。
 */
public class TaskTalentCallStampTest {
    private static final Pattern TASK_ID = Pattern.compile("<task_id>(task-[0-9a-f]{32})</task_id>");

    @TempDir
    Path ws;

    private HttpServer server;
    private ExecutorService httpPool;
    /** 带 fail-once 的提示词只失败一次（HTTP 500），续跑时正常回复 */
    private final AtomicBoolean failed = new AtomicBoolean();

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
        server.stop(0);
        httpPool.shutdownNow();
    }

    private void handle(HttpExchange ex) throws IOException {
        String body = readBody(ex.getRequestBody());
        if (body.contains("fail-once") && failed.compareAndSet(false, true)) {
            send(ex, 500, "application/json", "{\"error\":{\"message\":\"boom\"}}");
            return;
        }

        String message = "\"role\":\"assistant\",\"content\":\"done\"";
        String usage = "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}";
        if (body.replace(" ", "").contains("\"stream\":true")) {
            send(ex, 200, "text/event-stream; charset=utf-8",
                    "data: {\"id\":\"x\",\"object\":\"chat.completion.chunk\",\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{" + message + "},\"finish_reason\":null}]}\n\n"
                            + "data: {\"id\":\"x\",\"object\":\"chat.completion.chunk\",\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]," + usage + "}\n\n"
                            + "data: [DONE]\n\n");
        } else {
            send(ex, 200, "application/json", "{\"id\":\"x\",\"object\":\"chat.completion\",\"model\":\"m\",\"choices\":[{\"index\":0,\"message\":{"
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

    private static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** 落盘提供者：子会话每次从磁盘打开，调用戳必须随快照持久化才能被看到 */
    private static AgentSessionProvider fileProvider(Path root) {
        Map<String, AgentSession> cache = new ConcurrentHashMap<>();
        return (id, parentId, create) -> {
            if (TaskTalent.isTaskId(id) && parentId != null) {
                Path dir = root.resolve(parentId).resolve("subtasks").resolve(id);
                return create || Files.isDirectory(dir) ? new FileAgentSession(id, dir.toString()) : null;
            }
            Path dir = root.resolve(id);
            return create || Files.isDirectory(dir) ? cache.computeIfAbsent(id, k -> new FileAgentSession(k, dir.toString())) : null;
        };
    }

    private HarnessEngine engine() {
        ChatConfig model = new ChatConfig();
        model.setName("srv");
        model.setModel("m");
        model.setApiUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
        model.setApiKey("test");

        return HarnessFixtures.builder(ws)
                .modelAdd(model)
                .defaultModel("srv")
                .autoRethink(false)
                .sessionProvider(fileProvider(ws.resolve("sessions")))
                .extensionAdd(new HarnessExtension() {
                    @Override
                    public void onRequestAssemble(HarnessEngine engine, String agentName, boolean isDelegated, ReActOptionsAmend o) {
                        o.retryConfig(0, 0);
                    }
                })
                .build();
    }

    /** 模拟父代理正在执行某个 task 调用（actingCallId 只在工具执行期间有值） */
    private static String acting(ReActTrace parent, String id) {
        ToolCall call = new ToolCall("0", id, TaskTalent.TOOL_TASK, "{}", new HashMap<>());
        parent.setLastReasonMessage(new AssistantMessage("", null, Collections.singletonList(call), null));
        parent.setActingCallId(call.getUuid());
        return call.getUuid();
    }

    private static TaskTalent.SingleTaskOp op(String taskId, String prompt) {
        TaskTalent.SingleTaskOp op = new TaskTalent.SingleTaskOp();
        op.agent_name = "explore";
        op.description = "d";
        op.prompt = prompt;
        op.task_id = taskId;
        return op;
    }

    private static String taskIdOf(ToolResult r) {
        Matcher m = TASK_ID.matcher(r.getContent());
        assertTrue(m.find(), r.getContent());
        return m.group(1);
    }

    private static Map<?, ?> indexItem(ToolResult r) {
        List<?> subtasks = (List<?>) r.metas().get(TaskTalent.META_SUBTASKS);
        assertNotNull(subtasks, r.getContent());
        assertEquals(1, subtasks.size());
        return (Map<?, ?>) subtasks.get(0);
    }

    /** 每次从磁盘重新打开：验证戳与 meta 随快照落盘 */
    private static AgentSession reopen(HarnessEngine engine, String taskId) {
        AgentSession sub = engine.getSessionProvider().getSession(taskId, "s1", false);
        assertNotNull(sub);
        return sub;
    }

    private static List<ChatMessage> subMessages(HarnessEngine engine, String taskId) {
        ReActTrace trace = TaskTalent.subtaskTraceOf(reopen(engine, taskId));
        assertNotNull(trace);
        return trace.getWorkingMemory().getMessages();
    }

    /** 子会话 meta 里记的最近一次调用：没戳的尾部归它 */
    private static Object latestCallOf(HarnessEngine engine, String taskId) {
        Map<?, ?> meta = (Map<?, ?>) reopen(engine, taskId).getContext().get(TaskTalent.META_SUBTASK);
        assertNotNull(meta);
        return meta.get("parentCallId");
    }

    private static void assertNoStamp(List<ChatMessage> messages) {
        for (ChatMessage m : messages) {
            assertFalse(m.hasMetadata(TaskTalent.META_PARENT_CALL_ID), m.toString());
        }
    }

    private static int indexOfUser(List<ChatMessage> messages, String content) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof UserMessage && content.equals(messages.get(i).getContent())) {
                return i;
            }
        }
        fail("找不到用户消息: " + content + " in " + messages);
        return -1;
    }

    @Test
    public void failedThenResumedCallsAreStampedAndIndexedSeparately() throws Exception {
        HarnessEngine engine = engine();
        ReActTrace parent = HarnessFixtures.trace(null, false);
        HarnessFixtures.bindTrace(engine.getSession("s1"), parent);
        TaskTalent talent = engine.getTaskTalent();

        // 第一次调用：失败
        String callA = acting(parent, "call-A");
        ToolResult first = talent.task(op(null, "fail-once"), ws.toString(), "s1", null);
        assertTrue(first.isError(), first.getContent());
        String id = taskIdOf(first);

        Map<?, ?> firstItem = indexItem(first);
        assertEquals(TaskTalent.STATUS_FAILURE, firstItem.get("status"), "索引项记本次调用的结果");
        assertNotNull(firstItem.get("failureKind"));
        // 最近一次调用的消息不打戳，靠 meta 的 parentCallId 归属
        assertNoStamp(subMessages(engine, id));
        assertEquals(callA, latestCallOf(engine, id));

        // 第二次调用：带 task_id 续跑，成功
        String callB = acting(parent, "call-B");
        ToolResult second = talent.task(op(id, "继续"), ws.toString(), "s1", null);
        assertFalse(second.isError(), second.getContent());
        assertEquals(id, taskIdOf(second));

        Map<?, ?> secondItem = indexItem(second);
        assertEquals(TaskTalent.STATUS_SUCCESS, secondItem.get("status"));
        assertFalse(secondItem.containsKey("failureKind"));
        // 第一次的结果不随续跑改变（它是独立的一份）
        assertEquals(TaskTalent.STATUS_FAILURE, firstItem.get("status"));

        // 子消息按调用切开：续跑前给 A 的消息补了戳；从续跑指令起是最近一次（B），不打戳、归 meta
        List<ChatMessage> messages = subMessages(engine, id);
        int resumeAt = indexOfUser(messages, "继续");
        assertTrue(resumeAt > 0, messages.toString());
        assertTrue(resumeAt < messages.size() - 1, "续跑后应有新的回复: " + messages);
        for (int i = 0; i < resumeAt; i++) {
            assertEquals(callA, messages.get(i).getMetadataAs(TaskTalent.META_PARENT_CALL_ID),
                    "第 " + i + " 条: " + messages.get(i));
        }
        assertNoStamp(messages.subList(resumeAt, messages.size()));
        assertEquals(callB, latestCallOf(engine, id));
    }

    @Test
    public void noCallIdMeansNoStamp() throws Exception {
        HarnessEngine engine = engine();
        ReActTrace parent = HarnessFixtures.trace(null, false);
        HarnessFixtures.bindTrace(engine.getSession("s1"), parent);

        // 没有 actingCallId（文本模式、测试直调）：续跑也不打戳，行为与以前一样
        ToolResult r = engine.getTaskTalent().task(op(null, "p1"), ws.toString(), "s1", null);
        assertFalse(r.isError(), r.getContent());
        String id = taskIdOf(r);
        ToolResult resumed = engine.getTaskTalent().task(op(id, "继续"), ws.toString(), "s1", null);
        assertFalse(resumed.isError(), resumed.getContent());

        assertNoStamp(subMessages(engine, id));
        assertNull(latestCallOf(engine, id));
        assertEquals(TaskTalent.STATUS_SUCCESS, indexItem(resumed).get("status"));
    }
}
