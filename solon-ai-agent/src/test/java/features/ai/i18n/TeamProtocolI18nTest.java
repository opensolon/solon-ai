package features.ai.i18n;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentProfile;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamProtocol;
import org.noear.solon.ai.agent.team.TeamProtocolFactory;
import org.noear.solon.ai.agent.team.TeamProtocols;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.agent.team.protocol.BlackboardProtocol;
import org.noear.solon.ai.agent.team.protocol.ContractNetProtocol;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.content.ImageBlock;
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
 * Hierarchical / Blackboard / ContractNet / MarketBased 协议国际化测试（不依赖 LLM）
 *
 * <p>英文 locale 下，所有面向模型的提示词、工具描述与运行时反馈不应含中文；
 * 依赖文案的逻辑关键词（招标 / 完成）需同时识别中英文。</p>
 */
public class TeamProtocolI18nTest {
    static final Pattern CJK = Pattern.compile("[\u4e00-\u9fa5]");

    static void assertNoChinese(String where, String text) {
        Assertions.assertNotNull(text, where);
        Assertions.assertFalse(CJK.matcher(text).find(), where + " contains Chinese:\n" + text);
    }

    static class Ctx {
        TeamAgent team;
        TeamTrace trace;
        FlowContext flow;
        TeamProtocol protocol;
        Agent coder;
        Agent reviewer;
    }

    static Ctx build(TeamProtocolFactory factory, Locale locale, Prompt prompt) throws Exception {
        Ctx c = new Ctx();
        c.team = TeamAgent.of(null).name("i18n_team")
                .protocol(factory)
                .finishMarker("[FINISH]")
                .agentAdd(PromptGoldenTest.agent("coder", new AgentProfile().capabilityAdd("java")),
                        PromptGoldenTest.agent("reviewer", new AgentProfile().capabilityAdd("review")))
                .locale(locale)
                .build();
        c.trace = PromptGoldenTest.prepareTeam(c.team, prompt);
        c.flow = FlowContext.of();
        c.flow.put(c.team.getConfig().getTraceKey(), c.trace);
        c.flow.put(Agent.KEY_CURRENT_TEAM_TRACE_KEY, c.team.getConfig().getTraceKey());
        c.protocol = c.team.getConfig().getProtocol();
        c.coder = c.team.getConfig().getAgentMap().get("coder");
        c.reviewer = c.team.getConfig().getAgentMap().get("reviewer");
        return c;
    }

    static Ctx build(TeamProtocolFactory factory, Locale locale) throws Exception {
        return build(factory, locale, Prompt.of("write a hello api"));
    }

    static String supInstr(Ctx c) {
        StringBuilder sb = new StringBuilder();
        c.protocol.prepareSupervisorInstruction(c.flow, c.trace, sb);
        return sb.toString();
    }

    static String supCtx(Ctx c) {
        StringBuilder sb = new StringBuilder();
        c.protocol.prepareSupervisorContext(c.flow, c.trace, sb);
        return sb.toString();
    }

    static String agentInstr(Ctx c, Agent agent, Locale locale) {
        StringBuilder sb = new StringBuilder();
        c.protocol.injectAgentInstruction(c.flow, agent, locale, sb);
        return sb.toString();
    }

    static String agentPrompt(Ctx c, Agent agent, Prompt wm, Locale locale) {
        Prompt prepared = c.protocol.prepareAgentPrompt(c.trace, agent, wm, locale);
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : prepared.getMessages()) {
            sb.append("[").append(m.getRole()).append("] ").append(m.getContent()).append("\n");
        }
        return sb.toString();
    }

    static List<FunctionTool> supervisorTools(Ctx c) {
        List<FunctionTool> tools = new ArrayList<>();
        c.protocol.injectSupervisorTools(c.flow, tools::add);
        return tools;
    }

    static List<FunctionTool> agentTools(Ctx c, Agent agent) {
        List<FunctionTool> tools = new ArrayList<>();
        c.protocol.injectAgentTools(c.flow, agent, tools::add);
        return tools;
    }

    static String describe(List<FunctionTool> tools) {
        StringBuilder sb = new StringBuilder();
        for (FunctionTool t : tools) {
            sb.append(t.name()).append(": ").append(t.description()).append("\n").append(t.inputSchema()).append("\n");
        }
        return sb.toString();
    }

    static String records(TeamTrace trace) {
        StringBuilder sb = new StringBuilder();
        for (TeamTrace.TeamRecord r : trace.getRecords()) {
            sb.append(r.getSource()).append(": ").append(r.getContent()).append("\n");
        }
        return sb.toString();
    }

    static void agentRound(Ctx c, Agent agent, String content) {
        c.trace.addRecord(ChatRole.ASSISTANT, agent.name(), content, 10L);
        c.trace.setLastAgentName(agent.name());
        c.protocol.onAgentEnd(c.trace, agent);
    }

    // ---------------- Hierarchical ----------------

    @Test
    public void hierarchical_en() throws Exception {
        Prompt multi = Prompt.of(ChatMessage.ofUser("please describe", ImageBlock.ofUrl("https://example.com/a.png")));
        Ctx c = build(TeamProtocols.HIERARCHICAL, Locale.ENGLISH, multi);

        // 首轮（无汇报）
        assertNoChinese("sup_instr(empty)", supInstr(c));

        // 三轮失败：产生 errors + 过载告警
        for (int i = 0; i < 3; i++) {
            agentRound(c, c.coder, "step " + i + " FAILED");
        }

        String instr = supInstr(c);
        Assertions.assertTrue(instr.contains("Risk Alert"), instr);
        Assertions.assertTrue(instr.contains("`[FINISH]`"), instr);
        assertNoChinese("sup_instr", instr);
        assertNoChinese("sup_ctx", supCtx(c));
        assertNoChinese("agent_instr", agentInstr(c, c.coder, Locale.ENGLISH));

        // finish 被拦截：系统反馈写入 trace
        String route = c.protocol.resolveSupervisorRoute(c.flow, c.trace, "[FINISH] all good");
        Assertions.assertEquals("coder", route);
        String recs = records(c.trace);
        Assertions.assertTrue(recs.contains("[System] Task cannot end"), recs);
        assertNoChinese("records", recs);

        // 专家提示：前置背景 + 主管指令 + 错误修正 + 多模态
        String prompt = agentPrompt(c, c.coder, Prompt.of(ChatMessage.ofUser("please describe", ImageBlock.ofUrl("https://example.com/a.png"))), Locale.ENGLISH);
        Assertions.assertTrue(prompt.contains("### Pre-Context"), prompt);
        Assertions.assertTrue(prompt.contains("### Supervisor Directive"), prompt);
        Assertions.assertTrue(prompt.contains("### Error Correction Required"), prompt);
        Assertions.assertTrue(prompt.contains("[IMPORTANT]"), prompt);
        assertNoChinese("agent_prompt", prompt);

        assertNoChinese("tools", describe(supervisorTools(c)) + describe(agentTools(c, c.coder)));
    }

    @Test
    public void hierarchical_zh_runtime() throws Exception {
        Ctx c = build(TeamProtocols.HIERARCHICAL, Locale.CHINESE);
        agentRound(c, c.coder, "FAILED");
        Assertions.assertEquals("coder", c.protocol.resolveSupervisorRoute(c.flow, c.trace, "[FINISH]"));
        Assertions.assertTrue(records(c.trace).contains("【系统干预】由于看板中仍有未解决的错误，任务不能结束。请指派相关专家修复。"));
    }

    // ---------------- Blackboard ----------------

    @Test
    public void blackboard_en() throws Throwable {
        Ctx c = build(TeamProtocols.BLACKBOARD, Locale.ENGLISH);

        assertNoChinese("sup_instr(empty)", supInstr(c));
        assertNoChinese("agent_instr", agentInstr(c, c.reviewer, Locale.ENGLISH));

        List<FunctionTool> tools = agentTools(c, c.reviewer);
        Assertions.assertEquals(1, tools.size());
        assertNoChinese("tools", describe(supervisorTools(c)) + describe(tools));

        Map<String, Object> args = new HashMap<>();
        args.put("result", "api drafted");
        args.put("todo", "write docs; add tests");
        Object ret = tools.get(0).handle(args);
        Assertions.assertEquals("System: Blackboard updated. Subsequent experts will proceed based on this state.", ret);

        agentRound(c, c.reviewer, "review ok");

        String prompt = agentPrompt(c, c.reviewer, Prompt.of(ChatMessage.ofUser("continue")), Locale.ENGLISH);
        Assertions.assertTrue(prompt.contains("[Blackboard Snapshot]"), prompt);
        assertNoChinese("agent_prompt", prompt);

        String instr = supInstr(c);
        Assertions.assertTrue(instr.contains("### Global Blackboard"), instr);
        assertNoChinese("sup_instr", instr);
        assertNoChinese("sup_ctx", supCtx(c));
    }

    @Test
    public void blackboard_completedKeywords() {
        Assertions.assertTrue(BlackboardProtocol.BoardState.hasCompletedKeyword("已完成接口文档"));
        Assertions.assertTrue(BlackboardProtocol.BoardState.hasCompletedKeyword("DONE - write docs"));
        Assertions.assertTrue(BlackboardProtocol.BoardState.hasCompletedKeyword("Completed: write docs"));
        Assertions.assertTrue(BlackboardProtocol.BoardState.hasCompletedKeyword("Finished write docs"));
        Assertions.assertFalse(BlackboardProtocol.BoardState.hasCompletedKeyword("still pending"));
        Assertions.assertFalse(BlackboardProtocol.BoardState.hasCompletedKeyword(null));

        // 中文
        BlackboardProtocol.BoardState zh = new BlackboardProtocol.BoardState();
        zh.addDirect("coder", null, "完善接口文档;补充测试");
        zh.completeMatchingTodos("完成了完善接口文档");
        Assertions.assertFalse(zh.todos.contains("完善接口文档"));
        Assertions.assertTrue(zh.todos.contains("补充测试"));

        // 英文（忽略大小写）
        BlackboardProtocol.BoardState en = new BlackboardProtocol.BoardState();
        en.addDirect("coder", null, "Write Docs, add tests");
        en.completeMatchingTodos("FINISHED: write docs");
        Assertions.assertFalse(en.todos.contains("Write Docs"));
        Assertions.assertTrue(en.todos.contains("add tests"));
    }

    // ---------------- ContractNet ----------------

    @Test
    public void contractNet_en() throws Throwable {
        Ctx c = build(TeamProtocols.CONTRACT_NET, Locale.ENGLISH);

        assertNoChinese("sup_instr(waiting)", supInstr(c));
        assertNoChinese("sup_ctx", supCtx(c));
        assertNoChinese("agent_instr", agentInstr(c, c.coder, Locale.ENGLISH));

        // 主管招标工具
        List<FunctionTool> supTools = supervisorTools(c);
        Assertions.assertEquals(1, supTools.size());
        assertNoChinese("sup_tools", describe(supTools));
        Map<String, Object> req = new HashMap<>();
        req.put("requirement", "java api");
        Assertions.assertEquals("Bidding phase initiated.", supTools.get(0).handle(req));
        Assertions.assertEquals(ContractNetProtocol.ID_BIDDING, c.trace.getRoute());

        // 专家投标工具（route=bidding 时注入）
        List<FunctionTool> bidTools = agentTools(c, c.coder);
        Assertions.assertEquals(1, bidTools.size());
        assertNoChinese("bid_tools", describe(bidTools));
        Map<String, Object> bid = new HashMap<>();
        bid.put("score", 80);
        bid.put("plan", "");
        Assertions.assertEquals("Bid rejected: plan is required.", bidTools.get(0).handle(bid));
        bid.put("plan", "implement the api");
        Assertions.assertEquals("Bid received.", bidTools.get(0).handle(bid));

        String instr = supInstr(c);
        Assertions.assertTrue(instr.contains("Remaining bidding rounds: 1"), instr);
        assertNoChinese("sup_instr(bids)", instr);

        // 定标 + 中标执行指令
        c.trace.setRoute(TeamAgent.ID_SUPERVISOR);
        Assertions.assertEquals("coder", c.protocol.resolveSupervisorRoute(c.flow, c.trace, "coder"));
        String prompt = agentPrompt(c, c.coder, Prompt.of(ChatMessage.ofUser("continue")), Locale.ENGLISH);
        Assertions.assertTrue(prompt.contains("[Award Execution Order]"), prompt);
        assertNoChinese("agent_prompt", prompt);

        // 未投标指派：系统反馈（并触发第 2 轮招标）
        Assertions.assertEquals(ContractNetProtocol.ID_BIDDING, c.protocol.resolveSupervisorRoute(c.flow, c.trace, "reviewer"));
        String recs = records(c.trace);
        Assertions.assertTrue(recs.contains("[System] Invalid assignment."), recs);

        // 招标耗尽且无标：最终警告
        String finalInstr = supInstr(c);
        Assertions.assertTrue(finalInstr.contains("[Final Warning]"), finalInstr);
        assertNoChinese("sup_instr(final)", finalInstr);

        // 路由守卫：无法定标
        c.trace.setRoute("reviewer");
        c.protocol.onSupervisorRouting(c.flow, c.trace, "reviewer");
        Assertions.assertEquals(Agent.ID_END, c.trace.getRoute());
        Assertions.assertEquals("Cannot award: target has no bid and bidding rounds are exhausted.", c.trace.getFinalAnswer());

        assertNoChinese("records", records(c.trace));

        // 次数耗尽时招标工具反馈
        c.trace.setRoute(TeamAgent.ID_SUPERVISOR);
        Assertions.assertEquals("Bidding rounds exhausted. Award an existing bid or inform the user.", supTools.get(0).handle(req));
    }

    @Test
    public void contractNet_zh_runtime() throws Throwable {
        Ctx c = build(TeamProtocols.CONTRACT_NET, Locale.CHINESE);
        Assertions.assertEquals(ContractNetProtocol.ID_BIDDING, c.protocol.resolveSupervisorRoute(c.flow, c.trace, "reviewer"));
        Assertions.assertTrue(records(c.trace).contains("【系统指引】指派无效。必须先调用 __call_for_bids__ 获取标书，且只能指派已出现在 bids_board 中的专家。"));
    }

    @Test
    public void contractNet_biddingKeywords() throws Exception {
        String[] bidding = {"开始招标", "发起招标吧", "Let us start BIDDING", "please call for bids",
                "Call-For-Bids now", "CALL_FOR_BIDS", "__call_for_bids__"};
        for (String decision : bidding) {
            for (Locale locale : new Locale[]{Locale.CHINESE, Locale.ENGLISH}) {
                Ctx c = build(TeamProtocols.CONTRACT_NET, locale);
                Assertions.assertEquals(ContractNetProtocol.ID_BIDDING,
                        c.protocol.resolveSupervisorRoute(c.flow, c.trace, decision), decision);
            }
        }

        String[] notBidding = {"Award to the best bid", "继续定标", "no idea"};
        for (String decision : notBidding) {
            Ctx c = build(TeamProtocols.CONTRACT_NET, Locale.ENGLISH);
            Assertions.assertNull(c.protocol.resolveSupervisorRoute(c.flow, c.trace, decision), decision);
        }
    }

    // ---------------- MarketBased ----------------

    @Test
    public void market_en() throws Exception {
        Ctx c = build(TeamProtocols.MARKET_BASED, Locale.ENGLISH);

        String first = supInstr(c);
        Assertions.assertTrue(first.contains("### Expert Marketplace"), first);
        Assertions.assertTrue(first.contains("**Top ROI Agent**:"), first);
        Assertions.assertTrue(first.contains("**Budget Pick**:"), first);
        assertNoChinese("sup_instr(seed)", first);

        agentRound(c, c.coder, "```java\nok\n``` SUCCESS");
        assertNoChinese("sup_instr", supInstr(c));
        assertNoChinese("sup_ctx", supCtx(c));
        assertNoChinese("agent_instr", agentInstr(c, c.reviewer, Locale.ENGLISH));
        assertNoChinese("agent_prompt", agentPrompt(c, c.reviewer, Prompt.of(ChatMessage.ofUser("continue")), Locale.ENGLISH));
        assertNoChinese("tools", describe(supervisorTools(c)) + describe(agentTools(c, c.reviewer)));
    }

    @Test
    public void market_zh() throws Exception {
        Ctx c = build(TeamProtocols.MARKET_BASED, Locale.CHINESE);
        String instr = supInstr(c);
        Assertions.assertTrue(instr.contains("\n> **今日推荐 (ROI King)**：coder"), instr);
        Assertions.assertTrue(instr.contains("\n> **低价优选 (Budget Pick)**："), instr);
    }

    // ---------------- 未内置语言兜底英文 ----------------

    @Test
    public void unknownLocale_fallbackEnglish() throws Exception {
        for (TeamProtocolFactory f : new TeamProtocolFactory[]{TeamProtocols.HIERARCHICAL, TeamProtocols.BLACKBOARD,
                TeamProtocols.CONTRACT_NET, TeamProtocols.MARKET_BASED}) {
            Ctx c = build(f, Locale.JAPANESE);
            assertNoChinese("sup_instr(ja)", supInstr(c));
            assertNoChinese("agent_instr(ja)", agentInstr(c, c.coder, Locale.JAPANESE));
        }
    }
}
