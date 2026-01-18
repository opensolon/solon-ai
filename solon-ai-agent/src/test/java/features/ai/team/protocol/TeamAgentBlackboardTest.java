package features.ai.team.protocol;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActSystemPrompt;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamProtocols;
import org.noear.solon.ai.agent.team.TeamSystemPrompt;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.annotation.Param;

/**
 * 优化版 Blackboard 策略测试：低 Token 消耗 + 详细日志 + 正确 SystemPrompt 用法
 */
public class TeamAgentBlackboardTest {

    private static final String SHORT_LIMIT = " Constraint: Reply < 20 words.";

    // --- 通用极简工具类 ---
    public static class MiniTools {
        @ToolMapping(name = "update_board", description = "在黑板记录数据")
        public String update(@Param("key") String key, @Param("val") String val) {
            return String.format("{\"%s\":\"%s\"}", key, val);
        }

        @ToolMapping(name = "set_status", description = "设置流程状态(PASSED/REJECTED)")
        public String setStatus(@Param("s") String s) {
            return "STATUS_UPDATED:" + s;
        }
    }

    private void logHeader(String title) {
        System.out.println("\n" + "====== [ " + title + " ] " + "======");
    }

    // 1. 基础逻辑：验证 Context 共享与接力
    @Test
    public void testBlackboardLogic() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        logHeader("维度1：基础共享协作");

        Agent step1 = ReActAgent.of(chatModel).name("A")
                .systemPrompt(ReActSystemPrompt.builder().role("诗人").instruction("写个关于猫的词。"+SHORT_LIMIT).build()).build();
        Agent step2 = ReActAgent.of(chatModel).name("B")
                .systemPrompt(ReActSystemPrompt.builder().role("翻译").instruction("将黑板上的词翻译成英文。"+SHORT_LIMIT).build()).build();

        TeamAgent team = TeamAgent.of(chatModel).protocol(TeamProtocols.BLACKBOARD).agentAdd(step1, step2).maxTurns(3).build();
        AgentSession session = InMemoryAgentSession.of("s1");
        String result = team.call(Prompt.of("开始执行"), session).getContent();

        System.out.println("Result: " + result);
        System.out.println("Trace: " + team.getTrace(session));
        Assertions.assertFalse(result.isEmpty());
    }

    // 2. 状态驱动与补位 (Gap Filling)
    @Test
    @DisplayName("验证状态驱动：前端等待后端数据")
    public void testBlackboardAutomaticGapFilling() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        logHeader("维度2：状态驱动补位");
        MethodToolProvider tools = new MethodToolProvider(new MiniTools());

        Agent backend = ReActAgent.of(chatModel).name("backend").toolAdd(tools)
                .systemPrompt(ReActSystemPrompt.builder().instruction("调用 update_board(key='api', val='ok')。"+SHORT_LIMIT).build()).build();
        Agent frontend = ReActAgent.of(chatModel).name("frontend")
                .systemPrompt(ReActSystemPrompt.builder().instruction("黑板没 api 就等，有 api 就回复 'READY'。"+SHORT_LIMIT).build()).build();

        TeamAgent team = TeamAgent.of(chatModel).protocol(TeamProtocols.BLACKBOARD).agentAdd(frontend, backend).maxTurns(5).build();
        AgentSession session = InMemoryAgentSession.of("s2");
        String result = team.call(Prompt.of("协作开始"), session).getContent();

        System.out.println("Dashboard: " + team.getTrace(session).getProtocolDashboardSnapshot());
        Assertions.assertTrue(result.contains("READY"));
    }

    // 3. 企业级门禁与终态判定
    @Test
    @DisplayName("企业级门禁：基于黑板数据判定终态")
    public void testBlackboardEnterpriseProductionLevel() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        logHeader("维度3：数据门禁判定");
        MethodToolProvider tools = new MethodToolProvider(new MiniTools());

        Agent worker = ReActAgent.of(chatModel).name("worker").toolAdd(tools)
                .systemPrompt(ReActSystemPrompt.builder().instruction("调 update_board(key='task', val='done')。"+SHORT_LIMIT).build()).build();

        Agent reviewer = ReActAgent.of(chatModel).name("reviewer")
                .systemPrompt(ReActSystemPrompt.builder()
                        .instruction(t -> "检查黑板 JSON。若无 'task'，指派 worker；若有，输出 " + t.getConfig().getFinishMarker() + " 并总结。" + SHORT_LIMIT)
                        .build()).build();

        TeamAgent team = TeamAgent.of(chatModel).protocol(TeamProtocols.BLACKBOARD).agentAdd(worker, reviewer).maxTurns(5).build();
        AgentSession session = InMemoryAgentSession.of("s3");
        String result = team.call(Prompt.of("执行任务"), session).getContent();

        System.out.println("Result: " + result);
        System.out.println("Trace: " + team.getTrace(session));
        Assertions.assertTrue(team.getTrace(session).getProtocolDashboardSnapshot().contains("task"));
    }

    // 4. 多协议博弈与修正 (打回重做逻辑)
    @Test
    @DisplayName("博弈修正：不合规打回重做")
    public void testEnterpriseComplexProductionLevel() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        logHeader("维度4：循环博弈与修正");
        MethodToolProvider tools = new MethodToolProvider(new MiniTools());

        Agent writer = ReActAgent.of(chatModel).name("writer").toolAdd(tools)
                .systemPrompt(ReActSystemPrompt.builder().instruction("写个不含数字的词并 update_board。若被打回就重写。"+SHORT_LIMIT).build()).build();

        Agent security = ReActAgent.of(chatModel).name("security").toolAdd(tools)
                .systemPrompt(ReActSystemPrompt.builder().instruction("词含数字则 set_status('PASSED')，否则 set_status('REJECTED')。"+SHORT_LIMIT).build()).build();

        TeamAgent team = TeamAgent.of(chatModel).protocol(TeamProtocols.BLACKBOARD).agentAdd(writer, security)
                .maxTurns(8)
                .systemPrompt(TeamSystemPrompt.builder()
                        .instruction("REJECTED 则指派 writer 修正，PASSED 则结束。")
                        .build()).build();

        AgentSession session = InMemoryAgentSession.of("s4");
        team.call(Prompt.of("开始博弈"), session);

        TeamTrace trace = team.getTrace(session);
        System.out.println("Final Dashboard: " + trace.getProtocolDashboardSnapshot());
        Assertions.assertTrue(trace.getProtocolDashboardSnapshot().contains("PASSED"));
    }

    // 5. 冲突解决：状态覆盖验证
    @Test
    @DisplayName("冲突解决：资深专家覆盖决策")
    public void testBlackboardConflictResolution() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        logHeader("维度5：状态覆盖(冲突解决)");
        MethodToolProvider tools = new MethodToolProvider(new MiniTools());

        Agent junior = ReActAgent.of(chatModel).name("junior").toolAdd(tools)
                .systemPrompt(ReActSystemPrompt.builder().instruction("update_board(key='val', val='1')。"+SHORT_LIMIT).build()).build();
        Agent senior = ReActAgent.of(chatModel).name("senior").toolAdd(tools)
                .systemPrompt(ReActSystemPrompt.builder().instruction("若 val 为 1，则 update_board(key='val', val='99')。"+SHORT_LIMIT).build()).build();

        TeamAgent team = TeamAgent.of(chatModel).protocol(TeamProtocols.BLACKBOARD).agentAdd(junior, senior).maxTurns(5).build();
        AgentSession session = InMemoryAgentSession.of("s5");
        team.call(Prompt.of("进行决策"), session);

        String dashboard = team.getTrace(session).getProtocolDashboardSnapshot();
        System.out.println("Dashboard: " + dashboard);
        Assertions.assertTrue(dashboard.contains("\"val\":\"99\""));
    }

    // 6. 死循环预防验证
    @Test
    public void testLoopPrevention() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        logHeader("维度6：死循环防御");
        Agent a = ReActAgent.of(chatModel).name("a").systemPrompt(ReActSystemPrompt.builder().instruction("推给b。"+SHORT_LIMIT).build()).build();
        Agent b = ReActAgent.of(chatModel).name("b").systemPrompt(ReActSystemPrompt.builder().instruction("推给a。"+SHORT_LIMIT).build()).build();

        TeamAgent team = TeamAgent.of(chatModel).protocol(TeamProtocols.BLACKBOARD).agentAdd(a, b).maxTurns(3).build();
        AgentSession session = InMemoryAgentSession.of("s6");
        team.call(Prompt.of("工作"), session);

        System.out.println("Trace Records: " + team.getTrace(session).getRecordCount());
        Assertions.assertTrue(team.getTrace(session).getRecordCount() < 15);
    }
}