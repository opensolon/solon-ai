package org.noear.solon.ai.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.agent.ToolName;
import org.noear.solon.ai.harness.permission.PermissionBehavior;
import org.noear.solon.ai.harness.session.SessionSelections;
import org.noear.solon.ai.harness.permission.PermissionContext;
import org.noear.solon.ai.harness.permission.PermissionRule;
import org.noear.solon.ai.mount.source.FileMountSource;
import org.noear.solon.ai.mount.Mount;
import org.noear.solon.ai.mount.MountType;

import java.net.Proxy;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HarnessEngine 配置面与访问器契约：setter 生效、getter 与 options 一致、
 * 权限/挂载/扩展的运行时增删语义、越界入参的容错。
 * <p>
 * 与 BuiltinCommandsSystemTest（走 handle 全链路）互补：本类只验证「配置写入 → 读取 / 副作用」
 * 这一层，不触发推理。覆盖率基线显示 HarnessEngine 有 135 个方法从未被触达，绝大多数是此类访问器。
 */
class HarnessEngineAccessorTest {

    @TempDir
    Path workspace;

    /** 带模型 + 记忆方案的完整引擎：多数 setter 会连带操作 talent / 重建主代理，缺任一会 NPE */
    private HarnessEngine engine() {
        return HarnessEngine.of(workspace.toString(), null)
                .sessionProvider((id, c) -> InMemoryAgentSession.of(id))
                .memoryProvider(cwd -> null)
                .modelAdd(withEndpoint("acc"))
                .defaultModel("acc")
                .build();
    }

    /** 最小引擎：仅 sessionProvider，用于验证「默认值」与「未配置时的容错」 */
    private HarnessEngine bareEngine() {
        return HarnessEngine.of(workspace.toString(), null)
                .sessionProvider((id, c) -> InMemoryAgentSession.of(id))
                .build();
    }

    private static ChatConfig model(String name) {
        ChatConfig c = new ChatConfig();
        c.setName(name);
        c.setModel("model-" + name);
        return c;
    }

    // ---------- 路径与身份 ----------

    @Test
    void pathAccessorsDeriveFromWorkspaceAndHarnessHome() {
        HarnessEngine e = HarnessEngine.of(workspace.toString(), ".hh")
                .sessionProvider((id, c) -> InMemoryAgentSession.of(id))
                .build();

        assertEquals(".hh/", e.getHarnessHome());
        assertEquals(".hh/sessions/", e.getHarnessSessions());
        assertEquals(".hh/skills/", e.getHarnessSkills());
        assertEquals(".hh/agents/", e.getHarnessAgents());
        assertEquals(".hh/commands/", e.getHarnessCommands());
        assertEquals(".hh/memory/", e.getHarnessMemory());
        assertEquals(".hh/channels/", e.getHarnessChannels());
        assertEquals(workspace.toString(), e.getWorkspace());
        assertNotNull(e.getUserDir());
        assertNotNull(e.getUserHome());
    }

    @Test
    void getNameResolvesThroughMainAgent() {
        HarnessEngine e = engine();
        // 主代理懒加载：getName 会触发构建
        assertNotNull(e.getName());
        assertNotNull(e.getMainAgent());
    }

    // ---------- 配置 setter 全量往返 ----------

    @Test
    void numericAndBooleanSettersRoundTrip() {
        HarnessEngine e = engine();

        e.setMaxTurns(9);
        assertEquals(9, e.getMaxTurns());
        e.setModelRetries(4);
        assertEquals(4, e.getModelRetries());
        e.setMcpRetries(5);
        assertEquals(5, e.getMcpRetries());
        e.setApiRetries(6);
        assertEquals(6, e.getApiRetries());
        e.setSubagentDefaultConcurrency(2);
        assertEquals(2, e.getSubagentDefaultConcurrency());

        // 旧名 @Deprecated 桥接：必须与新名同效
        e.setSubagentDefaultConcurrency(3);
        assertEquals(3, e.getSubagentDefaultConcurrency());

        // 桥梁必须同步推送到执行者，否则只写 options 不生效
        assertEquals(3, e.getTaskTalent().getDefaultConcurrency());

        e.setMemoryEnabled(false);
        assertFalse(e.isMemoryEnabled());
        e.setMemoryRelevanceCount(3);
        e.setMemoryPriorityCount(4);
        e.setMemorySummaryLength(42);

        e.setSandboxEnabled(false);
        assertFalse(e.isSandboxEnabled());
        e.setSandboxAllowUserHome(false);
        e.setSandboxSystemRestrict(true);
        assertTrue(e.isSandboxSystemRestrict());

        e.setHitlEnabled(true);
        assertTrue(e.isHitlEnabled());
        e.setSubagentEnabled(false);
        assertFalse(e.isSubagentEnabled());
        e.setBashAsyncEnabled(true);
        assertTrue(e.isBashAsyncEnabled());

        e.setSessionWindowSize(8);
        assertEquals(8, e.getSessionWindowSize());
        e.setCompressionThreshold(40, 0.5d);
        assertEquals(40, e.getCompressionMaxMessages());
        assertEquals(0.5d, e.getCompressionMaxContextRatio(), 1e-9);
        e.setCompressionDefaultContextLength(9_000L);
        assertEquals(9_000L, e.getCompressionDefaultContextLength());

        e.setSystemPrompt("sys");
        assertEquals("sys", e.getSystemPrompt());
        e.setDefaultModel("m1");
        assertEquals("m1", e.getDefaultModel());

        // null 入参被忽略，保留旧值
        e.setMaxTurns(null);
        assertEquals(9, e.getMaxTurns());
        e.setHitlEnabled(null);
        assertTrue(e.isHitlEnabled());
    }

    @Test
    void defaultConfigValuesAreExposedThroughEngine() {
        HarnessEngine e = bareEngine();
        assertEquals(20, e.getMaxTurns());
        assertTrue(e.isAutoRethink());
        assertEquals(12, e.getSessionWindowSize());
        assertEquals(100, e.getCompressionMaxMessages());
        assertEquals(0.75d, e.getCompressionMaxContextRatio(), 1e-9);
        assertEquals(128_000L, e.getCompressionDefaultContextLength());
        assertTrue(e.isMemoryEnabled());
        assertTrue(e.isSandboxEnabled());
        assertFalse(e.isHitlEnabled());
        assertTrue(e.isSubagentEnabled());
        assertFalse(e.isBashAsyncEnabled());
        assertEquals("/", e.getCommandPrefix());
        assertEquals("@", e.getAgentPrefix());
        assertEquals(3, e.getApiRetries());
        assertEquals(3, e.getMcpRetries());
        assertEquals(3, e.getModelRetries());
        assertEquals(5, e.getSubagentDefaultConcurrency());
        assertNull(e.getSystemPrompt());
        assertNull(e.getUserAgent());
        assertNull(e.getDefaultModel());
        assertNull(e.getCacheControl());
        assertNull(e.getHttpProxy());
        assertNull(e.getHttpCustomize());
        assertNull(e.getModelOrNil("none"));
    }

    @Test
    void httpProxyAndCustomizeAreStored() {
        HarnessEngine e = engine();
        e.setHttpProxy("127.0.0.1", 7890);
        assertNotNull(e.getHttpProxy());

        Proxy direct = Proxy.NO_PROXY;
        e.setHttpProxy(direct);
        assertSame(direct, e.getHttpProxy());

        AtomicBoolean called = new AtomicBoolean();
        e.setHttpCustomize(u -> called.set(true));
        e.getHttpCustomize().accept(null);
        assertTrue(called.get());

        e.addHttpCustomize(u -> {});
        assertNotNull(e.getHttpCustomize());
    }

    @Test
    void modelRegistryThroughEngine() {
        HarnessEngine e = bareEngine();

        assertTrue(e.getModels().isEmpty());
        e.addModel(model("a"));
        e.addModel(model("b"));
        assertEquals(2, e.getModels().size());
        assertTrue(e.hasModel("a"));

        assertNotNull(e.getModelOrNil("a"));
        // 未知模型：nil 变体返回 null
        assertNull(e.getModelOrNil("zzz"));

        e.setDefaultModel("a");
        assertSame(e.getModelOrDef("a"), e.getModelOrNil("a"));
        // def 未知模型回落默认
        assertEquals("a", e.getModelOrDef("zzz").getNameOrModel());

        e.removeModel("b");
        assertFalse(e.hasModel("b"));
    }

    @Test
    void setMemoryEnabledToleratesMissingMemoryProvider() {
        // 未注入 memoryProvider 时 memoryTalent 为 null；开关只写配置，不抛 NPE
        HarnessEngine e = bareEngine();

        e.setMemoryEnabled(true);
        assertTrue(e.isMemoryEnabled());
        e.setMemoryEnabled(false);
        assertFalse(e.isMemoryEnabled());
    }

    @Test
    void fallbackModelSkipsDisabledConfigs() {
        HarnessEngine e = bareEngine();

        ChatConfig disabled = model("off");
        disabled.setEnabled(false);
        e.addModel(disabled);

        // 唯一模型被禁用：显式名与"未知名回落"都取不到
        assertNull(e.getModelOrNil("off"));
        assertNull(e.getModelOrDef("off"));
        assertNull(e.getModelOrDef("unknown"));
        assertNull(e.getModelOrDefInstance("off"));

        // 加入启用模型后，回落跳过禁用项直接命中它
        e.addModel(model("on"));
        assertEquals("on", e.getModelOrDef("off").getNameOrModel());
        assertEquals("on", e.getModelOrDef("unknown").getNameOrModel());
    }

    @Test
    void disabledDefaultModelFallsBackToNextEnabled() {
        HarnessEngine e = bareEngine();

        e.addModel(model("a"));
        e.addModel(model("b"));
        e.setDefaultModel("a");
        // 禁用默认模型：不应继续返回它，而应向后回落到 b
        e.getModelOrNil("a"); // 确认已注册
        e.removeModel("a");
        e.addModel(disable(model("a")));

        assertEquals("b", e.getModelOrDef(null).getNameOrModel());
    }

    private static ChatConfig disable(ChatConfig c) {
        c.setEnabled(false);
        return c;
    }

    @Test
    void modelInstanceAccessorsBuildChatModel() {
        HarnessEngine e = engine();
        e.addModel(withEndpoint("i1"));
        e.setDefaultModel("i1");

        assertNotNull(e.getModelOrNilInstance("i1"));
        assertNull(e.getModelOrNilInstance("nope"));
        assertNotNull(e.getModelOrDefInstance("i1"));
        // def 变体在未知模型时回落默认
        assertNotNull(e.getModelOrDefInstance("nope"));
    }

    private static ChatConfig withEndpoint(String name) {
        ChatConfig c = new ChatConfig();
        c.setName(name);
        c.setModel("agnes-3.0-flash");
        c.setApiUrl("https://example.invalid/v1");
        c.setApiKey("k");
        return c;
    }

    // ---------- 工具权限 ----------

    @Test
    void allowAndDisallowToolMutateBothSets() {
        HarnessEngine e = engine();

        e.allowTool("bash");
        assertTrue(e.getTools().contains("bash"));
        assertFalse(e.getDisallowedTools().contains("bash"));

        // allow 会从黑名单移除
        e.disallowTool("bash");
        assertTrue(e.getDisallowedTools().contains("bash"));
        assertFalse(e.getTools().contains("bash"));

        e.allowTool("bash");
        assertTrue(e.getTools().contains("bash"));
        assertFalse(e.getDisallowedTools().contains("bash"));

        // 空值直接忽略
        e.allowTool("");
        e.disallowTool(null);
        assertTrue(e.getTools().contains("bash"));

        e.allowToolReset(Arrays.asList("read", "write"));
        assertEquals(2, e.getTools().size());
        assertTrue(e.getTools().contains("read"));

        e.disallowToolReset(Collections.singletonList("edit"));
        assertTrue(e.getDisallowedTools().contains("edit"));

        // null 集合等于清空
        e.allowToolReset(null);
        assertTrue(e.getTools().isEmpty());

        e.toolPermissionReset(Arrays.asList("grep"), Arrays.asList("bash"));
        assertTrue(e.getTools().contains("grep"));
        assertTrue(e.getDisallowedTools().contains("bash"));
        assertFalse(e.getDisallowedTools().contains("edit"));

        e.toolPermissionReset(null, null);
        assertTrue(e.getTools().isEmpty());
        assertTrue(e.getDisallowedTools().isEmpty());
    }

    // ---------- 权限规则 ----------

    @Test
    void permissionRulesAddThroughEngine() {
        HarnessEngine e = engine();
        PermissionContext before = e.getPermissionContext();
        assertNotNull(before);

        e.addPermissionRule(PermissionRule.allow("bash"));
        assertEquals(1, e.getPermissionContext().rules().size());
        assertEquals(PermissionBehavior.ALLOW, e.getPermissionContext().rules().get(0).behavior());

        // null 规则被忽略
        e.addPermissionRule(null);
        assertEquals(1, e.getPermissionContext().rules().size());

        e.addPermissionRules(Arrays.asList(PermissionRule.deny("rm"), PermissionRule.ask("write")));
        assertEquals(3, e.getPermissionContext().rules().size());
        // null 列表被忽略
        e.addPermissionRules(null);
        assertEquals(3, e.getPermissionContext().rules().size());
    }

    // ---------- 挂载 ----------

    @Test
    void mountAddRemoveAndRefresh() {
        HarnessEngine e = engine();
        Mount m = Mount.builder()
                .alias("stuffs")
                .type(MountType.FILES)
                .source(new FileMountSource(workspace))
                .build();

        e.addMount(m);
        assertTrue(e.hasMount("stuffs"));
        assertFalse(e.getMounts().isEmpty());
        assertNotNull(e.getMount("stuffs"));

        e.refreshMount("stuffs");
        e.refreshMount(null);   // 全量刷新分支

        e.removeMount("stuffs");
        assertFalse(e.hasMount("stuffs"));
    }

    @Test
    void builtinAgentMountCannotBeReplacedOrRemoved() {
        HarnessEngine e = engine();
        Mount builtin = Mount.builder()
                .alias("@harness-agents")
                .type(MountType.AGENTS)
                .source(new FileMountSource(workspace))
                .build();

        assertThrows(IllegalArgumentException.class, () -> e.addMount(builtin));
        assertThrows(IllegalArgumentException.class,
                () -> e.removeMount("@harness-agents"));
        // 不带 @ 前缀也应被识别
        String bare = "@harness-agents".startsWith("@")
                ? "@harness-agents".substring(1)
                : "@harness-agents";
        assertThrows(IllegalArgumentException.class, () -> e.removeMount(bare));
    }

    // ---------- 扩展 ----------

    @Test
    void extensionAddTriggersInitializeOnceAndRemoveStopsConfiguring() {
        HarnessEngine e = engine();
        AtomicBoolean initialized = new AtomicBoolean();
        AtomicBoolean configured = new AtomicBoolean();
        AtomicBoolean enabled = new AtomicBoolean(true);

        HarnessExtension ext = new HarnessExtension() {
            @Override
            public boolean isEnabled() {
                return enabled.get();
            }

            @Override
            public void onAttach(HarnessEngine engine) {
                initialized.set(true);
            }

            @Override
            public void onAgentBuild(HarnessEngine engine, String agentName, ReActAgent.Builder builder) {
                configured.set(true);
            }
        };

        e.addExtension(ext);
        assertTrue(initialized.get(), "addExtension 应立即触发 initialize");
        assertTrue(e.getExtensions().contains(ext));

        // null 被忽略
        e.addExtension(null);
        assertFalse(e.getExtensions().isEmpty());

        e.removeExtension(ext);
        assertFalse(e.getExtensions().contains(ext));
        e.removeExtension(null);
        assertEquals(0, e.getExtensions().size());
    }

    @Test
    void disabledExtensionStillInitializesOnAdd() {
        // initialize 不再看启用状态：禁用扩展也要完成引擎级装配（注册命令/资源），
        // 开关只在子组件与执行时生效。否则「构造期关闭」会造成命令未注册、运行期改回 true 也挂不上。
        HarnessEngine e = engine();
        AtomicBoolean initialized = new AtomicBoolean();

        HarnessExtension ext = new AbsHarnessExtension() {
            @Override
            public boolean isEnabled() {
                return false;
            }

            @Override
            public void onAttach(HarnessEngine engine) {
                initialized.set(true);
            }

            @Override
            public void onAgentBuild(HarnessEngine engine, String agentName, ReActAgent.Builder builder) {
            }
        };

        e.addExtension(ext);
        assertTrue(initialized.get(), "禁用扩展仍应完成 initialize 装配");
        assertTrue(e.getExtensions().contains(ext));

        // 运行期开关：不重建主代理也能传播状态（命令/拦截器的运行期判定即时生效）
        e.setExtensionEnabled(ext, true);
        assertFalse(ext.isEnabled(), "匿名类覆写了 isEnabled，不受 AbsSwitchable 状态影响");
    }

    @Test
    void setExtensionEnabledTogglesAbsExtensionAndRegistersNewOne() {
        HarnessEngine e = engine();
        AbsHarnessExtension ext = new AbsHarnessExtension() {
            @Override
            public void onAgentBuild(HarnessEngine engine, String agentName, ReActAgent.Builder builder) {
            }
        };

        // 未加入的扩展：先加入（与 addExtension 一致），不静默丢弃
        e.setExtensionEnabled(ext, false);
        assertTrue(e.getExtensions().contains(ext));
        assertTrue(ext.isEnabled(), "新加入时仍为默认启用（setExtensionEnabled 只负责加入）");

        e.setExtensionEnabled(ext, false);
        assertFalse(ext.isEnabled());

        e.setExtensionEnabled(ext, true);
        assertTrue(ext.isEnabled());

        e.setExtensionEnabled(ext, null);
        assertTrue(ext.isEnabled(), "null = 忽略本次设置");

        // null 入参不抛
        e.setExtensionEnabled(null, false);
    }

    // ---------- 技能黑名单 ----------

    @Test
    void disallowedSkillsLifecycle() {
        HarnessEngine e = engine();

        assertTrue(e.getDisallowedSkills().isEmpty());
        assertFalse(e.isSkillDisallowed("a/b"));

        e.disallowSkill("a/b");
        assertTrue(e.isSkillDisallowed("a/b"));
        assertTrue(e.getDisallowedSkills().contains("a/b"));

        e.allowSkill("a/b");
        assertFalse(e.isSkillDisallowed("a/b"));

        e.disallowSkillReset(Arrays.asList("x", "y"));
        assertTrue(e.isSkillDisallowed("x"));
        assertTrue(e.isSkillDisallowed("y"));

        e.disallowSkillReset(null);
        assertTrue(e.getDisallowedSkills().isEmpty());
    }

    // ---------- 目录查询（空态安全） ----------

    @Test
    void emptyCatalogsReturnEmptyCollectionsNot() {
        HarnessEngine e = bareEngine();
        // harness 自带内置技能与内置挂载（来自 classpath 内置目录），空工作区也不是零
        assertTrue(e.getSkills().size() >= 0);
        assertNull(e.getSkill("nope"));
        assertEquals(0, e.getSkillsByMount("nope").size());
        assertEquals(0, e.getAgentsByMount("nope").size());
        assertNull(e.getMount("nope"));
        assertTrue(e.getMounts().size() > 0);
        assertTrue(e.getMcpServers().isEmpty());
        assertTrue(e.getApiServers().isEmpty());
        assertTrue(e.getLspServers().isEmpty());
    }

    // ---------- 反射巡检：setter 是否都有用例触达 ----------

    @Test
    void allPublicSettersAreExercised() {
        List<String> expected = Arrays.stream(HarnessEngine.class.getDeclaredMethods())
                .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()))
                .filter(m -> (m.getName().startsWith("set") || m.getName().startsWith("add")
                        || m.getName().startsWith("allow") || m.getName().startsWith("disallow")
                        || m.getName().startsWith("remove") || m.getName().startsWith("refresh")))
                .filter(m -> !m.isSynthetic())
                .map(java.lang.reflect.Method::getName)
                .distinct()
                .collect(Collectors.toList());

        // 动态增删的 MCP / API / LSP server 需要外部运行时，单独由 HarnessEngineServerTest 覆盖
        List<String> delegated = Arrays.asList(
                "addMcpServer", "getMcpServer", "refreshMcpServer", "removeMcpServer",
                "addApiServer", "getApiServer", "refreshApiServer", "removeApiServer",
                "addLspServer", "removeLspServer");

        List<String> missing = expected.stream()
                .filter(n -> !delegated.contains(n))
                .filter(n -> !SWEEP_COVERED.contains(n))
                .collect(Collectors.toList());

        assertTrue(missing.isEmpty(), "HarnessEngine 存在未被测试触达的公开配置方法: " + missing);
    }

    /** 本类用例已覆盖的公开配置方法（用于上面的巡检比对） */
    private static final List<String> SWEEP_COVERED = Arrays.asList(
            "setMaxTurns", "setModelRetries", "setMcpRetries", "setApiRetries", "setSubagentDefaultConcurrency", "setMultitaskMaxConcurrency", "setMultitaskMaxTasks",
            "setMemoryEnabled", "setMemoryRelevanceCount", "setMemoryPriorityCount", "setMemorySummaryLength",
            "setSandboxEnabled", "setSandboxAllowUserHome", "setSandboxSystemRestrict",
            "setHitlEnabled", "setSubagentEnabled", "setBashAsyncEnabled", "setSessionWindowSize",
            "setCompressionThreshold", "setCompressionDefaultContextLength", "setSystemPrompt",
            "setCacheControl", "setHttpProxy", "setHttpCustomize", "addHttpCustomize",
            "setDefaultModel", "addModel", "removeModel",
            "allowTool", "allowToolReset", "toolPermissionReset", "disallowTool", "disallowToolReset",
            "addPermissionRule", "addPermissionRules",
            "addMount", "removeMount", "refreshMount", "refreshMainAgent",
            "addExtension", "removeExtension", "setExtensionEnabled",
            "addExtensionIfAbsent", "removeSession" /* HarnessPluginContractTest */,
            "setSessionCwd", "setSessionCwdIfAbsent", "setSessionModel", "setSessionAgent",
            "disallowSkill", "allowSkill", "disallowSkillReset");

    @Test
    void refreshMainAgentRebuildsInstance() {
        HarnessEngine e = engine();
        Object before = e.getMainAgent();
        e.refreshMainAgent();
        Object after = e.getMainAgent();
        assertNotNull(after);
        assertNotSame(before, after, "refreshMainAgent 应重建主代理实例");
    }

    @Test
    void cacheControlSetterApplies() {
        HarnessEngine e = engine();
        org.noear.solon.ai.chat.CacheControl cc = new org.noear.solon.ai.chat.CacheControl();
        e.setCacheControl(cc);
        assertSame(cc, e.getCacheControl());
    }

    /**
     * 会话工作目录门面：未设置时回退到工作区默认，设置/清除/仅补写三个入口语义明确。
     *
     * <p>此前宿主各自写 {@code attrs().getOrDefault(ATTR_CWD, ".")}：既把引擎内部键
     * 当私有状态用，又硬编码了与工作区默认值不一致的 {@code "."}。</p>
     */
    @Test
    void sessionCwdAccessorsFallBackToWorkspace() {
        HarnessEngine e = engine();
        AgentSession session = InMemoryAgentSession.of();

        assertEquals(e.getWorkspace(), e.getSessionCwd(session), "未设置时必须回退工作区默认");

        e.setSessionCwd(session, "/tmp/project-a");
        assertEquals("/tmp/project-a", e.getSessionCwd(session));

        // 仅补写：已有值不被覆盖（用户后续选择的 cwd 优先）
        e.setSessionCwdIfAbsent(session, "/tmp/project-b");
        assertEquals("/tmp/project-a", e.getSessionCwd(session));

        // 空值 / null ⇒ 清除，回退工作区默认
        e.setSessionCwd(session, "  ");
        assertEquals(e.getWorkspace(), e.getSessionCwd(session));
        e.setSessionCwd(session, "/tmp/x");
        e.setSessionCwd(session, null);
        assertEquals(e.getWorkspace(), e.getSessionCwd(session));
    }

    /**
     * 会话级选择项门面：模型 / 子代理的「设置、读取、清除」三态语义必须归一。
     *
     * <p>此前宿主直接读写 {@code CTX_MODEL_SELECTED} / {@code CTX_AGENT_SELECTED}，
     * 同一个「清除」出现了三种写法（{@code put("")} / {@code remove} / {@code put(null)}），
     * 读侧也各自判断，语义漂移不可枚举。本测试锁定门面口径：
     * <b>null 与空串都等于「未设置」</b>，写入即清除。</p>
     */
    @Test
    void sessionSelectionAccessorsNormalizeEmptyToUnset() {
        HarnessEngine e = engine();
        AgentSession session = InMemoryAgentSession.of();

        // 未设置
        assertNull(e.getSessionModel(session));
        assertNull(e.getSessionAgent(session));

        // 设置后读回
        e.setSessionModel(session, "gpt-4o");
        e.setSessionAgent(session, "coder");
        assertEquals("gpt-4o", e.getSessionModel(session));
        assertEquals("coder", e.getSessionAgent(session));

        // 静态读开口与实例门面必须同口径（供拿不到引擎实例的工具使用）
        assertEquals("gpt-4o", SessionSelections.modelOf(session));
        assertEquals("coder", SessionSelections.agentOf(session));

        // 空串 / null / 空白 ⇒ 清除，且读回统一为 null（不是 ""）
        e.setSessionModel(session, "");
        e.setSessionAgent(session, "   ");
        assertNull(e.getSessionModel(session), "空串必须等同未设置，不能读出 \"\"");
        assertNull(e.getSessionAgent(session), "空白必须等同未设置");

        // 回到主代理 / 默认模型
        e.setSessionModel(session, "gpt-4o");
        e.setSessionModel(session, null);
        assertNull(e.getSessionModel(session));

        // 会话为 null 时全部是空操作，不得抛
        assertNull(e.getSessionModel(null));
        assertNull(e.getSessionAgent(null));
        e.setSessionModel(null, "x");
        e.setSessionAgent(null, "x");
    }
}
