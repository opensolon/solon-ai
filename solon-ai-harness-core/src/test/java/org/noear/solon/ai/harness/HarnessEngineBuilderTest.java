package org.noear.solon.ai.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.intercept.ContextCompressionInterceptor;
import org.noear.solon.ai.agent.react.intercept.HITLInterceptor;
import org.noear.solon.ai.agent.react.intercept.StopLoopInterceptor;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.CacheControl;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.agent.ToolName;
import org.noear.solon.ai.harness.permission.PermissionBehavior;
import org.noear.solon.ai.harness.permission.PermissionRule;
import org.noear.solon.ai.mcp.client.McpServerParameters;
import org.noear.solon.ai.mount.source.FileMountSource;
import org.noear.solon.ai.mount.Mount;
import org.noear.solon.ai.mount.MountType;

import java.net.Proxy;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HarnessEngine.Builder 全量链式配置：每个 builder 方法都必须把值真正写进 options，
 * 且 build() 的校验与扩展初始化时序符合契约。
 */
class HarnessEngineBuilderTest {

    @TempDir
    Path workspace;

    private HarnessEngine.Builder builder() {
        return HarnessEngine.of(workspace.toString(), null)
                .sessionProvider((id, c) -> InMemoryAgentSession.of(id));
    }

    private static ChatConfig model(String name) {
        ChatConfig c = new ChatConfig();
        c.setName(name);
        c.setModel("m-" + name);
        c.setApiUrl("https://example.invalid/v1");
        c.setApiKey("k");
        return c;
    }

    @Test
    void buildRequiresSessionProvider() {
        // 唯一硬校验：缺 sessionProvider 立即失败（缺模型要到首次 handle 才暴露）
        assertThrows(NullPointerException.class,
                () -> HarnessEngine.of(workspace.toString(), null).build());
    }

    @Test
    void simplePropertiesReachEngine() {
        HarnessEngine e = builder()
                .systemPrompt("sys")
                .userAgent("ua")
                .maxTurns(7)
                .autoRethink(false)
                .sessionWindowSize(3)
                .compressionThreshold(20, 0.4d)
                .compressionDefaultContextLength(9_000L)
                .cacheControl(new CacheControl())
                .memoryEnabled(false)
                .memoryRelevanceCount(2)
                .memoryPriorityCount(1)
                .memorySummaryLength(10)
                .sandboxEnabled(false)
                .sandboxAllowUserHome(false)
                .sandboxSystemRestrict(true)
                .hitlEnabled(true)
                .subagentEnabled(false)
                .bashAsyncEnabled(true)
                .apiRetries(1)
                .mcpRetries(2)
                .modelRetries(3)
                .subagentDefaultConcurrency(4)
                .commandPrefix("::")
                .agentPrefix("#")
                .build();

        assertEquals("sys", e.getSettings().getSystemPrompt());
        assertEquals("ua", e.getSettings().getUserAgent());
        assertEquals(7, e.getSettings().getMaxTurns());
        assertFalse(e.getSettings().isAutoRethink());
        assertEquals(3, e.getSettings().getSessionWindowSize());
        assertEquals(20, e.getSettings().getCompressionMaxMessages());
        assertEquals(0.4d, e.getSettings().getCompressionMaxContextRatio(), 1e-9);
        assertEquals(9_000L, e.getSettings().getCompressionDefaultContextLength());
        assertNotNull(e.getSettings().getCacheControl());
        assertFalse(e.getSettings().isMemoryEnabled());
        assertFalse(e.getSettings().isSandboxEnabled());
        assertTrue(e.getSettings().isSandboxSystemRestrict());
        assertTrue(e.getSettings().isHitlEnabled());
        assertFalse(e.getSettings().isSubagentEnabled());
        assertTrue(e.getSettings().isBashAsyncEnabled());
        assertEquals(1, e.getSettings().getApiRetries());
        assertEquals(2, e.getSettings().getMcpRetries());
        assertEquals(3, e.getSettings().getModelRetries());
        assertEquals(4, e.getSettings().getSubagentDefaultConcurrency());
        // Builder 只写 options，构造函数必须把它推送到执行者
        assertEquals(4, e.getTalents().getTask().getDefaultConcurrency());
        assertEquals("::", e.getSettings().getCommandPrefix());
        assertEquals("#", e.getSettings().getAgentPrefix());
    }

    @Test
    void serviceInterceptorsAndProvidersAreWired() {
        HITLInterceptor hitl = new HITLInterceptor();
        ContextCompressionInterceptor compress = new ContextCompressionInterceptor();
        StopLoopInterceptor stop = new StopLoopInterceptor();

        HarnessEngine e = builder()
                .hitlInterceptor(hitl)
                .compressionInterceptor(compress)
                .stopLoopInterceptor(stop)
                .memoryProvider(cwd -> null)
                .build();

        assertSame(hitl, e.getHitlInterceptor());
        assertSame(compress, e.getCompressionInterceptor());
        assertSame(stop, e.getStopLoopInterceptor());
        assertNotNull(e.getMemoryProvider());
    }

    @Test
    void httpProxyBothOverloads() {
        HarnessEngine e = builder().httpProxy("127.0.0.1", 8888).build();
        assertNotNull(e.getSettings().getHttpProxy());

        Proxy direct = Proxy.NO_PROXY;
        HarnessEngine e2 = builder().httpProxy(direct).build();
        assertSame(direct, e2.getSettings().getHttpProxy());

        AtomicBoolean hit = new AtomicBoolean();
        HarnessEngine e3 = builder()
                .httpCustomizeAdd(u -> hit.set(true))
                .build();
        e3.getSettings().getHttpCustomize().accept(null);
        assertTrue(hit.get());

        HarnessEngine e4 = builder().httpCustomizeSet(u -> hit.set(false)).build();
        e4.getSettings().getHttpCustomize().accept(null);
        assertFalse(hit.get());
    }

    @Test
    void toolWhitelistAndBlacklistThroughBuilder() {
        HarnessEngine e = builder()
                .toolsAdd(ToolName.TOOL_READ, ToolName.TOOL_WRITE)
                .toolsAdd(Collections.singletonList("grep"))
                .disallowedToolsAdd(ToolName.TOOL_BASH)
                .disallowedToolsAdd(Collections.singletonList("edit"))
                .build();

        assertTrue(e.getTools().contains("read"));
        assertTrue(e.getTools().contains("write"));
        assertTrue(e.getTools().contains("grep"));
        assertTrue(e.getDisallowedTools().contains("bash"));
        assertTrue(e.getDisallowedTools().contains("edit"));

        HarnessEngine e2 = builder()
                .toolsAdd(ToolName.TOOL_GLOB)
                .disallowedToolsAdd(ToolName.TOOL_LS)
                .build();
        assertTrue(e2.getTools().contains("glob"));
        assertTrue(e2.getDisallowedTools().contains("ls"));
    }

    @Test
    void modelRegistryThroughBuilder() {
        HarnessEngine e = builder()
                .modelAdd(model("a"))
                .modelAdd(Arrays.asList(model("b"), model("c")))
                .defaultModel("a")
                .build();

        assertEquals(3, e.getModels().size());
        assertEquals("a", e.getDefaultModel());
        assertNotNull(e.getModelOrNil("b"));
    }

    @Test
    void permissionRuleAddAccumulates() {
        HarnessEngine e = builder()
                .permissionRuleAdd(PermissionRule.allow("bash"))
                .permissionRuleAdd(PermissionRule.deny("rm"))
                .build();

        assertEquals(2, e.getPermissionContext().rules().size());
        assertEquals(PermissionBehavior.DENY, e.getPermissionContext().rules().get(1).behavior());
    }

    @Test
    void mountMcpApiLspServersAreRegistered() {
        Mount mount = Mount.builder()
                .alias("stuffs")
                .type(MountType.FILES)
                .source(new FileMountSource(workspace))
                .build();

        HarnessEngine e = builder()
                .mountAdd(mount)
                .mcpServerAdd("m1", new McpServerParameters())
                .build();

        assertTrue(e.hasMount("stuffs"));
        assertTrue(e.getMcpServers().containsKey("m1"));
    }

    @Test
    void mountAddRejectsBuiltinAlias() {
        Mount builtin = Mount.builder()
                .alias("@harness-agents")
                .type(MountType.AGENTS)
                .source(new FileMountSource(workspace))
                .build();

        assertThrows(IllegalArgumentException.class, () -> builder().mountAdd(builtin));

        // 【已确认的框架缺陷，用例锁定现状】
        // mountAdd 的 null 守卫只包住了「内置别名」检查，随后的 register(mount) 未防护，
        // 于是 mountAdd(null) 抛 IllegalArgumentException("mount/source must not be null")
        // 而不是被静默忽略。对比 HarnessEngine.addMount(null) 可直接通过。
        assertThrows(IllegalArgumentException.class, () -> builder().mountAdd(null));
    }

    @Test
    void extensionAddInitializesEnabledExtensionDuringBuild() {
        AtomicBoolean initialized = new AtomicBoolean();
        AbsHarnessExtension ext = new AbsHarnessExtension() {
            @Override
            public void onAttach(HarnessEngine engine) {
                initialized.set(true);
            }

            @Override
            public void onAgentBuild(HarnessEngine engine, String agentName, ReActAgent.Builder agentBuilder) {
            }
        };

        HarnessEngine e = builder().extensionAdd(ext).build();
        assertTrue(initialized.get(), "build() 应初始化已启用的扩展");
        assertTrue(e.getExtensions().contains(ext));
    }

    @Test
    void disabledExtensionStillInitializedDuringBuild() {
        // 构建期不看启用状态：initialize 属引擎级装配，开关在子组件与执行时生效
        AtomicBoolean initialized = new AtomicBoolean();
        AbsHarnessExtension ext = new AbsHarnessExtension() {
            @Override
            public boolean isEnabled() {
                return false;
            }

            @Override
            public void onAttach(HarnessEngine engine) {
                initialized.set(true);
            }

            @Override
            public void onAgentBuild(HarnessEngine engine, String agentName, ReActAgent.Builder agentBuilder) {
            }
        };

        builder().extensionAdd(ext).build();
        assertTrue(initialized.get(), "initialize 与启用状态无关");
    }

    @Test
    void commandPrefixRejectsEmptyAtBuildTime() {
        assertThrows(IllegalArgumentException.class, () -> builder().commandPrefix(""));
        assertThrows(IllegalArgumentException.class, () -> builder().agentPrefix(""));
    }
}
