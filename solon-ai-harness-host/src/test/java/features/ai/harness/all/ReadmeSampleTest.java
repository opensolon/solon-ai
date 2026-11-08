package features.ai.harness.all;

import org.noear.solon.ai.harness.input.BusyPolicy;

import org.noear.solon.ai.harness.input.HarnessInput;

import features.ai.harness.all.ReadmeSamples.AskController;
import features.ai.harness.all.ReadmeSamples.HostBootstrap;
import features.ai.harness.all.ReadmeSamples.ImBot;
import features.ai.harness.all.ReadmeSamples.ProgramAwareSink;
import features.ai.harness.all.ReadmeSamples.SseController;
import features.ai.harness.all.ReadmeSamples.WebPushSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessResult;
import org.noear.solon.ai.harness.extension.goal.GoalExtension;
import org.noear.solon.ai.harness.extension.loop.LoopExtension;
import org.noear.solon.ai.harness.extension.queue.QueueExtension;
import org.noear.solon.ai.harness.extension.queue.QueueItem;
import org.noear.solon.ai.harness.extension.steer.SteerExtension;
import org.noear.solon.ai.harness.host.HarnessHost;
import org.noear.solon.ai.harness.host.HostSignal;
import org.noear.solon.ai.harness.host.RunOutcome;
import org.noear.solon.ai.harness.host.SubmitResult;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.core.handle.Result;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * README.md 示例（{@link ReadmeSamples}）的主干流程可以原样跑通。
 *
 * <p>模型指向一个连不上的地址：ReAct 把模型错误收为「异常结束」，每轮以 COMPLETED + {@code isAbnormal()} 收尾，
 * 正好用来验证「终态恰好一次」与各入口对异常结束的处理。</p>
 */
class ReadmeSampleTest {

    @TempDir
    Path tmp;

    private final Map<String, Map<String, AgentSession>> stores = new ConcurrentHashMap<>();
    private final Map<String, HarnessEngine> engines = new ConcurrentHashMap<>();

    private HarnessEngine engineOf(String tenant) {
        return engines.computeIfAbsent(tenant, t -> {
            try {
                ChatConfig model = new ChatConfig();
                model.setName("fake");
                model.setModel("fake-model");
                model.setApiUrl("http://localhost:1/v1");
                model.setApiKey("test");
                Map<String, AgentSession> store = stores.computeIfAbsent(t, k -> new ConcurrentHashMap<>());
                return HarnessEngine.of(Files.createDirectories(tmp.resolve("ws").resolve(t)).toString(),
                                tmp.resolve("home").resolve(t).toString())
                        .sessionProvider((id, c) -> c ? store.computeIfAbsent(id, InMemoryAgentSession::of) : store.get(id))
                        .modelAdd(model)
                        .defaultModel("fake")
                        .extensionAdd(QueueExtension.builder().build())
                        .extensionAdd(new SteerExtension())
                        .extensionAdd(GoalExtension.builder().jobManager(null).build())
                        .extensionAdd(LoopExtension.builder().jobManager(null).build())
                        .build();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private void shutdownAll() {
        engines.values().forEach(HarnessEngine::shutdown);
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
    void askAwaitsOutcome() throws Exception {
        HarnessHost host = ReadmeSamples.minimalHost(engineOf("acme"));
        try {
            host.openSession("acme", "s1", "acme/u1");
            SubmitResult r = host.submit("acme", "s1", HarnessInput.of("hello").principal("acme/u1")
                    .whenBusy(BusyPolicy.REJECT));
            assertEquals(SubmitResult.Kind.STARTED, r.getKind(), r.toString());
            Result<String> answer = AskController.awaitAnswer(r.getRun());
            assertEquals(500, answer.getCode(), "模型连不上：异常结束 → 500");

            SubmitResult idor = host.submit("acme", "s1", HarnessInput.of("hello").principal("acme/u2"));
            assertEquals(403, idor.httpStatus(), "别人的会话不能用");
            assertEquals(404, host.submit("acme", "ghost", HarnessInput.of("hello")).httpStatus(), "只查不建");
        } finally {
            shutdownAll();
        }
    }

    @Test
    void sseFramesEndWithDone() throws Exception {
        HarnessHost host = ReadmeSamples.minimalHost(engineOf("acme"));
        try {
            host.openSession("acme", "s1", "acme/u1");
            List<HostSignal> signals = host.stream("acme", "s1", HarnessInput.of("hello").principal("acme/u1"))
                    .collectList().block(Duration.ofSeconds(30));
            assertNotNull(signals);
            HostSignal last = signals.get(signals.size() - 1);
            assertEquals(HostSignal.Type.FINISHED, last.getType());
            Map<String, Object> frame = SseController.toFrame(last);
            assertEquals("done", frame.get("type"));
            assertEquals("COMPLETED", frame.get("end"));
            assertTrue(last.getOutcome().isAbnormal(), "模型错误 = 异常结束");
        } finally {
            shutdownAll();
        }
    }

    @Test
    void imBotRepliesInChat() throws Exception {
        List<String> outbox = new CopyOnWriteArrayList<>();
        ImBot bot = new ImBot(this::engineOf, (chatId, text) -> outbox.add(chatId + ": " + text));
        try {
            SubmitResult r = bot.onMessage("acme", "c1", "u1", "m1", "hello");
            assertEquals(SubmitResult.Kind.STARTED, r.getKind(), r.toString());
            r.getRun().await(Duration.ofSeconds(30));
            await(() -> outbox.contains("c1: The task could not be finished."), "异常结束时回安全文案，不发半成品");

            SubmitResult again = bot.onMessage("acme", "c1", "u1", "m2", "hello again");
            assertNotEquals(SubmitResult.Kind.REJECTED, again.getKind(), "openSession 幂等：同一主体可重复调用");
            if (again.getRun() != null) {
                again.getRun().await(Duration.ofSeconds(30));
            }

            SubmitResult expired = bot.onMessage("acme", "c1", "u1", "m3", "approve call-x");
            assertEquals(SubmitResult.Kind.HITL, expired.getKind());
            await(() -> outbox.contains("c1: This approval has expired."), "过期的审批有回执");
        } finally {
            shutdownAll();
        }
    }

    @Test
    void webPushRunSinkResetsOnFirstOutputAndEndsOnce() throws Exception {
        Map<String, List<Map<String, Object>>> frames = new ConcurrentHashMap<>();
        HarnessHost host = HarnessHost.builder()
                .engines(this::engineOf)
                .sink(new WebPushSink((sid, f) -> frames.computeIfAbsent(sid, k -> new CopyOnWriteArrayList<>()).add(f)))
                .build();
        try {
            host.openSession("acme", "w1", "acme/u1");
            SubmitResult r = host.submit("acme", "w1", HarnessInput.of("hello")
                    .origin(InputOrigin.of("web", "u1", "w1", null)).principal("acme/u1"));
            RunOutcome o = r.getRun().await(Duration.ofSeconds(30));
            assertTrue(o.isCompleted() && o.isAbnormal(), o.toString());
            await(() -> frames.get("w1").stream().anyMatch(f -> "done".equals(f.get("type"))), "以 done 收尾");

            List<Map<String, Object>> list = frames.get("w1");
            assertEquals("reset", list.get(0).get("type"), "第一次输出时复位");
            assertEquals(1, list.stream().filter(f -> "done".equals(f.get("type"))).count(), "done 恰好一次");
            assertTrue(list.stream().noneMatch(f -> "stopped".equals(f.get("type"))), "没人点停止：不发 stopped");
        } finally {
            shutdownAll();
        }
    }

    @Test
    void adminCallsAndBootstrap() throws Exception {
        List<String> notices = new CopyOnWriteArrayList<>();
        HostBootstrap boot = new HostBootstrap(this::engineOf, new ProgramAwareSink(notices));
        try {
            assertEquals(1, boot.start(Collections.singletonList("acme")));
            HarnessHost host = boot.host;
            host.openSession("acme", "s1", "acme/u1");

            HarnessResult<List<QueueItem>> items = host.admin("acme")
                    .call("s1", QueueExtension.class, (queue, s) -> HarnessResult.ok(queue.list(s)));
            assertTrue(items.isOk());
            assertTrue(items.getValue().isEmpty());

            HarnessResult<Integer> missing = host.admin("acme")
                    .call("ghost", QueueExtension.class, (queue, s) -> queue.enqueue(s, "x", InputOrigin.channel("console")));
            assertEquals(HarnessResult.Code.NOT_FOUND, missing.getCode());
            assertEquals(404, missing.httpStatus());

            HarnessResult<Boolean> stop = host.interrupt("acme", "s1");
            assertTrue(stop.isOk());
            assertFalse(stop.getValue(), "没有在跑的轮次");

            assertTrue(host.closeSession("acme", "s1"), "空闲会话可以关闭");
            assertTrue(host.closeSession("acme", "never-opened"), "不存在的会话视为已关闭");
        } finally {
            boot.stop();
            shutdownAll();
        }
    }
}
