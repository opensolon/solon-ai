package features.ai.harness.all;

import org.noear.solon.ai.harness.input.HarnessInput;

import org.noear.solon.ai.harness.HarnessResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentEvent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.DeployMode;
import org.noear.solon.ai.harness.agent.ToolName;
import org.noear.solon.ai.harness.extension.goal.GoalExtension;
import org.noear.solon.ai.harness.extension.loop.LoopExtension;
import org.noear.solon.ai.harness.extension.queue.QueueExtension;
import org.noear.solon.ai.harness.extension.steer.SteerExtension;
import org.noear.solon.ai.harness.HarnessEnginePool;
import org.noear.solon.ai.harness.host.HarnessHost;
import org.noear.solon.ai.harness.host.HostRun;
import org.noear.solon.ai.harness.host.HostSink;
import org.noear.solon.ai.harness.host.HostInput;
import org.noear.solon.ai.harness.host.RunOutcome;
import org.noear.solon.ai.harness.InterruptMode;
import org.noear.solon.ai.harness.host.Disposition;
import org.noear.solon.ai.harness.host.SubmitResult;
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
                    .deployMode(DeployMode.SERVER)
                    .stateSource(stateSrc)
                    .sessionProvider((id, create) -> create
                            ? store.computeIfAbsent(id, InMemoryAgentSession::of)
                            : store.get(id))
                    .modelAdd(model)
                    .defaultModel("fake")
                    .toolsAdd(ToolName.TOOL_ALL_PUBLIC)
                    // 作用域：认领本租户主体；解析不到时返回 null → scope_unavailable
                    .scopeResolver(InputScopeResolver.byPrincipal(
                            p -> p.startsWith(tenantId + "/") ? InputScope.builder(p).build() : null))
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

    /** §3.2 输出通道：只需要覆盖投递出口，其余缺省即可 */
    static final class MySink implements HostSink {
        final List<String> replies = new CopyOnWriteArrayList<>();
        final List<HostRun> finished = new CopyOnWriteArrayList<>();

        @Override
        public void reply(HostInput input, String text) {
            replies.add(text);
        }

        @Override
        public void event(HostRun run, AgentEvent event) {
        }

        @Override
        public void runFinished(HostRun run, RunOutcome outcome) {
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
                .build();
        try {
            assertEquals(1, host.warmup(Collections.singletonList("acme")));

            // 创建会话并写入会话主体（一次调用，不留「先建后写主体」的窗口）
            host.openSession("acme", "s1", "acme/u1");

            // §3.4 使用态
            SubmitResult r = host.submit("acme", "s1", HarnessInput.of("hello")
                    .origin(InputOrigin.of("web", "u1", null, null))
                    .principal("acme/u1"));
            assertEquals(Disposition.STARTED, r.getDisposition(), r.toString());
            RunOutcome outcome = r.getRun().await(Duration.ofSeconds(30));   // 按请求拿结果，不必轮询 sink
            assertNotNull(outcome);
            assertEquals(1, sink.finished.size(), "全局 sink 同样收到终态");

            // 用别人的主体提交：拒绝（sessionId 来自客户端，不校验就能用别人的会话）
            SubmitResult idor = host.submit("acme", "s1", HarnessInput.of("hello").principal("acme/u2"));
            assertEquals(403, idor.httpStatus());

            // 别的租户的主体：解析不到作用域 → SERVER 下拒绝受理
            host.openSession("acme", "s2", "other/u1");
            SubmitResult noScope = host.submit("acme", "s2", HarnessInput.of("hello"));
            assertEquals(Disposition.REJECTED, noScope.getDisposition());
            assertEquals(403, noScope.httpStatus());

            // 未开通的会话：只查不建
            assertEquals(404, host.submit("acme", "ghost", HarnessInput.of("hello")).httpStatus());

            // §五 管理态：鉴权在控制器层做（框架不参与），管理态只保证不变量
            assertTrue(host.admin("acme")
                    .call("s1", QueueExtension.class, (queue, s) -> HarnessResult.ok(queue.list(s))).isOk());
            assertTrue(host.admin("acme")
                    .call("s1", GoalExtension.class, (goal, s) -> HarnessResult.ok(goal.get("s1"))).isOk());

            // §六 停止
            assertTrue(host.admin("acme").interrupt("s1", InterruptMode.INTERRUPT_ALL).isOk());
        } finally {
            pool.close();
        }
    }
}
