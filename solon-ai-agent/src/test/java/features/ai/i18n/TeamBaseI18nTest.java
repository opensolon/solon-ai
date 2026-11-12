package features.ai.i18n;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentProfile;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamAgentConfig;
import org.noear.solon.ai.agent.team.TeamProtocol;
import org.noear.solon.ai.agent.team.TeamProtocolFactory;
import org.noear.solon.ai.agent.team.TeamProtocols;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.agent.team.protocol.A2AProtocol;
import org.noear.solon.ai.agent.team.protocol.SwarmProtocol;
import org.noear.solon.ai.agent.team.task.SupervisorTask;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.flow.FlowContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Team 协议基类 / A2A / Swarm / Sequential / SupervisorTask / AgentProfile 的国际化检查
 *
 * <p>英文下所有面向模型的文案（含运行时反馈）不得含中文；中文下运行时文案保持原样。</p>
 */
public class TeamBaseI18nTest {
    static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fa5]");

    static void assertNoChinese(String where, String text) {
        Assertions.assertNotNull(text, where);
        Assertions.assertFalse(CJK.matcher(text).find(), where + " contains chinese:\n" + text);
    }

    static class Ctx {
        TeamAgent team;
        TeamTrace trace;
        FlowContext ctx;
        TeamProtocol protocol;

        Agent agent(String name) {
            return team.getConfig().getAgentMap().get(name);
        }
    }

    public static Ctx build(TeamProtocolFactory factory, Locale locale) throws Exception {
        Ctx c = new Ctx();
        c.team = TeamAgent.of(null).name("i18n_base")
                .protocol(factory)
                .finishMarker("[FINISH]")
                .locale(locale)
                .agentAdd(PromptGoldenTest.agent("coder", new AgentProfile().capabilityAdd("java")),
                        PromptGoldenTest.agent("reviewer", new AgentProfile().capabilityAdd("review")))
                .build();

        c.trace = PromptGoldenTest.prepareTeam(c.team, Prompt.of("write a hello api"));
        c.trace.addRecord(ChatRole.ASSISTANT, "coder", "draft v1 done", 10L);
        c.trace.setLastAgentName("coder");

        c.ctx = FlowContext.of();
        c.ctx.put(c.team.getConfig().getTraceKey(), c.trace);
        c.ctx.put(Agent.KEY_CURRENT_TEAM_TRACE_KEY, c.team.getConfig().getTraceKey());
        c.protocol = c.team.getConfig().getProtocol();
        return c;
    }

    /**
     * 注入提示词、主管指令、工具描述在英文下不含中文
     */
    static void assertPromptsEnglish(String name, Ctx c) throws Throwable {
        Agent reviewer = c.agent("reviewer");

        StringBuilder sb = new StringBuilder();
        c.protocol.injectAgentInstruction(c.ctx, reviewer, Locale.ENGLISH, sb);
        assertNoChinese(name + ".injectAgentInstruction", sb.toString());

        Prompt prepared = c.protocol.prepareAgentPrompt(c.trace, reviewer, Prompt.of(ChatMessage.ofUser("continue")), Locale.ENGLISH);
        for (ChatMessage m : prepared.getMessages()) {
            assertNoChinese(name + ".prepareAgentPrompt", m.getContent());
        }

        sb = new StringBuilder();
        c.protocol.injectSupervisorInstruction(Locale.ENGLISH, sb);
        assertNoChinese(name + ".injectSupervisorInstruction", sb.toString());

        sb = new StringBuilder();
        c.protocol.prepareSupervisorInstruction(c.ctx, c.trace, sb);
        assertNoChinese(name + ".prepareSupervisorInstruction", sb.toString());

        sb = new StringBuilder();
        c.protocol.prepareSupervisorContext(c.ctx, c.trace, sb);
        assertNoChinese(name + ".prepareSupervisorContext", sb.toString());

        assertNoChinese(name + ".systemPrompt", c.trace.getOptions().getSystemPromptFor(c.trace, null));

        for (FunctionTool t : tools(c, reviewer)) {
            assertNoChinese(name + ".tool.title", t.title());
            assertNoChinese(name + ".tool.description", t.description());
            assertNoChinese(name + ".tool.schema", t.inputSchema());
        }
    }

    static List<FunctionTool> tools(Ctx c, Agent agent) {
        List<FunctionTool> tools = new ArrayList<>();
        c.protocol.injectSupervisorTools(c.ctx, tools::add);
        c.protocol.injectAgentTools(c.ctx, agent, tools::add);
        return tools;
    }

    static FunctionTool tool(Ctx c, Agent agent, String toolName) {
        for (FunctionTool t : tools(c, agent)) {
            if (toolName.equals(t.name())) {
                return t;
            }
        }
        Assertions.fail("tool not found: " + toolName);
        return null;
    }

    // ---------------- A2A ----------------

    @Test
    public void testA2AEnglish() throws Throwable {
        Ctx c = build(TeamProtocols.A2A, Locale.ENGLISH);
        A2AProtocol a2a = (A2AProtocol) c.protocol;

        // 有全局状态 / payload 时也需覆盖看板与断面
        Map<String, Object> args = new HashMap<>();
        args.put("target", "ghost");
        args.put("state", "{\"step\":1}");
        args.put("memo", "memo-x");
        Object ret = tool(c, c.agent("reviewer"), "__transfer_to__").handle(args);
        Assertions.assertEquals("Transfer to [ghost] requested.", String.valueOf(ret));

        assertPromptsEnglish("a2a", c);

        // 移交失败反馈（写入 trace 记录）
        String route = a2a.resolveSupervisorRoute(c.ctx, c.trace, "anything");
        Assertions.assertEquals("coder", route);
        String feedback = lastRecord(c.trace);
        Assertions.assertEquals("[System] Transfer failed: Expert [ghost] is invalid or lacks required capabilities. Please re-select or resolve yourself.", feedback);

        // 熔断：最大流转次数
        A2AProtocol.A2AState state = a2a.getA2AState(c.trace);
        for (int i = 0; i < 5; i++) {
            state.confirmTransfer();
        }
        state.setTransferRequest("reviewer", null, "memo");
        Assertions.assertNull(a2a.resolveSupervisorRoute(c.ctx, c.trace, "anything"));
        Assertions.assertEquals("Max transfer limit reached.", c.trace.getFinalAnswer());
    }

    @Test
    public void testA2AChinese() throws Throwable {
        Ctx c = build(TeamProtocols.A2A, Locale.CHINA);
        A2AProtocol a2a = (A2AProtocol) c.protocol;

        Map<String, Object> args = new HashMap<>();
        args.put("target", "ghost");
        Object ret = tool(c, c.agent("reviewer"), "__transfer_to__").handle(args);
        Assertions.assertEquals("已发起向 [ghost] 的接力请求。", String.valueOf(ret));

        a2a.resolveSupervisorRoute(c.ctx, c.trace, "anything");
        Assertions.assertEquals("【系统通知】移交失败：专家 [ghost] 不存在或能力不匹配（如无法处理图片）。请重新选择或尝试自行解决。", lastRecord(c.trace));

        A2AProtocol.A2AState state = a2a.getA2AState(c.trace);
        for (int i = 0; i < 5; i++) {
            state.confirmTransfer();
        }
        state.setTransferRequest("reviewer", null, "memo");
        a2a.resolveSupervisorRoute(c.ctx, c.trace, "anything");
        Assertions.assertEquals("协作达到最大流转次数，任务中止。", c.trace.getFinalAnswer());
    }

    // ---------------- Swarm ----------------

    @Test
    public void testSwarmEnglish() throws Throwable {
        Ctx c = build(TeamProtocols.SWARM, Locale.ENGLISH);

        Map<String, Object> args = new HashMap<>();
        args.put("tasks", "write tests;review code");
        args.put("agents", "coder;reviewer");
        Object ret = tool(c, c.agent("reviewer"), "__emerge_tasks__").handle(args);
        Assertions.assertEquals("System: Tasks added to pool.", String.valueOf(ret));

        // 任务池非空：覆盖任务池快照与“推荐下一执行者”
        SwarmProtocol swarm = (SwarmProtocol) c.protocol;
        Assertions.assertFalse(swarm.getSwarmState(c.trace).getTaskPool().isEmpty());

        assertPromptsEnglish("swarm", c);

        Prompt prepared = c.protocol.prepareAgentPrompt(c.trace, c.agent("reviewer"), Prompt.of(ChatMessage.ofUser("continue")), Locale.ENGLISH);
        String last = prepared.getMessages().get(prepared.getMessages().size() - 1).getContent();
        Assertions.assertTrue(last.startsWith("[Swarm Task Pool Snapshot]\n```json\n"), last);

        StringBuilder sb = new StringBuilder();
        c.protocol.prepareSupervisorInstruction(c.ctx, c.trace, sb);
        Assertions.assertTrue(sb.toString().contains("> 4. **Recommended next**: `"), sb.toString());
    }

    @Test
    public void testSwarmChinese() throws Throwable {
        Ctx c = build(TeamProtocols.SWARM, Locale.CHINA);

        Map<String, Object> args = new HashMap<>();
        args.put("tasks", "写测试");
        args.put("agents", "reviewer");
        Object ret = tool(c, c.agent("reviewer"), "__emerge_tasks__").handle(args);
        Assertions.assertEquals("系统：子任务已加入任务池。", String.valueOf(ret));

        Prompt prepared = c.protocol.prepareAgentPrompt(c.trace, c.agent("reviewer"), Prompt.of(ChatMessage.ofUser("continue")), Locale.CHINA);
        String last = prepared.getMessages().get(prepared.getMessages().size() - 1).getContent();
        Assertions.assertTrue(last.startsWith("【蜂群任务池快照】\n```json\n"), last);

        StringBuilder sb = new StringBuilder();
        c.protocol.prepareSupervisorInstruction(c.ctx, c.trace, sb);
        Assertions.assertTrue(sb.toString().contains("> 4. **推荐下一执行者**：`reviewer`\n"), sb.toString());
    }

    // ---------------- Sequential / Base ----------------

    @Test
    public void testSequentialEnglish() throws Throwable {
        assertPromptsEnglish("sequential", build(TeamProtocols.SEQUENTIAL, Locale.ENGLISH));
    }

    @Test
    public void testUnsupportedLocaleFallbackEnglish() throws Throwable {
        assertPromptsEnglish("a2a-ja", build(TeamProtocols.A2A, Locale.JAPANESE));
        assertPromptsEnglish("swarm-ja", build(TeamProtocols.SWARM, Locale.JAPANESE));
    }

    // ---------------- SupervisorTask ----------------

    static class SupervisorTaskProbe extends SupervisorTask {
        SupervisorTaskProbe(TeamAgentConfig config) {
            super(config);
        }

        String userContent(FlowContext context, TeamTrace trace) {
            return buildSupervisorUserContent(context, trace);
        }
    }

    @Test
    public void testSupervisorUserContent() throws Throwable {
        Ctx en = build(TeamProtocols.SEQUENTIAL, Locale.ENGLISH);
        String enText = new SupervisorTaskProbe(en.team.getConfig()).userContent(en.ctx, en.trace);
        assertNoChinese("supervisor.userContent", enText);
        Assertions.assertTrue(enText.contains("Command: Assign next agent or output [FINISH] to finish."), enText);
        Assertions.assertTrue(enText.endsWith("\n> **Pending Experts**: [reviewer]."), enText);

        Ctx zh = build(TeamProtocols.SEQUENTIAL, Locale.CHINA);
        String zhText = new SupervisorTaskProbe(zh.team.getConfig()).userContent(zh.ctx, zh.trace);
        int window = zh.trace.getOptions().getRecordWindowSize();
        Assertions.assertTrue(zhText.contains("## 协作进度 (最近 " + window + " 轮历史)\n"), zhText);
        Assertions.assertTrue(zhText.contains("---\n当前迭代轮次: 1\n指令：请指派下一位执行者。已完成则输出 [FINISH]"), zhText);
        Assertions.assertTrue(zhText.endsWith("\n> **待命专家**：[reviewer]."), zhText);
    }

    // ---------------- AgentProfile ----------------

    @Test
    public void testAgentProfile() {
        AgentProfile profile = new AgentProfile()
                .capabilityAdd("java")
                .constraintAdd("no secrets")
                .style("concise")
                .modeAdd("image", "image");

        String en = profile.toFormatString(Locale.ENGLISH);
        assertNoChinese("profile", en);
        Assertions.assertTrue(en.startsWith("Input Modes: text, image | Outputs: Images | Capabilities: "), en);
        Assertions.assertTrue(en.endsWith(" | Constraints: no secrets | Style: concise"), en);

        String zh = profile.toFormatString(Locale.CHINESE);
        Assertions.assertTrue(zh.startsWith("输入模态: text, image | 产出: 包含图像 | 核心能力: "), zh);
        Assertions.assertTrue(zh.endsWith(" | 行为约束: no secrets | 交互风格: concise"), zh);

        Assertions.assertEquals("Input: Text only | Capabilities: assistant", new AgentProfile().toFormatString(Locale.US));
        Assertions.assertEquals("输入限制: 仅限文本 | 核心能力: assistant", new AgentProfile().toFormatString(Locale.SIMPLIFIED_CHINESE));
    }

    static String lastRecord(TeamTrace trace) {
        List<TeamTrace.TeamRecord> records = trace.getRecords();
        return records.get(records.size() - 1).getContent();
    }
}
