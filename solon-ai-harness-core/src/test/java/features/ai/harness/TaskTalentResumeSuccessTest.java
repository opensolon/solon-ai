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
import org.noear.solon.ai.agent.react.LlmErrorEvent;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
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
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 续跑成功路径（本地假模型按脚本应答）：模型错误类型被清掉、回合用尽后再给一份预算、父 trace 只加增量、完成后追问。
 */
public class TaskTalentResumeSuccessTest {
    private static final Pattern TASK_ID = Pattern.compile("<task_id>(task-[0-9a-f]{32})</task_id>");

    @TempDir
    Path ws;

    private HttpServer server;
    /** 脚本：每次模型请求取一条（"429" / "tool" / "answer"） */
    private final Deque<String> script = new ConcurrentLinkedDeque<>();

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

    private void handle(HttpExchange ex) throws IOException {
        boolean stream = readBody(ex.getRequestBody()).replace(" ", "").contains("\"stream\":true");
        String step = script.poll();
        if (step == null || "429".equals(step)) {
            send(ex, 429, "application/json", "{\"error\":{\"message\":\"rate limited\",\"type\":\"rate_limit\"}}");
            return;
        }

        String message = "tool".equals(step)
                ? "\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"no_such_tool\",\"arguments\":\"{}\"}}]"
                : "\"role\":\"assistant\",\"content\":\"done\"";
        String finish = "tool".equals(step) ? "tool_calls" : "stop";
        String usage = "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}";

        if (stream) {
            String body = "data: {\"id\":\"x\",\"object\":\"chat.completion.chunk\",\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{" + message + "},\"finish_reason\":null}]}\n\n"
                    + "data: {\"id\":\"x\",\"object\":\"chat.completion.chunk\",\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"" + finish + "\"}]," + usage + "}\n\n"
                    + "data: [DONE]\n\n";
            send(ex, 200, "text/event-stream; charset=utf-8", body);
        } else {
            send(ex, 200, "application/json", "{\"id\":\"x\",\"object\":\"chat.completion\",\"model\":\"m\",\"choices\":[{\"index\":0,\"message\":{"
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

    private static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * @param firstMaxTurns 首次执行的回合上限（0 = 不改）；续跑由 TaskTalent 重设，覆盖这里的值
     */
    private HarnessEngine engine(int firstMaxTurns) throws Exception {
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
                        if (isDelegated && firstMaxTurns > 0) {
                            o.maxTurns(firstMaxTurns);
                        }
                    }
                })
                .build();
        HarnessFixtures.bindTrace(engine.getSession("s1"), HarnessFixtures.trace(null, false));
        return engine;
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> meta(AgentSession sub) {
        return (Map<String, Object>) sub.getContext().get(TaskTalent.META_SUBTASK);
    }

    @Test
    public void resumeAfterLlmErrorSucceedsAndClearsErrorType() throws Exception {
        HarnessEngine engine = engine(0);
        TaskTalent talent = engine.getTaskTalent();

        script.add("429");
        ToolResult failed = talent.task(op(null, "p1"), ws.toString(), "s1", null);
        assertTrue(failed.isError(), failed.getContent());
        String id = taskIdOf(failed);

        script.add("answer");
        ToolResult ok = talent.task(op(id, "继续"), ws.toString(), "s1", null);

        assertFalse(ok.isError(), ok.getContent());
        assertTrue(ok.getContent().contains("<result_status>success</result_status>"), ok.getContent());
        assertTrue(ok.getContent().contains("done"), ok.getContent());
        assertEquals(id, taskIdOf(ok), "成功结果同样给 task_id（可追问）");

        AgentSession sub = engine.getSessionProvider().getSession(id, false);
        assertEquals(TaskTalent.STATUS_SUCCESS, meta(sub).get("status"));
        assertNull(meta(sub).get("failureKind"));
        ReActTrace trace = TaskTalent.subtaskTraceOf(sub);
        assertNotNull(trace);
        assertNull(trace.getExtras().get(LlmErrorEvent.TRACE_ERROR_TYPE_KEY), "上次的模型错误类型必须清掉");
        assertNotNull(ok.metas().get(TaskTalent.META_SUBTASKS), "跑起来的续跑要带子任务索引");
    }

    @Test
    public void resumeAfterMaxTurnsGetsFreshBudgetAndCountsOnlyDelta() throws Exception {
        HarnessEngine engine = engine(1);
        TaskTalent talent = engine.getTaskTalent();
        ReActTrace parent = ReActTrace.getCurrent(engine.getSession("s1").getContext());

        // 第 1 回合调工具，第 2 回合超出上限 1 → 回合用尽
        script.add("tool");
        ToolResult failed = talent.task(op(null, "p1"), ws.toString(), "s1", null);
        assertTrue(failed.isError(), failed.getContent());
        assertTrue(failed.getContent().contains("<failure_kind>max_turns</failure_kind>"), failed.getContent());
        String id = taskIdOf(failed);
        long afterFirst = parent.getMetrics().getTotalTokens();
        assertEquals(15, afterFirst, "首次执行一回合用量");

        // 续跑：已用回合数之上再给一份预算，否则第一回合就会再次用尽
        script.add("answer");
        ToolResult ok = talent.task(op(id, "继续"), ws.toString(), "s1", null);
        assertFalse(ok.isError(), ok.getContent());

        ReActTrace sub = TaskTalent.subtaskTraceOf(engine.getSessionProvider().getSession(id, false));
        assertEquals(30, sub.getMetrics().getTotalTokens(), "子 trace 指标累计");
        assertEquals(30, parent.getMetrics().getTotalTokens(), "父 trace 只加本次增量");
    }

    /** 完成后追问：保留上次的答案，追问追加其后，子代理带着完整上下文回答；父 trace 只加本次增量 */
    @Test
    public void followUpAfterSuccessKeepsTheAnswer() throws Exception {
        HarnessEngine engine = engine(0);
        TaskTalent talent = engine.getTaskTalent();
        ReActTrace parent = ReActTrace.getCurrent(engine.getSession("s1").getContext());

        script.add("answer");
        ToolResult first = talent.task(op(null, "p1"), ws.toString(), "s1", null);
        assertFalse(first.isError(), first.getContent());
        String id = taskIdOf(first);

        script.add("answer");
        ToolResult followUp = talent.task(op(id, "追问"), ws.toString(), "s1", null);
        assertFalse(followUp.isError(), followUp.getContent());
        assertEquals(id, taskIdOf(followUp));

        AgentSession sub = engine.getSessionProvider().getSession(id, false);
        List<ChatMessage> messages = new ArrayList<>(sub.getMessages());
        assertEquals(4, messages.size(), messages.toString());
        assertEquals("p1", messages.get(0).getContent());
        assertEquals("done", messages.get(1).getContent(), "上次的答案不撤");
        assertEquals("追问", messages.get(2).getContent());
        assertTrue(messages.get(3) instanceof AssistantMessage);
        assertEquals(30, parent.getMetrics().getTotalTokens(), "父 trace 只加本次增量");
    }

    /** 运行期才被拒的续跑（仍在执行）：预解析已通过，但子代理没跑起来，不进索引 */
    @Test
    @SuppressWarnings("unchecked")
    public void rejectedResumeIsNotIndexed() throws Throwable {
        HarnessEngine engine = engine(0);
        TaskTalent talent = engine.getTaskTalent();

        script.add("429");
        String id = taskIdOf(talent.task(op(null, "p1"), ws.toString(), "s1", null));

        Set<String> running = (Set<String>) HarnessFixtures.get(talent, "runningTasks");
        running.add(id);
        try {
            ToolResult rejected = talent.task(op(id, "继续"), ws.toString(), "s1", null);
            assertTrue(rejected.isError(), rejected.getContent());
            assertNull(rejected.metas().get(TaskTalent.META_SUBTASKS), "没跑起来的任务不进索引，免得前端隐藏拒绝信息");
        } finally {
            running.remove(id);
        }
    }
}
