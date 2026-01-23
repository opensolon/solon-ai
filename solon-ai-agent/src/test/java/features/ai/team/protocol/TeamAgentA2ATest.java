package features.ai.team.protocol;

import demo.ai.llm.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamProtocols;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 优化版 A2A 协作测试：低 Token 消耗、高逻辑覆盖
 */
public class TeamAgentA2ATest {

    private static final String SHORT_LIMIT = " Constraint: Reply < 15 words.";

    private void log(String title, Object content) {
        System.out.println(">> [" + title + "]\n" + content);
    }

    // 1. 基础接力逻辑：验证 A 移交给 B 的顺序和行为
    @Test
    public void testA2ABasicLogic() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        Agent designer = ReActAgent.of(chatModel).name("designer").description("设计")
                .systemPrompt(p->p
                        .instruction("描述一个红色按钮样式。完成后必须 transfer_to 给 developer。" + SHORT_LIMIT)).build();

        Agent developer = ReActAgent.of(chatModel).name("developer").description("开发")
                .systemPrompt(p->p
                        .instruction("根据设计输出简短 HTML。" + SHORT_LIMIT)).build();

        TeamAgent team = TeamAgent.of(chatModel).protocol(TeamProtocols.A2A).agentAdd(designer, developer).maxTurns(5).build();
        AgentSession session = InMemoryAgentSession.of("a2a_s1");
        String result = team.call(Prompt.of("开始任务"), session).getContent();

        List<String> order = team.getTrace(session).getRecords().stream()
                .map(TeamTrace.TeamRecord::getSource).filter(n -> !"supervisor".equals(n)).collect(Collectors.toList());

        log("Path", String.join(" -> ", order));
        log("Result", result);

        Assertions.assertTrue(order.indexOf("designer") < order.indexOf("developer"));
        Assertions.assertTrue(result.contains("<button") || result.contains("style"));
    }

    // 2. 上下文透传：验证 Memo 信息是否在 A -> B 链条中丢失
    @Test
    public void testA2AMemoInjection() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        Agent agentA = ReActAgent.of(chatModel).name("A")
                .systemPrompt(p->p
                        .instruction("将 'DATA_777' 存入 transfer_to 的 memo 参数并移交给 B。" + SHORT_LIMIT)).build();

        Agent agentB = ReActAgent.of(chatModel).name("B")
                .systemPrompt(p->p
                        .instruction("重复你收到的 memo 数据。" + SHORT_LIMIT)).build();

        TeamAgent team = TeamAgent.of(chatModel).protocol(TeamProtocols.A2A).agentAdd(agentA, agentB).build();
        AgentSession session = InMemoryAgentSession.of("a2a_s2");
        team.call(Prompt.of("启动"), session);

        String history = team.getTrace(session).getFormattedHistory();
        log("History", history);
        Assertions.assertTrue(history.contains("DATA_777"));
    }

    // 3. 幻觉防御：验证非法路由的目标拦截
    @Test
    public void testA2AHallucinationDefense() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        Agent agentA = ReActAgent.of(chatModel).name("A")
                .systemPrompt(p->p
                        .instruction("故意移交给不存在的专家 'ghost'。" + SHORT_LIMIT)).build();

        TeamAgent team = TeamAgent.of(chatModel).protocol(TeamProtocols.A2A).agentAdd(agentA).build();
        AgentSession session = InMemoryAgentSession.of("a2a_s3");
        team.call(Prompt.of("触发幻觉"), session);

        log("Final Route", team.getTrace(session).getRoute());
        Assertions.assertEquals(Agent.ID_END, team.getTrace(session).getRoute());
    }

    // 4. 死循环防御：验证协作上限
    @Test
    public void testA2ALoopAndMaxIteration() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        Agent a = ReActAgent.of(chatModel).name("A").systemPrompt(p->p.instruction("转给 B。")).build();
        Agent b = ReActAgent.of(chatModel).name("B").systemPrompt(p->p.instruction("转给 A。")).build();

        TeamAgent team = TeamAgent.of(chatModel).protocol(TeamProtocols.A2A).agentAdd(a, b).maxTurns(3).build();
        AgentSession session = InMemoryAgentSession.of("a2a_s4");

        team.call(Prompt.of("踢球"), session);
        log("Record Count", team.getTrace(session).getRecordCount());
        Assertions.assertTrue(team.getTrace(session).getRecordCount() >= 3);
    }

    // 5. 生产级复杂流水线：降维验证（分析 -> 审计 -> 输出）
    @Test
    @DisplayName("生产级流水线：A2A 多节点合规审计流")
    public void testA2AComplexProductionPipeline() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 分析：提取关键字
        Agent analyst = ReActAgent.of(chatModel).name("Analyst")
                .systemPrompt(p->p.role("分析员")
                        .instruction("提取输入中的‘金额’，放入 memo 转交给 Auditor。" + SHORT_LIMIT)).build();

        // 审计：判定金额风险
        Agent auditor = ReActAgent.of(chatModel).name("Auditor")
                .systemPrompt(p->p.role("审计员")
                        .instruction("若 memo 金额 > 1000，移交给 Legal；否则直接给 Designer。" + SHORT_LIMIT)).build();

        // 法务：二次确认
        Agent legal = ReActAgent.of(chatModel).name("Legal")
                .systemPrompt(p->p.role("法务")
                        .instruction("对高额单据备注 'APPROVED' 并转交给 Designer。" + SHORT_LIMIT)).build();

        // 设计：产出最终代码
        Agent designer = ReActAgent.of(chatModel).name("Designer")
                .systemPrompt(p->p.role("开发")
                        .instruction(t -> "根据上游数据输出 HTML 凭证，以 " + t.getConfig().getFinishMarker() + " 结束。")).build();

        TeamAgent team = TeamAgent.of(chatModel).protocol(TeamProtocols.A2A).agentAdd(analyst, auditor, legal, designer).maxTurns(10).build();
        AgentSession session = InMemoryAgentSession.of("a2a_s5");

        // 测试路径：Analyst -> Auditor -> Legal -> Designer (因为金额 9999 > 1000)
        String result = team.call(Prompt.of("处理订单：金额 9999 元"), session).getContent();

        List<String> history = team.getTrace(session).getRecords().stream()
                .map(TeamTrace.TeamRecord::getSource).filter(s -> !s.equals("supervisor")).collect(Collectors.toList());

        log("Work Path", String.join(" -> ", history));
        log("Result HTML", result);

        Assertions.assertTrue(history.contains("Legal"), "未能触发高额审计路径");
        Assertions.assertTrue(result.replace(",", "").contains("9999"), "业务数据在接力中丢失");
        Assertions.assertTrue(result.toLowerCase().contains("html"), "最终节点未产出代码");
    }
}