package features.ai.harness.all;

import org.noear.solon.ai.harness.HarnessResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentEvent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessProfile;
import org.noear.solon.ai.harness.agent.ToolName;
import org.noear.solon.ai.harness.extension.goal.GoalExtension;
import org.noear.solon.ai.harness.extension.loop.LoopExtension;
import org.noear.solon.ai.harness.extension.queue.QueueExtension;
import org.noear.solon.ai.harness.extension.steer.SteerExtension;
import org.noear.solon.ai.harness.host.HarnessEnginePool;
import org.noear.solon.ai.harness.host.HarnessHost;
import org.noear.solon.ai.harness.host.HostRun;
import org.noear.solon.ai.harness.host.HostSink;
import org.noear.solon.ai.harness.InterruptMode;
import org.noear.solon.ai.harness.host.Submitted;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.scope.InputScope;
import org.noear.solon.ai.harness.scope.InputScopeResolver;
import org.noear.solon.ai.mount.source.FileMountSource;
import org.noear.solon.ai.mount.source.MountSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QUICKSTART-SAAS.md 的写法必须在 SERVER 模式下原样可用（改动文档示例时同步此处）。
 */
class QuickstartSampleTest {

    @TempDir
    Path tmp;

    private final Map<String, Map<String, AgentSession>> sessions = new ConcurrentHashMap<>();

    /** §3.1 构建引擎（每个租户一个） */
    private HarnessEngine buildEngine(String tenantId) {
        try {
            Path ws = Files.createDirectories(tmp.resolve("ws").resolve(tenantId));
            MountSource stateSrc = FileMountSource.of(Files.createDirectories(tmp.resolve("state").resolve(tenantId)));
            Map<String, AgentSession> store = sessions.computeIfAbsent(tenantId, k -> new ConcurrentHashMap<>());

            ChatConfig model = new ChatConfig();
            model.setName("fake");
            model.setModel("fake-model");
            model.setApiUrl("http://localhost:1/v1");
            model.setApiKey("test");

            return HarnessEngine.of(ws.toString(), ".solon/")
                    .profile(HarnessProfile.SERVER)
                    .stateSource(stateSrc)
                    .sessionProvider((id, create) -> create
                            ? store.computeIfAbsent(id, InMemoryAgentSession::of)
                            : store.get(id))
                    .modelAdd(model)
                    .defaultModel("fake")
                    .toolsAdd(ToolName.TOOL_ALL_PUBLIC)
                    .scopeResolver(InputScopeResolver.byPrincipal(p -> InputScope.builder(p).build()))
                    // 会话插件：普通构建链，不要哪个删哪行，自己的扩展同样写法
                    .extensionAdd(QueueExtension.builder().maxSize(20).build())
                    .extensionAdd(new SteerExtension())
                    .extensionAdd(GoalExtension.builder().roundConcurrency(2).jobManager(null).build())
                    .extensionAdd(LoopExtension.builder().store("loop").maxTasks(200).quota(sid -> 20).jobManager(null).build())
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** §3.2 输出通道 */
    static final class MySink implements HostSink {
        final List<String> replies = new CopyOnWriteArrayList<>();
        final List<HostRun> finished = new CopyOnWriteArrayList<>();

        @Override
        public void reply(InputOrigin origin, String text) {
            replies.add(text);
        }

        @Override
        public void event(HostRun run, AgentEvent event) {
        }

        @Override
        public void runFinished(HostRun run, Throwable error) {
            finished.add(run);
        }
    }

    @Test
    void quickstartFlowRunsUnderServer() throws Exception {
        // §3.3 组装骨架
        HarnessEnginePool pool = HarnessEnginePool.builder(this::buildEngine)
                .idleTtl(Duration.ofMinutes(30))
                .maxEngines(500)
                .build();
        MySink sink = new MySink();
        HarnessHost host = HarnessHost.builder()
                .engines(pool::get)
                .sink(sink)
                .adminAuthorizer((tenant, principal, sid, op) -> "acme/admin".equals(principal))
                .build();
        try {
            assertEquals(1, host.warmup(Collections.singletonList("acme")));

            // 创建会话并写入会话主体
            HarnessEngine engine = host.engine("acme");
            engine.setSessionPrincipal(engine.getSession("s1"), "acme/u1");

            // §3.4 使用态
            Submitted r = host.submit("acme", "s1", "hello", InputOrigin.of("web", "u1", null, null));
            assertEquals(Submitted.Kind.STARTED, r.getKind(), r.toString());
            long deadline = System.currentTimeMillis() + 30_000;
            while (sink.finished.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(1, sink.finished.size());

            // 没有主体的会话：SERVER 下 scopeRequired，拒绝受理
            engine.getSession("s2");
            Submitted noScope = host.submit("acme", "s2", "hello", null);
            assertEquals(Submitted.Kind.REJECTED, noScope.getKind());
            assertEquals(403, noScope.httpStatus());

            // §五 管理态
            assertEquals(HarnessResult.Code.FORBIDDEN, host.admin("acme", "acme/u1")
                    .call("s1", "goal.get", GoalExtension.class, (goal, s) -> HarnessResult.ok(goal.get("s1"))).getCode());
            assertTrue(host.admin("acme", "acme/admin")
                    .call("s1", "goal.get", GoalExtension.class, (goal, s) -> HarnessResult.ok(goal.get("s1"))).isOk());

            // §六 停止
            assertTrue(host.admin("acme", "acme/admin").interrupt("s1", InterruptMode.INTERRUPT).isOk());
        } finally {
            pool.close();
        }
    }
}
