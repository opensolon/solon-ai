package org.noear.solon.ai.harness.agent;

import features.ai.harness.i18n.HarnessFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActOptions;
import org.noear.solon.ai.agent.react.ReActOptionsAmend;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.mcp.client.McpServerParameters;
import org.noear.solon.ai.mount.Mount;
import org.noear.solon.ai.mount.MountType;
import org.noear.solon.ai.mount.source.FileMountSource;
import org.noear.solon.ai.talents.cli.SkillTalent;
import org.noear.solon.ai.talents.gateway.McpGatewayTalent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 委派时的视野：子代理自己声明了技能 / MCP 就用自己的，没有才继承父代理本轮的选择。
 *
 * <p>TaskPlan / CallContext 是私有结构，经反射调用真实的 preparePlan 与 applyScope，不复制产品逻辑。</p>
 */
class TaskTalentDeclaredSelectionTest {
    @TempDir
    Path workspace;

    @Test
    void childDeclarationWinsAndUndeclaredChildInherits() throws Throwable {
        HarnessEngine engine = engine();
        engine.getAgentManager().addAgent(agent("declared", Arrays.asList("alpha"), Arrays.asList("m1")));
        engine.getAgentManager().addAgent(agent("half", Arrays.asList("alpha"), null));
        engine.getAgentManager().addAgent(agent("plain", null, null));
        AgentSession session = engine.getSession("s1");

        // 父代理本轮的选择：beta / m2
        ReActTrace parent = HarnessFixtures.trace(null, false);
        parent.getOptions().getToolContext().put(SkillTalent.CTX_SKILLS_SELECTED, Arrays.asList("beta"));
        parent.getOptions().getToolContext().put(McpGatewayTalent.CTX_MCPS_SELECTED, Arrays.asList("m2"));
        Object ctx = HarnessFixtures.callContext(parent);

        assertChild(engine, session, ctx, "declared", Arrays.asList("alpha"), Arrays.asList("m1"));
        // 只声明了技能：MCP 仍继承父代理
        assertChild(engine, session, ctx, "half", Arrays.asList("alpha"), Arrays.asList("m2"));
        assertChild(engine, session, ctx, "plain", Arrays.asList("beta"), Arrays.asList("m2"));
    }

    @Test
    void childDeclarationAppliesWithoutParentSelection() throws Throwable {
        HarnessEngine engine = engine();
        engine.getAgentManager().addAgent(agent("declared", Arrays.asList("alpha"), Arrays.asList("m1")));
        engine.getAgentManager().addAgent(agent("plain", null, null));
        AgentSession session = engine.getSession("s1");

        Object ctx = HarnessFixtures.callContext(HarnessFixtures.trace(null, false));

        assertChild(engine, session, ctx, "declared", Arrays.asList("alpha"), Arrays.asList("m1"));
        assertChild(engine, session, ctx, "plain", null, null);
    }

    @Test
    void sessionAgentDeclarationIsUsedWhenDelegated() throws Throwable {
        HarnessEngine engine = engine();
        AgentSession session = engine.getSession("s1");
        SessionAgents.putIfAbsent(session, agent("temp", Arrays.asList("beta"), null), SessionAgents.MAX_PER_SESSION);

        Object ctx = HarnessFixtures.callContext(HarnessFixtures.trace(null, false));

        assertChild(engine, session, ctx, "temp", Arrays.asList("beta"), null);
    }

    // ---------------- fixtures ----------------

    private static void assertChild(HarnessEngine engine, AgentSession session, Object ctx, String agentName,
                                    List<String> skills, List<String> mcps) throws Throwable {
        TaskTalent.TaskPlan plan = (TaskTalent.TaskPlan) HarnessFixtures.invoke(engine.getTaskTalent(), "preparePlan",
                new Class<?>[]{AgentSession.class, TaskTalent.SingleTaskOp.class, String.class, Locale.class},
                session, HarnessFixtures.taskOp(agentName, 1), null, null);
        assertNull(plan.invalidReason, agentName);

        ReActOptions child = new ReActOptions((ChatModel) null);
        HarnessFixtures.invoke(ctx, "applyScope", new Class<?>[]{ReActOptionsAmend.class, List.class, List.class},
                new ReActOptionsAmend(child), HarnessFixtures.get(plan, "ownSkills"), HarnessFixtures.get(plan, "ownMcps"));

        Map<String, Object> toolContext = child.getToolContext();
        if (skills == null) {
            assertFalse(toolContext.containsKey(SkillTalent.CTX_SKILLS_SELECTED), agentName + " skills");
        } else {
            assertEquals(skills, SkillTalent.getSelected(toolContext), agentName + " skills");
        }
        if (mcps == null) {
            assertFalse(toolContext.containsKey(McpGatewayTalent.CTX_MCPS_SELECTED), agentName + " mcps");
        } else {
            assertEquals(mcps, McpGatewayTalent.getSelected(toolContext), agentName + " mcps");
        }
    }

    private HarnessEngine engine() throws Exception {
        Path skills = workspace.resolve("skills");
        for (String name : new String[]{"alpha", "beta"}) {
            Path dir = Files.createDirectories(skills.resolve(name));
            Files.write(dir.resolve("SKILL.md"), ("---\nname: " + name + "\ndescription: " + name
                    + "\n---\n\nbody").getBytes(StandardCharsets.UTF_8));
        }

        HarnessEngine engine = HarnessFixtures.builder(workspace)
                .mountAdd(Mount.builder().alias("@skills").type(MountType.SKILLS)
                        .source(FileMountSource.of(skills)).build())
                .build();

        // 以停用状态注册（只进管理表、不建连），再打开
        for (String name : new String[]{"m1", "m2"}) {
            McpServerParameters p = new McpServerParameters();
            p.setType("stdio");
            p.setCommand("soloncode-test-no-such-command");
            p.setEnabled(false);
            engine.getMcpGatewayTalent().addMcpServer(name, p);
            engine.getMcpServer(name).setEnabled(true);
        }
        return engine;
    }

    private static AgentDefinition agent(String name, List<String> skills, List<String> mcps) {
        return AgentDefinition.builder()
                .name(name)
                .description(name)
                .toolsAdd("read", "skill", "mcp")
                .systemPrompt("You are " + name)
                .metadata(md -> {
                    md.setSkills(skills);
                    md.setMcpServers(mcps);
                })
                .build();
    }
}
