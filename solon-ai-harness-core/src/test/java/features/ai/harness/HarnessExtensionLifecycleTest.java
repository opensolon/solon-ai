package features.ai.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessExtension;
import org.noear.solon.ai.agent.react.ReActAgent;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 扩展生命周期验证：
 * 1. 构造期 initialize 恰好回调一次，且此刻引擎已装配完毕（可调用任意引擎方法）
 * 2. addExtension 动态添加时补触发 initialize
 * 3. isEnabled=false 的扩展不触发 initialize
 * 4. initialize 抛异常时引擎构建 fail-fast
 * 5. Builder.mountAdd 对内置挂载的保护（含不带 @ 前缀的同名别名）
 *
 * 注：不在此处验证 configure（构建代理需要 chatModel，见 AgentFactoryCommandSessionTest）。
 */
class HarnessExtensionLifecycleTest {
    @TempDir Path workspace;

    static class RecordingExtension implements HarnessExtension {
        final List<String> events = new CopyOnWriteArrayList<>();
        boolean failOnInitialize;

        @Override
        public void onAttach(HarnessEngine engine) {
            events.add("initialize");
            if (failOnInitialize) {
                throw new IllegalStateException("boom");
            }
            // 此刻引擎必须已装配完毕：这里调用最重的几个引擎方法做验证
            assertNotNull(engine.getTerminalTalent());
            assertNotNull(engine.getSkillCatalog());
            assertNotNull(engine.getAgentCatalog());
            assertNotNull(engine.getAgentManager());
            assertNotNull(engine.getMcpGatewayTalent());
            assertNotNull(engine.getLspTalent());
            assertNotNull(engine.getMount("@harness-agents"));
        }

        @Override
        public void onAgentBuild(HarnessEngine engine, String agentName, ReActAgent.Builder agentBuilder) {
            events.add("configure:" + agentName);
        }

        @Override
        public void onDetach(HarnessEngine engine) {
            events.add("detach");
        }
    }

    private HarnessEngine.Builder builder() {
        return HarnessEngine.of(workspace.toString(), workspace.resolve("home").toString())
                .sessionProvider(InMemoryAgentSession::of);
    }

    @Test
    void initializeCalledOnceAfterFullAssembly() {
        RecordingExtension ext = new RecordingExtension();
        HarnessEngine engine = builder().extensionAdd(ext).build();

        assertEquals(java.util.Collections.singletonList("initialize"), ext.events);
    }

    @Test
    void addExtensionTriggersInitializeLate() {
        RecordingExtension ext = new RecordingExtension();
        HarnessEngine engine = builder().build();
        assertTrue(ext.events.isEmpty());

        engine.addExtension(ext);
        assertEquals(java.util.Collections.singletonList("initialize"), ext.events);
    }

    @Test
    void disabledExtensionStillInitializedButConfigureIsNotGated() {
        // initialize 不再看启用状态（引擎级装配必须完成，否则命令未注册、运行期改回 true 也挂不上）；
        // configure 同样无条件回调——停用由扩展自己传播给子组件。
        RecordingExtension ext = new RecordingExtension() {
            @Override
            public boolean isEnabled() {
                return false;
            }
        };
        HarnessEngine engine = builder().extensionAdd(ext).build();

        assertEquals(java.util.Collections.singletonList("initialize"), ext.events);
    }

    @Test
    void initializeFailureFailsEngineConstruction() {
        RecordingExtension ext = new RecordingExtension();
        ext.failOnInitialize = true;

        assertThrows(IllegalStateException.class, () -> builder().extensionAdd(ext).build());
        // 失败发生在 initialize，尚未触发任何 configure
        assertEquals(java.util.Collections.singletonList("initialize"), ext.events);
    }

    @Test
    void detachCalledOnRemoveAndShutdown() {
        RecordingExtension ext = new RecordingExtension();
        HarnessEngine engine = builder().extensionAdd(ext).build();
        ext.events.clear();

        // removeExtension：先移除再回调（此刻已收不到任何输入/请求钩子）
        engine.removeExtension(ext);
        assertEquals(java.util.Collections.singletonList("detach"), ext.events);
        assertTrue(engine.getExtensions().isEmpty());

        // shutdown：遍历回调（与 isEnabled 无关）
        RecordingExtension ext2 = new RecordingExtension();
        HarnessEngine engine2 = builder().extensionAdd(ext2).build();
        ext2.events.clear();
        engine2.shutdown();
        assertEquals(java.util.Collections.singletonList("detach"), ext2.events);
    }

    @Test
    void builtinMountProtectionStillHolds() {
        // Builder.mountAdd 使用 normalizeMountAlias：不带 @ 前缀的同名内置挂载也要被拦截
        assertThrows(IllegalArgumentException.class, () ->
                builder().mountAdd(org.noear.solon.ai.talents.mount.Mount.builder()
                        .alias("harness-agents")
                        .build()));
    }
}
