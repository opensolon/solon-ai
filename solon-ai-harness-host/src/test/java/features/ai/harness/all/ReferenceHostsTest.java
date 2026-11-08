package features.ai.harness.all;

import features.ai.harness.all.ReferenceHosts.ImBotSample;
import features.ai.harness.all.ReferenceHosts.PushWebSample;
import features.ai.harness.all.ReferenceHosts.SseChatSample;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.extension.loop.LoopExtension;
import org.noear.solon.ai.harness.extension.queue.QueueExtension;
import org.noear.solon.ai.harness.extension.steer.SteerExtension;
import org.noear.solon.ai.harness.host.HostSignal;
import org.noear.solon.ai.harness.host.SubmitResult;
import org.noear.solon.ai.harness.input.InputOutcome.RejectReason;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 三个参考宿主都能用公开 API 跑通自己的主路径。
 */
class ReferenceHostsTest {

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
    void sseChatSample() throws Exception {
        SseChatSample a = new SseChatSample(this::engineOf);
        try {
            a.host.openSession("acme", "s1", "acme/u1");
            List<HostSignal> signals = a.chat("acme", "s1", "u1", "hello", 8).collectList().block(Duration.ofSeconds(30));
            assertEquals(HostSignal.Type.FINISHED, signals.get(signals.size() - 1).getType());
            assertEquals(1, a.billing.size(), "按轮计费拿到用量");
            assertEquals("acme", a.preparedTenants.get(0), "prepare 在 wrap 的租户上下文里执行");

            List<HostSignal> idor = a.chat("acme", "s1", "u2", "hello", 8).collectList().block(Duration.ofSeconds(5));
            assertEquals(RejectReason.SCOPE_UNAVAILABLE, idor.get(0).getRejectReason(), "别人的会话不能用");
        } finally {
            shutdownAll();
        }
    }

    @Test
    void imBotSample() throws Exception {
        ImBotSample b = new ImBotSample(this::engineOf);
        try {
            b.host.openSession("acme", "im-c1", "acme/u1");
            SubmitResult r = b.onMessage("acme", "c1", "u1", "hello");
            assertEquals(SubmitResult.Kind.STARTED, r.getKind());
            assertEquals("c1", b.replyRoute.get("im-c1"), "受理后记下回复路由");
            assertEquals(1, b.typing.size());
            r.getRun().await(Duration.ofSeconds(30));
            await(() -> b.outbox.stream().anyMatch(m -> m.startsWith("c1: ")), "终态发回 IM");

            b.onMessage("acme", "nope", "u1", "hello");
            assertTrue(b.outbox.contains("nope: rejected: session_not_found"));
        } finally {
            shutdownAll();
        }
    }

    @Test
    void pushWebSample() throws Exception {
        PushWebSample c = new PushWebSample(this::engineOf);
        try {
            c.host.openSession("acme", "w1", "acme/u1");
            SubmitResult r = c.send("acme", "w1", "u1", "hello");
            r.getRun().await(Duration.ofSeconds(30));
            await(() -> c.frames.get("w1").stream().anyMatch(f -> f.startsWith("done:")), "每轮以 done 收尾");
            List<String> frames = c.frames.get("w1");
            assertEquals("reset", frames.get(0), "open 时流门复位");
            assertEquals(1, frames.stream().filter(f -> f.startsWith("done:")).count(), "done 恰好一次");
        } finally {
            shutdownAll();
        }
    }
}
