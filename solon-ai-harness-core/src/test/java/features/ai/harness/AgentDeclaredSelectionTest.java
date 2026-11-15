package features.ai.harness;

import features.ai.harness.i18n.HarnessFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActOptions;
import org.noear.solon.ai.agent.react.ReActRequest;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.agent.AgentDefinition;
import org.noear.solon.ai.harness.agent.SessionAgents;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputOutcome;
import org.noear.solon.ai.mcp.client.McpServerParameters;
import org.noear.solon.ai.mount.Mount;
import org.noear.solon.ai.mount.MountType;
import org.noear.solon.ai.mount.source.FileMountSource;
import org.noear.solon.ai.talents.cli.SkillTalent;
import org.noear.solon.ai.talents.gateway.McpGatewayTalent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 智能体声明的技能 / MCP（定义里的 {@code skills} / {@code mcpServers}）：用到该智能体的新消息上转给本轮输入，
 * 与 {@link HarnessInput#selectedSkill} / {@link HarnessInput#selectedMcp} 同一语义（收窄 + 预加载）。
 *
 * <p>同时锁定选择的轮次边界：新消息不沿用上一轮的选择（请求选项从上一轮复制），续流沿用被中断那一轮。</p>
 */
class AgentDeclaredSelectionTest {
    @TempDir
    Path workspace;

    @Test
    void declarationIsTransferredToInputWhenAgentIsUsed() throws Exception {
        HarnessEngine engine = engine();
        engine.getAgentManager().addAgent(agent("helper", Arrays.asList("read", "skill", "mcp"),
                Arrays.asList("alpha"), Arrays.asList("m1")));

        // 选择器选中
        HarnessInput input = HarnessInput.of(engine.getSession("s1"), "hi").selectedAgent("helper");
        InputOutcome outcome = engine.handle(input);
        try {
            assertEquals(InputOutcome.Kind.TASK, outcome.getKind());
            assertEquals(Arrays.asList("alpha"), input.getSelectedSkills());
            assertEquals(Arrays.asList("m1"), input.getSelectedMcps());

            Map<String, Object> ctx = optionsOf(outcome).getToolContext();
            assertEquals(Arrays.asList("alpha"), SkillTalent.getSelected(ctx));
            assertEquals(Arrays.asList("m1"), McpGatewayTalent.getSelected(ctx));

            // 用户消息上记的事实与执行生效的一致
            assertEquals(Arrays.asList("alpha"), SkillTalent.getSelected(outcome.getPrompt()));
            assertEquals(Arrays.asList("m1"), McpGatewayTalent.getSelected(outcome.getPrompt()));
        } finally {
            outcome.discard();
        }

        // @提及
        InputOutcome mentioned = engine.handle(HarnessInput.of(engine.getSession("s2"), "@helper hi"));
        try {
            assertEquals("helper", mentioned.getAgentName());
            assertEquals(Arrays.asList("alpha"), SkillTalent.getSelected(optionsOf(mentioned).getToolContext()));
        } finally {
            mentioned.discard();
        }

        // 主代理没有声明：不限制
        assertSelection(engine, null, null, null);
    }

    @Test
    void explicitSelectionWinsOverDeclaration() throws Exception {
        HarnessEngine engine = engine();
        engine.getAgentManager().addAgent(agent("helper", Arrays.asList("skill", "mcp"),
                Arrays.asList("alpha"), Arrays.asList("m1")));

        InputOutcome outcome = engine.handle(HarnessInput.of(engine.getSession("s1"), "hi")
                .selectedAgent("helper").selectedSkill("beta"));
        try {
            Map<String, Object> ctx = optionsOf(outcome).getToolContext();
            assertEquals(Arrays.asList("beta"), SkillTalent.getSelected(ctx));
            // 只显式选了技能：MCP 仍按声明
            assertEquals(Arrays.asList("m1"), McpGatewayTalent.getSelected(ctx));
        } finally {
            outcome.discard();
        }
    }

    @Test
    void newTurnDoesNotInheritPreviousSelection() throws Exception {
        HarnessEngine engine = engine();
        AgentSession session = engine.getSession("s1");

        InputOutcome first = engine.handle(HarnessInput.of(session, "one").selectedSkill("alpha").selectedMcp("m1"));
        ranWith(engine, session, null, first);

        // 下一轮不传选择 = 不限制（请求选项从上一轮复制，只写不清会沿用上一轮）
        InputOutcome second = engine.handle(HarnessInput.of(session, "two"));
        try {
            Map<String, Object> ctx = optionsOf(second).getToolContext();
            assertFalse(ctx.containsKey(SkillTalent.CTX_SKILLS_SELECTED), "skills residue");
            assertFalse(ctx.containsKey(McpGatewayTalent.CTX_MCPS_SELECTED), "mcps residue");
        } finally {
            second.discard();
        }
    }

    @Test
    void newTurnOnDeclaringAgentReplacesPreviousExplicitSelection() throws Exception {
        HarnessEngine engine = engine();
        engine.getAgentManager().addAgent(agent("helper", Arrays.asList("skill", "mcp"),
                Arrays.asList("alpha"), null));
        AgentSession session = engine.getSession("s1");

        InputOutcome first = engine.handle(HarnessInput.of(session, "one").selectedAgent("helper")
                .selectedSkill("beta").selectedMcp("m1"));
        ranWith(engine, session, "helper", first);

        InputOutcome second = engine.handle(HarnessInput.of(session, "two").selectedAgent("helper"));
        try {
            Map<String, Object> ctx = optionsOf(second).getToolContext();
            assertEquals(Arrays.asList("alpha"), SkillTalent.getSelected(ctx));
            assertFalse(ctx.containsKey(McpGatewayTalent.CTX_MCPS_SELECTED), "mcps residue");
        } finally {
            second.discard();
        }
    }

    @Test
    void resumeKeepsTheInterruptedRoundSelection() throws Exception {
        HarnessEngine engine = engine();
        engine.getAgentManager().addAgent(agent("helper", Arrays.asList("skill", "mcp"),
                Arrays.asList("alpha"), Arrays.asList("m1")));
        AgentSession session = engine.getSession("s1");

        // 被中断那一轮显式选了 beta：续流沿用它，不换成声明
        InputOutcome first = engine.handle(HarnessInput.of(session, "one").selectedAgent("helper").selectedSkill("beta"));
        ranWith(engine, session, "helper", first);

        HarnessInput resume = HarnessInput.of(session, null).selectedAgent("helper").resume(true);
        InputOutcome resumed = engine.handle(resume);
        try {
            assertEquals(InputOutcome.Kind.TASK, resumed.getKind());
            Map<String, Object> ctx = optionsOf(resumed).getToolContext();
            assertEquals(Arrays.asList("beta"), SkillTalent.getSelected(ctx));
            assertEquals(Arrays.asList("m1"), McpGatewayTalent.getSelected(ctx));
            // 续流不往输入上补声明
            assertFalse(resume.hasSkillSelection());
            assertFalse(resume.hasMcpSelection());
        } finally {
            resumed.discard();
        }
    }

    @Test
    void resumeAfterReloadFallsBackToUnrestricted() throws Exception {
        HarnessEngine engine = engine();
        engine.getAgentManager().addAgent(agent("helper", Arrays.asList("skill", "mcp"),
                Arrays.asList("alpha"), Arrays.asList("m1")));
        AgentSession session = engine.getSession("s1");

        // 上一轮跑过并显式选了 beta，随后会话从存储重新加载：轨迹在，但 options 是 transient 为空的
        InputOutcome first = engine.handle(HarnessInput.of(session, "one").selectedAgent("helper").selectedSkill("beta"));
        reloaded(engine, session, "helper", first);

        // 已知边界：续流靠「上一轮复制的选项」，复制源头为空时既不读输入也不读声明，
        // 退回不限制（fail-open，不是静默变窄）；不补声明是为了不覆盖被中断那一轮的选择
        InputOutcome resumed = engine.handle(HarnessInput.of(session, null).selectedAgent("helper").resume(true));
        try {
            assertEquals(InputOutcome.Kind.TASK, resumed.getKind());
            Map<String, Object> ctx = optionsOf(resumed).getToolContext();
            assertFalse(ctx.containsKey(SkillTalent.CTX_SKILLS_SELECTED), "skills after reload");
            assertFalse(ctx.containsKey(McpGatewayTalent.CTX_MCPS_SELECTED), "mcps after reload");
        } finally {
            resumed.discard();
        }
    }

    @Test
    void declarationNeedsTheToolAndMustNotBeDisallowed() throws Exception {
        HarnessEngine engine = engine();
        engine.getAgentManager().addAgent(agent("no-tools", Arrays.asList("read"),
                Arrays.asList("alpha"), Arrays.asList("m1")));
        engine.getAgentManager().addAgent(full("full"));
        engine.getAgentManager().addAgent(agent("public", Arrays.asList("*"),
                Arrays.asList("alpha"), Arrays.asList("m1")));
        AgentDefinition denied = full("denied");
        denied.getMetadata().getDisallowedTools().add("mcp");
        engine.getAgentManager().addAgent(denied);

        assertSelection(engine, "no-tools", null, null);
        assertSelection(engine, "full", Arrays.asList("alpha"), Arrays.asList("m1"));
        // * 含 skill、不含 mcp
        assertSelection(engine, "public", Arrays.asList("alpha"), null);
        assertSelection(engine, "denied", Arrays.asList("alpha"), null);

        // 引擎级禁用
        HarnessEngine noSkill = engine(HarnessFixtures.builder(workspace.resolve("ws2"))
                .disallowedToolsAdd(Collections.singletonList("skill")));
        noSkill.getAgentManager().addAgent(full("full"));
        assertSelection(noSkill, "full", null, Arrays.asList("m1"));
    }

    @Test
    void invalidDeclaredNamesAreDropped() throws Exception {
        HarnessEngine engine = engine();
        engine.getAgentManager().addAgent(agent("ghosts", Arrays.asList("skill", "mcp"),
                Arrays.asList("ghost"), Arrays.asList("m-off", "nope")));
        engine.getAgentManager().addAgent(agent("mixed", Arrays.asList("skill", "mcp"),
                Arrays.asList("@skills/beta", "ghost", "alpha"), Arrays.asList("nope", "m1")));

        // 全部失效（不存在 / 已停用）：视为未声明，不能把视野收窄成空
        assertSelection(engine, "ghosts", null, null);
        // 部分失效：只留有效的（完整 id 与短名都认）
        assertSelection(engine, "mixed", Arrays.asList("@skills/beta", "alpha"), Arrays.asList("m1"));
    }

    @Test
    void sessionAgentDeclarationApplies() throws Exception {
        HarnessEngine engine = engine();
        AgentSession session = engine.getSession("s1");
        assertTrue(SessionAgents.putIfAbsent(session,
                agent("temp", Arrays.asList("skill"), Arrays.asList("beta"), null), SessionAgents.MAX_PER_SESSION));

        InputOutcome outcome = engine.handle(HarnessInput.of(session, "hi").selectedAgent("temp"));
        try {
            assertEquals(Arrays.asList("beta"), SkillTalent.getSelected(optionsOf(outcome).getToolContext()));
        } finally {
            outcome.discard();
        }
    }

    // ---------------- fixtures ----------------

    private HarnessEngine engine() throws Exception {
        return engine(HarnessFixtures.builder(workspace));
    }

    private HarnessEngine engine(HarnessEngine.Builder builder) throws Exception {
        Path skills = workspace.resolve("skills");
        writeSkill(skills, "alpha");
        writeSkill(skills, "beta");

        HarnessEngine engine = builder
                .mountAdd(Mount.builder().alias("@skills").type(MountType.SKILLS)
                        .source(FileMountSource.of(skills)).build())
                .build();

        // 一个可用、一个停用的 MCP 服务：都以停用状态注册（只进管理表、不建连），再把 m1 打开
        engine.getMcpGatewayTalent().addMcpServer("m1", stdio());
        engine.getMcpGatewayTalent().addMcpServer("m-off", stdio());
        engine.getMcpServer("m1").setEnabled(true);
        return engine;
    }

    private static void writeSkill(Path root, String name) throws Exception {
        Path dir = Files.createDirectories(root.resolve(name));
        Files.write(dir.resolve("SKILL.md"), ("---\nname: " + name + "\ndescription: " + name + " skill\n---\n\n# "
                + name + "\n\nbody of " + name).getBytes(StandardCharsets.UTF_8));
    }

    private static McpServerParameters stdio() {
        McpServerParameters p = new McpServerParameters();
        p.setType("stdio");
        p.setCommand("soloncode-test-no-such-command");
        p.setEnabled(false);
        return p;
    }

    /**
     * 工具为 {@code **} 的代理（去掉 memory：测试引擎没配记忆提供者，挂 memory 会因记忆才能为空而失败）
     */
    private static AgentDefinition full(String name) {
        AgentDefinition def = agent(name, Arrays.asList("**"), Arrays.asList("alpha"), Arrays.asList("m1"));
        def.getMetadata().getDisallowedTools().add("memory");
        return def;
    }

    private static AgentDefinition agent(String name, List<String> tools, List<String> skills, List<String> mcps) {
        return AgentDefinition.builder()
                .name(name)
                .description(name)
                .toolsAdd(tools)
                .systemPrompt("You are " + name)
                .metadata(md -> {
                    md.setSkills(skills);
                    md.setMcpServers(mcps);
                })
                .build();
    }

    private static void assertSelection(HarnessEngine engine, String agentName, List<String> skills, List<String> mcps)
            throws Exception {
        String label = agentName == null ? "main" : agentName;
        InputOutcome outcome = engine.handle(HarnessInput.of(engine.getSession("s-" + label), "hi")
                .selectedAgent(agentName));
        try {
            Map<String, Object> ctx = optionsOf(outcome).getToolContext();
            if (skills == null) {
                assertFalse(ctx.containsKey(SkillTalent.CTX_SKILLS_SELECTED), label + " skills");
            } else {
                assertEquals(skills, SkillTalent.getSelected(ctx), label + " skills");
            }
            if (mcps == null) {
                assertFalse(ctx.containsKey(McpGatewayTalent.CTX_MCPS_SELECTED), label + " mcps");
            } else {
                assertEquals(mcps, McpGatewayTalent.getSelected(ctx), label + " mcps");
            }
        } finally {
            outcome.discard();
        }
    }

    /**
     * 模拟这一轮已经跑过（同一进程内）：该代理的轨迹带着这一轮初始化后的 options
     */
    private static void ranWith(HarnessEngine engine, AgentSession session, String agentName, InputOutcome outcome)
            throws Exception {
        ReActOptions options = optionsOf(outcome);
        ReActTrace trace = new ReActTrace();
        HarnessFixtures.set(trace, "options", options);
        HarnessFixtures.set(trace, "originalPrompt", outcome.getPrompt());
        session.getContext().put(engine.getAgentOrMain(session, agentName).getConfig().getTraceKey(), trace);
        outcome.discard();
    }

    /**
     * 模拟会话从存储重新加载：轨迹在，上一轮初始化后的 options 没了（transient）
     */
    private static void reloaded(HarnessEngine engine, AgentSession session, String agentName, InputOutcome outcome)
            throws Exception {
        ReActTrace trace = new ReActTrace();
        HarnessFixtures.set(trace, "options", null);
        HarnessFixtures.set(trace, "originalPrompt", outcome.getPrompt());
        session.getContext().put(engine.getAgentOrMain(session, agentName).getConfig().getTraceKey(), trace);
        outcome.discard();
    }

    /**
     * 请求装配后实际生效的 options（与执行时同一条初始化路径）
     */
    private static ReActOptions optionsOf(InputOutcome outcome) throws Exception {
        ReActRequest request = (ReActRequest) outcome.getRequest();
        Method init = ReActRequest.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(request);
        Field f = ReActRequest.class.getDeclaredField("options");
        f.setAccessible(true);
        return (ReActOptions) f.get(request);
    }
}
