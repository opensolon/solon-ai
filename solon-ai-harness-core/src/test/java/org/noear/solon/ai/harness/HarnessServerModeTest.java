package org.noear.solon.ai.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.scope.InputScope;
import org.noear.solon.ai.mount.source.FileMountSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SERVER 预设契约：保守缺省、构建期快速失败、运行期全局配置锁、会话 cwd 边界、状态目录对模型不可见
 */
class HarnessServerModeTest {

    @TempDir
    Path tmp;

    private static ChatConfig model() {
        ChatConfig model = new ChatConfig();
        model.setName("fake");
        model.setModel("fake-model");
        model.setApiUrl("http://localhost:1/v1");
        model.setApiKey("test");
        return model;
    }

    private HarnessEngine.Builder server() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path state = Files.createDirectories(tmp.resolve("state"));
        return HarnessEngine.of(ws.toString(), ".solon/")
                .deployMode(DeployMode.SERVER)
                .sessionProvider((id, c) -> InMemoryAgentSession.of(id))
                .stateSource(FileMountSource.of(state))
                .modelAdd(model())
                .defaultModel("fake")
                .scopeResolver(InputScopeResolver.byPrincipal(p -> InputScope.builder(p).build()));
    }

    @Test
    void serverDefaultsAreConservative() throws Exception {
        HarnessEngine engine = server().build();
        assertEquals(DeployMode.SERVER, engine.getDeployMode());
        assertTrue(engine.getTerminalTalent().isSandboxEnabled());
        assertTrue(engine.getTerminalTalent().isSandboxSystemRestrict());
        assertFalse(engine.getTerminalTalent().isSandboxSelfBootstrap(), "进程级沙盒由宿主统一引导");
        assertFalse(engine.getTerminalTalent().isBashAsyncEnabled());
        assertTrue(engine.getTerminalTalent().getProtectedStatePaths().contains(".solon/sessions"));
    }

    @Test
    void deployModeIsComposedAtBuildRegardlessOfOrder() throws Exception {
        // 显式值写在 deployMode 之前：不被预设覆盖
        Path ws = Files.createDirectories(tmp.resolve("w-order"));
        Path state = Files.createDirectories(tmp.resolve("s-order"));
        HarnessEngine before = HarnessEngine.of(ws.toString(), ".solon/")
                .sandboxEnabled(false)
                .scopeRequired(false)
                .deployMode(DeployMode.SERVER)
                .sessionProvider((id, c) -> InMemoryAgentSession.of(id))
                .stateSource(FileMountSource.of(state))
                .modelAdd(model())
                .build();
        assertFalse(before.getTerminalTalent().isSandboxEnabled(), "显式值 > 预设值");
        assertFalse(before.isScopeRequired());
        assertTrue(before.getTerminalTalent().isSandboxSystemRestrict(), "未显式设置的仍取预设");

        // 后写的 deployMode 生效，前一个不留痕迹
        HarnessEngine last = HarnessEngine.of(tmp.resolve("w-last").toString(), ".solon/")
                .deployMode(DeployMode.SERVER)
                .deployMode(DeployMode.STANDALONE)
                .sessionProvider((id, c) -> InMemoryAgentSession.of(id))
                .build();
        assertEquals(DeployMode.STANDALONE, last.getDeployMode());
        assertTrue(last.getTerminalTalent().isSandboxSelfBootstrap());
        assertFalse(last.isScopeRequired());

        assertTrue(server().build().isScopeRequired(), "SERVER 缺省要求作用域");
    }

    @Test
    void standaloneKeepsExistingDefaults() throws Exception {
        HarnessEngine engine = HarnessEngine.of(tmp.toString(), ".solon/")
                .sessionProvider((id, c) -> InMemoryAgentSession.of(id))
                .build();
        assertEquals(DeployMode.STANDALONE, engine.getDeployMode());
        assertTrue(engine.getTerminalTalent().isSandboxSelfBootstrap());
        engine.allowTool("bash"); // 运行期修改仍允许
        // 状态目录保护在所有模式下都开启
        assertTrue(engine.getTerminalTalent().getProtectedStatePaths().contains(".solon/memory"));
    }

    @Test
    void serverBuildFailsFast() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("w2"));
        // 未显式配置 stateSource
        IllegalStateException e1 = assertThrows(IllegalStateException.class, () ->
                HarnessEngine.of(ws.toString(), ".solon/").deployMode(DeployMode.SERVER)
                        .sessionProvider((id, c) -> InMemoryAgentSession.of(id))
                        .modelAdd(model()).build());
        assertTrue(e1.getMessage().contains("stateSource"));

        // stateSource 在工作区内
        IllegalStateException e2 = assertThrows(IllegalStateException.class, () ->
                HarnessEngine.of(ws.toString(), ".solon/").deployMode(DeployMode.SERVER)
                        .sessionProvider((id, c) -> InMemoryAgentSession.of(id))
                        .stateSource(FileMountSource.of(ws.resolve(".solon")))
                        .modelAdd(model()).build());
        assertTrue(e2.getMessage().contains("outside the workspace"));

        // 没有模型
        assertThrows(IllegalStateException.class, () ->
                HarnessEngine.of(ws.toString(), ".solon/").deployMode(DeployMode.SERVER)
                        .sessionProvider((id, c) -> InMemoryAgentSession.of(id))
                        .stateSource(FileMountSource.of(tmp.resolve("s2"))).build());

        // defaultModel 不存在
        IllegalStateException e4 = assertThrows(IllegalStateException.class, () ->
                server().defaultModel("nope").build());
        assertTrue(e4.getMessage().contains("nope"));

        // 要求作用域（scopeRequired）却没有 scopeResolver：每轮输入都会被拒，构建期就报错
        IllegalStateException e5 = assertThrows(IllegalStateException.class, () ->
                HarnessEngine.of(ws.toString(), ".solon/").deployMode(DeployMode.SERVER)
                        .sessionProvider((id, c) -> InMemoryAgentSession.of(id))
                        .stateSource(FileMountSource.of(Files.createDirectories(tmp.resolve("s5"))))
                        .modelAdd(model()).defaultModel("fake").build());
        assertTrue(e5.getMessage().contains("scopeResolver"), e5.getMessage());

        // 显式关掉 scopeRequired 则不要求解析器
        HarnessEngine noScope = HarnessEngine.of(ws.toString(), ".solon/").deployMode(DeployMode.SERVER)
                .scopeRequired(false)
                .sessionProvider((id, c) -> InMemoryAgentSession.of(id))
                .stateSource(FileMountSource.of(Files.createDirectories(tmp.resolve("s6"))))
                .modelAdd(model()).defaultModel("fake").build();
        assertFalse(noScope.isScopeRequired());
    }

    @Test
    void serverLocksEngineWideMutations() throws Exception {
        HarnessEngine engine = server().build();
        assertThrows(IllegalStateException.class, () -> engine.allowTool("bash"));
        assertThrows(IllegalStateException.class, () -> engine.setSandboxEnabled(false));
        assertThrows(IllegalStateException.class, () -> engine.setHitlEnabled(true));
        assertThrows(IllegalStateException.class, () -> engine.addModel(model()));
        assertThrows(IllegalStateException.class, () -> engine.disallowToolReset(Collections.<String>emptyList()));

        // 调优类参数不锁
        engine.setMaxTurns(10);

        // 显式放开
        HarnessEngine mutable = server().runtimeMutable(true).build();
        mutable.allowTool("bash");
    }

    @Test
    void cwdPolicyGuardsSessionCwd() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("tenant-a"));
        HarnessEngine engine = server()
                .cwdPolicy(cwd -> tmp.resolve(cwd).toAbsolutePath().normalize().startsWith(root))
                .build();
        AgentSession s = InMemoryAgentSession.of("s1");

        engine.setSessionCwd(s, root.resolve("proj").toString());
        assertEquals(root.resolve("proj").toString(), engine.getSessionCwd(s));

        assertThrows(SecurityException.class, () -> engine.setSessionCwd(s, tmp.resolve("tenant-b").toString()));
        assertThrows(SecurityException.class, () -> engine.setSessionCwdIfAbsent(InMemoryAgentSession.of("s2"), "/etc"));
        engine.setSessionCwd(s, null); // 清除不受策略约束
    }
}
