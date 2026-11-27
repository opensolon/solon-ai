package org.noear.solon.ai.harness.agent;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.harness.agent.AgentDefinition.Metadata;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AgentDefinition / Metadata / Builder 契约：构建、复制隔离、Markdown 往返、
 * 各类 has* 判据与集合累积语义。
 */
class AgentDefinitionTest {

    @Test
    void builderAppliesAllFields() {
        AgentDefinition d = AgentDefinition.builder()
                .name("worker")
                .description("desc")
                .systemPrompt("sp")
                .primary(true)
                .hidden(true)
                .toolsAdd("read", "write")
                .disallowedToolsAdd("bash")
                .build();

        assertEquals("worker", d.getName());
        assertEquals("desc", d.getDescription());
        assertEquals("sp", d.getSystemPrompt());
        assertTrue(d.getMetadata().isPrimary());
        assertTrue(d.isHidden());
        assertTrue(d.getMetadata().getTools().contains("read"));
        assertTrue(d.getMetadata().getDisallowedTools().contains("bash"));
    }

    @Test
    void toolsAddAcceptsCollectionAndVarargs() {
        AgentDefinition d = AgentDefinition.builder()
                .toolsAdd(Arrays.asList("a", "b"))
                .toolsAdd("c")
                .disallowedToolsAdd(Arrays.asList("x"))
                .disallowedToolsAdd("y")
                .build();

        assertTrue(d.getMetadata().getTools().containsAll(Arrays.asList("a", "b", "c")));
        assertTrue(d.getMetadata().getDisallowedTools().containsAll(Arrays.asList("x", "y")));
    }

    @Test
    void metadataConsumerIsApplied() {
        AgentDefinition d = AgentDefinition.builder()
                .metadata(m -> m.setName("viaConsumer"))
                .build();
        assertEquals("viaConsumer", d.getName());
    }

    @Test
    void copyProducesIndependentInstance() {
        AgentDefinition origin = AgentDefinition.builder()
                .name("origin")
                .systemPrompt("sp")
                .toolsAdd("read")
                .build();
        AgentDefinition copy = origin.copy();

        assertNotSame(origin, copy);
        assertEquals("origin", copy.getName());
        assertEquals("sp", copy.getSystemPrompt());

        // 修改副本不影响原实例
        copy.getMetadata().getTools().add("write");
        assertFalse(origin.getMetadata().getTools().contains("write"));
    }

    @Test
    void mountAliasIsSettableAndReadBack() {
        AgentDefinition d = AgentDefinition.builder().name("a").build();
        assertNull(d.getMountAlias());
        d.setMountAlias("@m");
        assertEquals("@m", d.getMountAlias());
    }

    @Test
    void setMetadataReplacesWholeMetadata() {
        AgentDefinition d = AgentDefinition.builder().name("old").build();
        Metadata m = new Metadata();
        m.setName("new");
        d.setMetadata(m);
        assertEquals("new", d.getName());
    }

    // ---------- has* 判据 ----------

    @Test
    void hasPredicatesReflectEmptyState() {
        Metadata m = new Metadata();
        assertFalse(m.hasModel());
        assertFalse(m.hasPermissionRules());
        assertFalse(m.hasSkills());
        assertFalse(m.hasMcpServers());
        assertFalse(m.hasDisallowedTools());
        assertFalse(m.hasMemory());
        assertFalse(m.isBackground());
        assertFalse(m.hasIsolation());
        assertFalse(m.hasTeamName());
    }

    @Test
    void hasPredicatesTurnTrueAfterBeingPopulated() {
        Metadata m = new Metadata();
        m.setModel("m1");
        assertTrue(m.hasModel());

        m.addPermissionRule(org.noear.solon.ai.harness.permission.PermissionRule.allow("bash"));
        assertTrue(m.hasPermissionRules());

        m.addTools("read");
        assertTrue(m.getTools().contains("read"));

        m.getDisallowedTools().add("bash");
        assertTrue(m.hasDisallowedTools());
    }

    @Test
    void metadataAddToolsAcceptsThreeOverloads() {
        Metadata m = new Metadata();

        m.addTools(ToolName.TOOL_READ);
        assertTrue(m.getTools().contains("read"));

        m.addTools(Collections.singletonList("grep"));
        assertTrue(m.getTools().contains("grep"));

        m.addTools("glob", "ls");
        assertTrue(m.getTools().contains("glob"));
        assertTrue(m.getTools().contains("ls"));

        m.addTools(ToolName.TOOL_BASH);
        assertTrue(m.getTools().contains("bash"));
    }

    @Test
    void addPermissionRulesIsNullSafe() {
        Metadata m = new Metadata();
        m.addPermissionRules(null);
        assertFalse(m.hasPermissionRules());

        m.addPermissionRules(Collections.singletonList(
                org.noear.solon.ai.harness.permission.PermissionRule.deny("bash")));
        assertTrue(m.hasPermissionRules());
    }

    // ---------- Markdown 往返 ----------

    @Test
    void toMarkdownEmitsNameAndPrompt() {
        AgentDefinition d = AgentDefinition.builder()
                .name("md-agent")
                .description("markdown roundtrip")
                .systemPrompt("You are md-agent.")
                .build();

        String md = d.toMarkdown();
        assertTrue(md.contains("md-agent"), md);
        assertTrue(md.contains("You are md-agent."), md);
    }

    @Test
    void fromMarkdownParsesBackIntoDefinition() {
        String src = "---\n"
                + "name: parsed-agent\n"
                + "description: parsed from markdown\n"
                + "---\n"
                + "You are parsed-agent.\n";

        AgentDefinition d = AgentDefinition.fromParsedMarkdown(
                org.noear.solon.ai.util.MarkdownUtil.resolve(java.util.Arrays.asList(src.split("\\n"))));
        assertEquals("parsed-agent", d.getName());
        assertTrue(d.getSystemPrompt().contains("parsed-agent"));
    }

    // ---------- AgentManager ----------

    @Test
    void managerAddGetAndOverwrite() {
        AgentManager mgr = new AgentManager(null);

        AgentDefinition a = AgentDefinition.builder().name("a").build();
        mgr.addAgent(a);
        assertEquals("a", mgr.getAgent("a").getName());
        assertTrue(mgr.hasAgent("a"));

        // addAgentIfAbsent 不覆盖既有
        AgentDefinition a2 = AgentDefinition.builder().name("a").description("new").build();
        assertFalse(mgr.addAgentIfAbsent(a2), "同名时返回 false");
        assertNull(mgr.getAgent("a").getDescription());

        // addAgent 覆盖
        mgr.addAgent(a2);
        assertEquals("new", mgr.getAgent("a").getDescription());
    }

    @Test
    void managerGetUnknownAgentThrows() {
        AgentManager mgr = new AgentManager(null);
        assertFalse(mgr.hasAgent("nope"));
        assertThrows(IllegalArgumentException.class, () -> mgr.getAgent("nope"));
    }

    @Test
    void managerFiltersHiddenAgentsFromList() {
        AgentManager mgr = new AgentManager(null);
        mgr.addAgent(AgentDefinition.builder().name("visible").build());
        mgr.addAgent(AgentDefinition.builder().name("hidden").hidden(true).build());

        Collection<AgentDefinition> all = mgr.getAgents();
        assertEquals(1, all.size());
        assertEquals("visible", all.iterator().next().getName());
        // 仍可按名精确取到隐藏代理
        assertTrue(mgr.hasAgent("hidden"));
    }

    private static AgentDefinition parse(String src) {
        return AgentDefinition.fromParsedMarkdown(
                org.noear.solon.ai.util.MarkdownUtil.resolve(Arrays.asList(src.split("\\n"))));
    }

    @Test
    void permissionRuleWithUnknownBehaviorIsSkippedNotFatal() {
        AgentDefinition d = parse("---\nname: \"x\"\ntools: [\"bash\"]\npermissionRules:\n"
                + "  - { toolName: \"bash\", behavior: \"alow\", pattern: \"git *\" }\n"
                + "  - { toolName: \"bash\", behavior: \"deny\", priority: -100 }\n---\nbody");

        assertEquals("x", d.getName());
        assertEquals(1, d.getMetadata().getPermissionRules().size(), "写错的一条跳过，其余照常");
        assertEquals(-100, d.getMetadata().getPermissionRules().get(0).priority());
    }

    @Test
    void permissionPatternWithQuotesRoundTrips() {
        AgentDefinition d = AgentDefinition.builder().name("x").build();
        d.getMetadata().addPermissionRule(
                org.noear.solon.ai.harness.permission.PermissionRule.allow("bash", "grep \"a\\b\" *"));

        AgentDefinition back = parse(d.toMarkdown());
        assertEquals(1, back.getMetadata().getPermissionRules().size());
        assertEquals("grep \"a\\b\" *", back.getMetadata().getPermissionRules().get(0).pattern().get());
    }

    @Test
    void toMarkdownRoundTripsListsRulesAndSkipsMatcherRules() {
        AgentDefinition d = AgentDefinition.builder()
                .name("rt")
                .description("say \"hi\": x")
                .toolsAdd("read", "bash")
                .systemPrompt("body")
                .build();
        d.getMetadata().addPermissionRule(org.noear.solon.ai.harness.permission.PermissionRule.deny("bash").priority(-100));
        d.getMetadata().addPermissionRule(org.noear.solon.ai.harness.permission.PermissionRule.allow("bash", "git log *"));
        d.getMetadata().addPermissionRule(org.noear.solon.ai.harness.permission.PermissionRule.allowExact("bash", "command", "ls"));

        String md = d.toMarkdown();
        AgentDefinition back = parse(md);
        assertEquals("rt", back.getName());
        assertEquals("say \"hi\": x", back.getDescription());
        assertEquals(Arrays.asList("read", "bash"), back.getMetadata().getTools(), md);
        assertEquals("body", back.getSystemPrompt());
        assertEquals(2, back.getMetadata().getPermissionRules().size(), "自定义匹配器规则无法用 markdown 表达，不写: " + md);
        assertEquals(-100, back.getMetadata().getPermissionRules().get(0).priority());
        assertEquals("git log *", back.getMetadata().getPermissionRules().get(1).pattern().get());
    }

    @Test
    void managerRemoveByMountAliasAndClear() {
        AgentManager mgr = new AgentManager(null);

        AgentDefinition mounted = AgentDefinition.builder().name("m1").build();
        mounted.setMountAlias("@pool");
        mgr.addAgent(mounted);

        AgentDefinition builtin = AgentDefinition.builder().name("b1").build();
        mgr.addAgent(builtin);

        mgr.removeByMountAlias("@pool");
        assertFalse(mgr.hasAgent("m1"));
        assertTrue(mgr.hasAgent("b1"));

        // null 别名安全
        mgr.removeByMountAlias(null);

        // clearCustomAgents 保留无挂载别名的
        AgentDefinition mounted2 = AgentDefinition.builder().name("m2").build();
        mounted2.setMountAlias("@x");
        mgr.addAgent(mounted2);
        mgr.clearCustomAgents();
        assertFalse(mgr.hasAgent("m2"));
        assertTrue(mgr.hasAgent("b1"));

        mgr.clear();
        assertFalse(mgr.hasAgent("b1"));
    }
}
