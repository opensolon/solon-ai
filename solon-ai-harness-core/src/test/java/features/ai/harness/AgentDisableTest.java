package features.ai.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessExtension;
import org.noear.solon.ai.harness.agent.AgentDefinition;
import org.noear.solon.ai.harness.agent.AgentManager;
import org.noear.solon.ai.harness.agent.GenerateTalent;
import org.noear.solon.ai.mount.Mount;
import org.noear.solon.ai.mount.MountType;
import org.noear.solon.ai.mount.source.FileMountSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 子代理停用：定义里的 enabled: false 与按名字禁用（构建期名单 / 运行期 disallowAgent）同口径——
 * 列表不列、按名字解析不到，但名字仍算被占用。
 */
class AgentDisableTest {
    @TempDir
    Path workspace;

    private final Map<String, AgentSession> sessions = new ConcurrentHashMap<>();

    private HarnessEngine.Builder builder() {
        return HarnessEngine.of(workspace.toString(), workspace.resolve("home").toString())
                .sessionProvider((id, parentId, c) -> c ? sessions.computeIfAbsent(id, InMemoryAgentSession::of) : sessions.get(id));
    }

    private static boolean listed(HarnessEngine engine, String name) {
        return engine.getAgentManager().getAgents().stream().anyMatch(a -> name.equals(a.getName()));
    }

    private static void assertUnavailable(HarnessEngine engine, String name) {
        AgentManager am = engine.getAgentManager();
        assertNull(engine.resolveAgent(null, name), name + " 不应可解析");
        assertNull(am.getAgentOrNil(name));
        assertThrows(IllegalArgumentException.class, () -> am.getAgent(name));
        assertFalse(listed(engine, name), name + " 不应列出");
        assertTrue(am.hasAgent(name), name + " 名字仍算被占用");
    }

    @Test
    void enabledFalseInDefinitionMakesAgentUnavailable() throws Exception {
        Path dir = Files.createDirectories(workspace.resolve("agents"));
        writeAgent(dir, "custom", "enabled: false\n");
        // 同名覆盖内置代理并标 enabled: false（设置页“停用”写的就是这种覆盖文件）
        writeAgent(dir, "plan", "enabled: false\n");
        writeAgent(dir, "other", "");

        HarnessEngine engine = builder()
                .mountAdd(Mount.builder().alias("@agents").type(MountType.AGENTS).source(FileMountSource.of(dir)).build())
                .build();

        assertUnavailable(engine, "custom");
        assertUnavailable(engine, "plan");
        assertNotNull(engine.resolveAgent(null, "other"));
        assertTrue(listed(engine, "other"));

        // 运行时注册的同名定义不可用时，不回落到挂载目录
        engine.getAgentManager().addAgent(AgentDefinition.builder().name("other")
                .metadata(m -> m.setEnabled(false)).build());
        assertUnavailable(engine, "other");
    }

    @Test
    void disallowAgentAtBuildAndRuntime() {
        HarnessEngine engine = builder().disallowedAgentsAdd("bash").build();
        AgentManager am = engine.getAgentManager();

        assertUnavailable(engine, "bash");
        assertTrue(am.getDisallowedAgents().contains("bash"));

        assertNotNull(engine.resolveAgent(null, "plan"));
        am.disallowAgent("plan");
        assertUnavailable(engine, "plan");
        am.allowAgent("plan");
        assertNotNull(engine.resolveAgent(null, "plan"));
        assertTrue(listed(engine, "plan"));

        // 会话级子代理同名也被挡下
        AgentSession s1 = sessions.computeIfAbsent("s1", InMemoryAgentSession::of);
        String r = new GenerateTalent(engine).generate("helper", "d", "p", null, null, false, null, "s1", null).getContent();
        assertTrue(r.startsWith("[OK]"), r);
        assertNotNull(engine.resolveAgent(s1, "helper"));
        am.disallowAgent("helper");
        assertNull(engine.resolveAgent(s1, "helper"));
    }

    @Test
    void generateWorksWithoutGeneralAndRejectsDisallowedNames() {
        HarnessEngine engine = builder().disallowedAgentsAdd("general", "blocked").build();
        sessions.computeIfAbsent("s1", InMemoryAgentSession::of);
        GenerateTalent gen = new GenerateTalent(engine);

        String ok = gen.generate("helper", "d", "you are helper", null, null, false, null, "s1", null).getContent();
        assertTrue(ok.startsWith("[OK]"), ok);
        assertNotNull(engine.resolveAgent(sessions.get("s1"), "helper"));

        String blocked = gen.generate("blocked", "d", "p", null, null, false, null, "s1", null).getContent();
        assertTrue(blocked.startsWith("ERROR"), blocked);
        assertNull(engine.resolveAgent(sessions.get("s1"), "blocked"));
    }

    @Test
    void serverModeAllowsBuildTimeAndAttachButSealsAfterBuild() {
        HarnessEngine engine = builder()
                .runtimeMutable(false)
                .disallowedAgentsAdd("bash")
                .extensionAdd(new HarnessExtension() {
                    @Override
                    public void onAttach(HarnessEngine engine) {
                        engine.getAgentManager().disallowAgent("plan");
                    }
                })
                .build();

        assertUnavailable(engine, "bash");
        assertUnavailable(engine, "plan");
        assertThrows(IllegalStateException.class, () -> engine.getAgentManager().disallowAgent("explore"));
        assertThrows(IllegalStateException.class, () -> engine.getAgentManager().allowAgent("plan"));
        assertNotNull(engine.resolveAgent(null, "explore"));
    }

    private static void writeAgent(Path root, String name, String extraMeta) throws Exception {
        Files.write(root.resolve(name + ".md"),
                ("---\nname: " + name + "\ndescription: test\n" + extraMeta + "---\n" + name + " prompt")
                        .getBytes(StandardCharsets.UTF_8));
    }
}
