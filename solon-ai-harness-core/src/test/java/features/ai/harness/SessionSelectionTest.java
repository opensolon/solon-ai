package features.ai.harness;

import features.ai.harness.i18n.HarnessFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActOptions;
import org.noear.solon.ai.agent.react.ReActRequest;
import org.noear.solon.ai.agent.session.FileAgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.agent.AgentDefinition;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话级技能 / MCP 视野：随会话固定（像模型选择一样），每轮新消息自动回退到这里。
 *
 * <p>优先级：输入显式选择 → 会话选择 → 目标智能体的声明 → 不限制。</p>
 */
class SessionSelectionTest {
    @TempDir
    Path workspace;

    @Test
    void sessionSelectionAppliesToEveryNewTurn() throws Exception {
        HarnessEngine engine = engine();
        AgentSession session = engine.getSession("s1");
        engine.setSessionSkills(session, Arrays.asList("alpha"));
        engine.setSessionMcps(session, Arrays.asList("m1"));

        // 主代理没有声明：能固定住完全靠会话级选择
        ranWith(engine, session, null, engine.handle(HarnessInput.of(session, "one")));
        assertSelection(engine, session, null, Arrays.asList("alpha"), Arrays.asList("m1"));

        // 第二轮不传任何选择，仍然生效
        assertSelection(engine, session, null, Arrays.asList("alpha"), Arrays.asList("m1"));
    }

    @Test
    void explicitInputWinsOverSessionSelection() throws Exception {
        HarnessEngine engine = engine();
        AgentSession session = engine.getSession("s1");
        engine.setSessionSkills(session, Arrays.asList("alpha"));
        engine.setSessionMcps(session, Arrays.asList("m1"));

        InputOutcome outcome = engine.handle(HarnessInput.of(session, "hi").selectedSkill("beta"));
        try {
            Map<String, Object> ctx = optionsOf(outcome).getToolContext();
            assertEquals(Arrays.asList("beta"), SkillTalent.getSelected(ctx));
            // 只显式选了技能：MCP 仍按会话选择
            assertEquals(Arrays.asList("m1"), McpGatewayTalent.getSelected(ctx));
        } finally {
            outcome.discard();
        }
    }

    @Test
    void sessionSelectionWinsOverAgentDeclaration() throws Exception {
        HarnessEngine engine = engine();
        engine.getAgentManager().addAgent(agent("helper", Arrays.asList("skill", "mcp"),
                Arrays.asList("beta"), Arrays.asList("m1")));
        AgentSession session = engine.getSession("s1");
        engine.setSessionSkills(session, Arrays.asList("alpha"));

        InputOutcome outcome = engine.handle(HarnessInput.of(session, "hi").selectedAgent("helper"));
        try {
            Map<String, Object> ctx = optionsOf(outcome).getToolContext();
            assertEquals(Arrays.asList("alpha"), SkillTalent.getSelected(ctx));
            // 会话没选 MCP：回退声明
            assertEquals(Arrays.asList("m1"), McpGatewayTalent.getSelected(ctx));
        } finally {
            outcome.discard();
        }
    }

    @Test
    void clearedSessionSelectionFallsBackToDeclaration() throws Exception {
        HarnessEngine engine = engine();
        engine.getAgentManager().addAgent(agent("helper", Arrays.asList("skill", "mcp"),
                Arrays.asList("beta"), Arrays.asList("m1")));
        AgentSession session = engine.getSession("s1");
        engine.setSessionSkills(session, Arrays.asList("alpha"));
        engine.setSessionMcps(session, Arrays.asList("m1"));

        // 不传与传空都表示清除（与模型、子代理同一口径）
        engine.setSessionSkills(session, null);
        engine.setSessionMcps(session, Collections.<String>emptyList());
        assertNull(engine.getSessionSkills(session));
        assertNull(engine.getSessionMcps(session));

        InputOutcome outcome = engine.handle(HarnessInput.of(session, "hi").selectedAgent("helper"));
        try {
            Map<String, Object> ctx = optionsOf(outcome).getToolContext();
            assertEquals(Arrays.asList("beta"), SkillTalent.getSelected(ctx));
            assertEquals(Arrays.asList("m1"), McpGatewayTalent.getSelected(ctx));
        } finally {
            outcome.discard();
        }
    }

    @Test
    void invalidSessionSelectionIsDroppedWithoutNarrowingToNothing() throws Exception {
        HarnessEngine engine = engine();
        engine.getAgentManager().addAgent(agent("helper", Arrays.asList("skill", "mcp"),
                Arrays.asList("beta"), Arrays.asList("m1")));

        AgentSession mixed = engine.getSession("mixed");
        engine.setSessionSkills(mixed, Arrays.asList("ghost", "alpha"));
        engine.setSessionMcps(mixed, Arrays.asList("nope", "m1"));
        assertSelection(engine, mixed, "helper", Arrays.asList("alpha"), Arrays.asList("m1"));

        // 全部失效：不限制（也不能收窄成空），且不回退智能体声明
        AgentSession ghosts = engine.getSession("ghosts");
        engine.setSessionSkills(ghosts, Arrays.asList("ghost"));
        engine.setSessionMcps(ghosts, Arrays.asList("m-off", "nope"));
        assertSelection(engine, ghosts, "helper", null, null);
    }

    @Test
    void sessionSelectionSurvivesSnapshotReload() throws Exception {
        Path dir = workspace.resolve("session-store");
        FileAgentSession first = new FileAgentSession("persisted", dir.toString());
        HarnessEngine engine = engine();
        engine.setSessionSkills(first, Arrays.asList("alpha", "@skills/beta"));
        engine.setSessionMcps(first, Arrays.asList("m1"));
        first.updateSnapshot();

        // 重新加载：读到的是落盘的清单，顺序与内容都不变
        FileAgentSession reloaded = new FileAgentSession("persisted", dir.toString());
        assertEquals(Arrays.asList("alpha", "@skills/beta"), engine.getSessionSkills(reloaded));
        assertEquals(Arrays.asList("m1"), engine.getSessionMcps(reloaded));

        // 清空后落盘，重载仍是未设置
        engine.setSessionSkills(reloaded, null);
        engine.setSessionMcps(reloaded, null);
        reloaded.updateSnapshot();
        FileAgentSession cleared = new FileAgentSession("persisted", dir.toString());
        assertNull(engine.getSessionSkills(cleared));
        assertNull(engine.getSessionMcps(cleared));
    }

    // ---------------- fixtures ----------------

    private HarnessEngine engine() throws Exception {
        Path skills = workspace.resolve("skills");
        writeSkill(skills, "alpha");
        writeSkill(skills, "beta");

        HarnessEngine engine = HarnessFixtures.builder(workspace)
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

    private static void assertSelection(HarnessEngine engine, AgentSession session, String agentName,
                                        List<String> skills, List<String> mcps) throws Exception {
        InputOutcome outcome = engine.handle(HarnessInput.of(session, "hi").selectedAgent(agentName));
        try {
            Map<String, Object> ctx = optionsOf(outcome).getToolContext();
            if (skills == null) {
                assertFalse(ctx.containsKey(SkillTalent.CTX_SKILLS_SELECTED), "skills");
            } else {
                assertEquals(skills, SkillTalent.getSelected(ctx), "skills");
            }
            if (mcps == null) {
                assertFalse(ctx.containsKey(McpGatewayTalent.CTX_MCPS_SELECTED), "mcps");
            } else {
                assertEquals(mcps, McpGatewayTalent.getSelected(ctx), "mcps");
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
        org.noear.solon.ai.agent.react.ReActTrace trace = new org.noear.solon.ai.agent.react.ReActTrace();
        HarnessFixtures.set(trace, "options", options);
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
