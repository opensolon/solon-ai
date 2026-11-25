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
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessExtension;
import org.noear.solon.ai.harness.agent.TaskTalent;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * multitask 被取消时不丢已有成果：已完成的结果照常返回，已启动未结束的带 task_id（可接着做）。
 *
 * <p>本地假模型：提示词含 slow 的第一次请求卡住（模拟子代理卡在长调用里、不再产出事件），其余立即答 done。</p>
 */
public class TaskTalentMultitaskCancelTest {
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

    /** 父轨迹挂一个可随时取消的流 sink（模拟用户按停止） */
    private static ReActTrace traceWithSink(FluxSink<?> sink) throws Throwable {
        ReActTrace trace = HarnessFixtures.trace(null, false);
        HarnessFixtures.invoke(trace.getOptions(), "setStreamSink", new Class<?>[]{FluxSink.class}, sink);
        return trace;
    }

    @Test
    @SuppressWarnings("unchecked")
    public void cancelKeepsFinishedResultsAndTaskIdsOfStartedTasks() throws Throwable {
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

        AtomicReference<FluxSink<?>> sinkRef = new AtomicReference<>();
        Disposable parentStream = Flux.create(sinkRef::set).subscribe();
        HarnessFixtures.bindTrace(engine.getSession("s1"), traceWithSink(sinkRef.get()));

        List<TaskTalent.MultiTaskOp> ops = new ArrayList<>();
        ops.add(op(1, "slow-task"));
        ops.add(op(2, "fast-task"));

        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<ToolResult> pending = caller.submit(() -> talent.multitask(ops, ws.toString(), "s1", null));

            // 第 1 条卡在模型请求里，第 2 条跑完后再取消（aggregate 此时正等第 1 条）
            assertTrue(slowArrived.await(10, TimeUnit.SECONDS), "第 1 条应已发出模型请求");
            assertTrue(waitUntil(() -> sessions.values().stream().anyMatch(s -> {
                Object meta = s.getContext().get(TaskTalent.META_SUBTASK);
                return meta instanceof Map && TaskTalent.STATUS_SUCCESS.equals(((Map<?, ?>) meta).get("status"));
            })), "第 2 条应已完成");
            parentStream.dispose();

            ToolResult r = pending.get(10, TimeUnit.SECONDS);
            String out = r.getContent();
            assertTrue(r.isError(), "被取消的 multitask 整体仍标失败: " + out);

            // 已完成的结果不随取消丢掉（旧实现只给一句「未完成」，连外壳都没有）
            assertTrue(out.contains("<index>1</index>") && out.contains("<index>2</index>"), out);
            String second = out.substring(out.indexOf("<index>2</index>"));
            assertTrue(second.contains("<result_status>success</result_status>") && second.contains("done"), out);

            // 卡住的那条带 task_id，可接着做
            String first = out.substring(out.indexOf("<index>1</index>"), out.indexOf("<index>2</index>"));
            Matcher m = TASK_ID.matcher(first);
            assertTrue(m.find(), out);
            assertTrue(first.contains("<failure_kind>cancelled</failure_kind>"), out);
            String id = m.group(1);

            // 放行后原线程退出、释放运行锁，再带 task_id 接着做
            releaseSlow.countDown();
            Map<String, String> running = (Map<String, String>) HarnessFixtures.get(talent, "runningTasks");
            assertTrue(waitUntil(() -> running.containsKey(id) == false), "原子任务线程应已退出");

            HarnessFixtures.bindTrace(engine.getSession("s1"), HarnessFixtures.trace(null, false));
            TaskTalent.SingleTaskOp again = new TaskTalent.SingleTaskOp();
            again.agent_name = "explore";
            again.prompt = "继续";
            again.task_id = id;
            ToolResult resumed = talent.task(again, ws.toString(), "s1", null);
            assertFalse(resumed.isError(), resumed.getContent());
            assertTrue(resumed.getContent().contains("<task_id>" + id + "</task_id>"), resumed.getContent());
        } finally {
            caller.shutdownNow();
        }
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
