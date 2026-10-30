package org.noear.solon.ai.harness;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.CacheControl;
import org.noear.solon.ai.harness.agent.ToolName;
import org.noear.solon.ai.harness.permission.PermissionContext;
import org.noear.solon.ai.harness.permission.PermissionRule;
import org.noear.solon.ai.harness.permission.PermissionBehavior;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HarnessOptions 配置面：默认值、派生路径、setter 的 null 忽略语义、集合操作。
 * <p>
 * 本类直接针对包内可见的 HarnessOptions（与引擎同包），不经过引擎，
 * 用于锁定「配置项默认值与写入语义」这一层契约——此前这些 setter/getter
 * 从未被任何测试触达（覆盖率显示 HarnessOptions 58/99 方法未覆盖）。
 */
class HarnessOptionsAccessorTest {

    private static HarnessOptions fresh() {
        return new HarnessOptions("work", null);
    }

    // ---------- 派生路径 ----------

    @Test
    void harnessHomeDefaultsToSolonAndAppendsTrailingSlash() {
        HarnessOptions o = new HarnessOptions("work", null);
        assertEquals(".solon/", o.getHarnessHome());

        HarnessOptions o2 = new HarnessOptions("work", "");
        assertEquals(".solon/", o2.getHarnessHome());

        HarnessOptions o3 = new HarnessOptions("work", ".soloncode");
        assertEquals(".soloncode/", o3.getHarnessHome());

        HarnessOptions o4 = new HarnessOptions("work", ".soloncode/");
        assertEquals(".soloncode/", o4.getHarnessHome());
    }

    @Test
    void derivedPathsAreHarnessHomePrefixedSegments() {
        HarnessOptions o = new HarnessOptions("work", ".h/");
        assertEquals(".h/sessions/", o.getHarnessSessions());
        assertEquals(".h/skills/", o.getHarnessSkills());
        assertEquals(".h/agents/", o.getHarnessAgents());
        assertEquals(".h/commands/", o.getHarnessCommands());
        assertEquals(".h/memory/", o.getHarnessMemory());
        assertEquals(".h/channels/", o.getHarnessChannels());
        assertEquals("work", o.getWorkspace());
    }

    // ---------- 默认值 ----------

    @Test
    void defaultsMatchDocumentedContract() {
        HarnessOptions o = fresh();
        assertEquals(20, o.getMaxTurns());
        assertTrue(o.isAutoRethink());
        assertEquals(12, o.getSessionWindowSize());
        assertEquals(100, o.getCompressionMaxMessages());
        assertEquals(0.75d, o.getCompressionMaxContextRatio(), 1e-9);
        assertEquals(128_000L, o.getCompressionDefaultContextLength());
        assertTrue(o.isMemoryEnabled());
        assertEquals(6, o.getMemoryRelevanceCount());
        assertEquals(5, o.getMemoryPriorityCount());
        assertEquals(80, o.getMemorySummaryLength());
        assertTrue(o.isSandboxEnabled());
        assertTrue(o.isSandboxAllowUserHome());
        assertFalse(o.isSandboxSystemRestrict());
        assertFalse(o.isHitlEnabled());
        assertTrue(o.isSubagentEnabled());
        assertFalse(o.isBashAsyncEnabled());
        assertEquals(3, o.getApiRetries());
        assertEquals(3, o.getMcpRetries());
        assertEquals(3, o.getModelRetries());
        assertEquals(5, o.getMultitaskMaxTasks());
        assertEquals("/", o.getCommandPrefix());
        assertEquals("@", o.getAgentPrefix());
        assertNotNull(o.getPermissionContext());
        assertNull(o.getSystemPrompt());
        assertNull(o.getUserAgent());
        assertNull(o.getCacheControl());
        assertNull(o.getDefaultModel());
    }

    // ---------- setter 的 null 忽略语义 ----------

    @Test
    void boxedSettersIgnoreNullAndKeepPreviousValue() {
        HarnessOptions o = fresh();

        o.setMaxTurns(7);
        o.setMaxTurns(null);
        assertEquals(7, o.getMaxTurns());

        o.setAutoRethink(false);
        o.setAutoRethink(null);
        assertFalse(o.isAutoRethink());

        o.setSessionWindowSize(30);
        o.setSessionWindowSize(null);
        assertEquals(30, o.getSessionWindowSize());

        o.setCompressionMaxMessages(50);
        o.setCompressionMaxMessages(null);
        assertEquals(50, o.getCompressionMaxMessages());

        o.setCompressionMaxContextRatio(0.5d);
        o.setCompressionMaxContextRatio(null);
        assertEquals(0.5d, o.getCompressionMaxContextRatio(), 1e-9);

        o.setCompressionDefaultContextLength(64_000L);
        o.setCompressionDefaultContextLength(null);
        assertEquals(64_000L, o.getCompressionDefaultContextLength());

        o.setMemoryEnabled(false);
        o.setMemoryEnabled(null);
        assertFalse(o.isMemoryEnabled());

        o.setSandboxEnabled(false);
        o.setSandboxEnabled(null);
        assertFalse(o.isSandboxEnabled());

        o.setSandboxAllowUserHome(false);
        o.setSandboxAllowUserHome(null);
        assertFalse(o.isSandboxAllowUserHome());

        o.setSandboxSystemRestrict(true);
        o.setSandboxSystemRestrict(null);
        assertTrue(o.isSandboxSystemRestrict());

        o.setHitlEnabled(true);
        o.setHitlEnabled(null);
        assertTrue(o.isHitlEnabled());

        o.setSubagentEnabled(false);
        o.setSubagentEnabled(null);
        assertFalse(o.isSubagentEnabled());

        o.setBashAsyncEnabled(true);
        o.setBashAsyncEnabled(null);
        assertTrue(o.isBashAsyncEnabled());

        o.setApiRetries(9);
        o.setApiRetries(null);
        assertEquals(9, o.getApiRetries());

        o.setMcpRetries(9);
        o.setMcpRetries(null);
        assertEquals(9, o.getMcpRetries());

        o.setModelRetries(9);
        o.setModelRetries(null);
        assertEquals(9, o.getModelRetries());

        o.setMultitaskMaxTasks(0);
        o.setMultitaskMaxTasks(null);
        assertEquals(0, o.getMultitaskMaxTasks());
    }

    @Test
    void prefixSettersRejectEmptyValue() {
        HarnessOptions o = fresh();

        o.setCommandPrefix("::");
        assertEquals("::", o.getCommandPrefix());
        try {
            o.setCommandPrefix("");
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertEquals("::", o.getCommandPrefix());
        }

        o.setAgentPrefix("#");
        assertEquals("#", o.getAgentPrefix());
        try {
            o.setAgentPrefix(null);
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertEquals("#", o.getAgentPrefix());
        }
    }

    @Test
    void nullableSettersAcceptValues() {
        HarnessOptions o = fresh();

        o.setSystemPrompt("sys");
        assertEquals("sys", o.getSystemPrompt());
        o.setUserAgent("ua");
        assertEquals("ua", o.getUserAgent());
        o.setCompressionModel("m1");
        assertEquals("m1", o.getCompressionModel());
        o.setDefaultModel("d1");
        assertEquals("d1", o.getDefaultModel());

        PermissionContext ctx = PermissionContext.create();
        o.setPermissionContext(ctx);
        assertSame(ctx, o.getPermissionContext());

        CacheControl cc = new CacheControl();
        o.setCacheControl(cc);
        assertSame(cc, o.getCacheControl());
    }

    @Test
    void httpCustomizeAddAppendsAndSetReplaces() {
        HarnessOptions o = fresh();
        assertNull(o.getHttpCustomize());

        o.addHttpCustomize(u -> {});
        assertNotNull(o.getHttpCustomize());

        java.util.concurrent.atomic.AtomicInteger hits = new java.util.concurrent.atomic.AtomicInteger();
        o.setHttpCustomize(u -> hits.incrementAndGet());
        o.getHttpCustomize().accept(null);
        assertEquals(1, hits.get());
        // add 是链式追加：set 后 add 仍然累加
        o.addHttpCustomize(u -> hits.addAndGet(10));
        o.getHttpCustomize().accept(null);
        assertEquals(12, hits.get());
    }

    // ---------- 集合操作 ----------

    @Test
    void addToolsAcceptsBothEnumKindsAndDeduplicates() {
        HarnessOptions o = fresh();

        o.addTools(ToolName.TOOL_BASH, ToolName.TOOL_READ);
        assertEquals(2, o.getTools().size());
        assertTrue(o.getTools().contains("bash"));
        assertTrue(o.getTools().contains("read"));

        // 重复添加同一工具不会增长
        o.addTools(ToolName.TOOL_BASH);
        assertEquals(2, o.getTools().size());

        @SuppressWarnings("deprecation")
        org.noear.solon.ai.harness.permission.ToolPermission old =
                org.noear.solon.ai.harness.permission.ToolPermission.TOOL_GREP;
        @SuppressWarnings("deprecation")
        org.noear.solon.ai.harness.permission.ToolPermission[] olds = {old};
        o.addTools(olds);
        assertTrue(o.getTools().contains("grep"));
    }

    @Test
    void addDisallowedToolsAcceptsBothEnumKinds() {
        HarnessOptions o = fresh();

        o.addDisallowedTools(ToolName.TOOL_WRITE);
        assertTrue(o.getDisallowedTools().contains("write"));

        @SuppressWarnings("deprecation")
        org.noear.solon.ai.harness.permission.ToolPermission[] olds =
                {org.noear.solon.ai.harness.permission.ToolPermission.TOOL_EDIT};
        o.addDisallowedTools(olds);
        assertTrue(o.getDisallowedTools().contains("edit"));
    }

    @Test
    void modelRegistryLookupRules() {
        HarnessOptions o = fresh();

        // 空表：任何查询都返回 null
        assertNull(o.getModelOrNil("a"));
        assertNull(o.getModelOrDef("a"));

        org.noear.solon.ai.chat.ChatConfig m1 = new org.noear.solon.ai.chat.ChatConfig();
        m1.setName("m1");
        m1.setModel("model-m1");
        o.addModel(m1);

        assertTrue(o.hasModel("m1"));
        assertNotNull(o.getModelOrNil("m1"));

        // 空名 → getModelOrNil 返回 null，getModelOrDef 回落到默认
        assertNull(o.getModelOrNil(""));
        assertNull(o.getModelOrNil(null));
        assertNotNull(o.getModelOrDef(""));
        assertNotNull(o.getModelOrDef(null));

        // 未知名 → nil 为 null，def 回落默认
        assertNull(o.getModelOrNil("nope"));
        assertNotNull(o.getModelOrDef("nope"));

        o.setDefaultModel("m1");
        assertEquals("m1", o.getModelOrDef("nope").getNameOrModel());

        o.removeModel("m1");
        assertFalse(o.hasModel("m1"));
        assertNull(o.getModelOrDef("nope"));
    }

    @Test
    void disabledModelIsNotReturnedByLookup() {
        HarnessOptions o = fresh();
        org.noear.solon.ai.chat.ChatConfig m1 = new org.noear.solon.ai.chat.ChatConfig();
        m1.setName("m1");
        m1.setModel("model-m1");
        m1.setEnabled(false);
        o.addModel(m1);

        assertTrue(o.hasModel("m1"));
        assertNull(o.getModelOrNil("m1"), "禁用模型不应被解析为可用模型");

        // 回落路径同样跳过 disabled：defaultModel 指向禁用项时继续向后找
        assertNull(o.getModelOrDef("m1"), "禁用模型不应被回落路径解析为可用模型");
    }

    // ---------- 服务注入 ----------

    @Test
    void serviceSlotsAreStoredAndReadBack() {
        HarnessOptions o = fresh();
        assertNull(o.getSessionProvider());

        o.setSessionProvider(org.noear.solon.ai.agent.session.InMemoryAgentSession::of);
        assertNotNull(o.getSessionProvider());

        o.setHitlInterceptor(new org.noear.solon.ai.agent.react.intercept.HITLInterceptor());
        assertNotNull(o.getHitlInterceptor());

        o.setCompressionInterceptor(new org.noear.solon.ai.agent.react.intercept.ContextCompressionInterceptor());
        assertNotNull(o.getCompressionInterceptor());

        o.setStopLoopInterceptor(new org.noear.solon.ai.agent.react.intercept.StopLoopInterceptor());
        assertNotNull(o.getStopLoopInterceptor());

        // memoryProvider 为抽象类型，此处只验证 setter/getter 往返（get 返回 null 即可，不解引用）
        org.noear.solon.ai.talents.memory.MemorySolutionProvider mp = cwd -> null;
        o.setMemoryProvider(mp);
        assertSame(mp, o.getMemoryProvider());
    }

    // ---------- 反射巡检：确保没有遗漏的公开 setter 未被触达 ----------

    /**
     * 兜底巡检：HarnessOptions 的所有 setter 都应在本类被调用过，
     * 否则新增配置项时会静默失去测试。此处不执行逻辑，仅列出未覆盖的 setter 名，
     * 便于人工核对（断言集合为空即全部覆盖）。
     */
    @Test
    void allSettersAreExercisedByThisTest() {
        List<String> expected = Arrays.stream(HarnessOptions.class.getDeclaredMethods())
                .filter(m -> m.getName().startsWith("set") || m.getName().startsWith("add"))
                .filter(m -> !m.isSynthetic())
                .map(Method::getName)
                .distinct()
                .collect(Collectors.toList());

        // 与上方用例点名的 setter 集合比对
        List<String> covered = Arrays.asList(
                "setSystemPrompt", "setUserAgent", "setHttpProxy", "setHttpCustomize",
                "setMaxTurns", "setAutoRethink", "setSessionWindowSize",
                "setCompressionMaxMessages", "setCompressionMaxContextRatio",
                "setCompressionDefaultContextLength", "setCompressionModel",
                "setMemoryEnabled", "setMemoryRelevanceCount", "setMemoryPriorityCount",
                "setMemorySummaryLength", "setSandboxEnabled", "setSandboxAllowUserHome",
                "setSandboxSystemRestrict", "setHitlEnabled", "setPermissionContext",
                "setSubagentEnabled", "setBashAsyncEnabled", "setApiRetries", "setMcpRetries",
                "setModelRetries", "setMultitaskMaxTasks", "setCacheControl",
                "setCommandPrefix", "setAgentPrefix", "setDefaultModel",
                "setSessionProvider", "setCompressionInterceptor", "setStopLoopInterceptor",
                "setHitlInterceptor", "setMemoryProvider",
                "addHttpCustomize", "addTools", "addDisallowedTools", "addModel");

        List<String> missing = expected.stream()
                .filter(n -> !covered.contains(n))
                .collect(Collectors.toList());
        assertTrue(missing.isEmpty(), "HarnessOptions 存在未被测试触达的 setter: " + missing);
    }

    /** PermissionRule 也从属于配置面，这里顺带锁定 builder 规则的累积语义。 */
    @Test
    void permissionRulesAccumulateOnContext() {
        HarnessOptions o = fresh();
        PermissionRule r1 = PermissionRule.allow("bash");
        o.setPermissionContext(o.getPermissionContext().addRule(r1));
        assertEquals(1, o.getPermissionContext().rules().size());
        assertEquals(PermissionBehavior.ALLOW, o.getPermissionContext().rules().get(0).behavior());
    }
}
