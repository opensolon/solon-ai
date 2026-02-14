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
import org.noear.solon.ai.agent.team.TeamResponse;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatModel;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 优化版 A2A 协作测试：低 Token 消耗、高逻辑覆盖
 */
public class TeamAgentA2ATest {

    private void log(String title, Object content) {
        System.out.println(">> [" + title + "]\n" + content);
    }

    // 1. 基础接力逻辑：验证 A 移交给 B 的顺序和行为
    @Test
    public void testA2ABasicLogic() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        Agent designer = ReActAgent.of(chatModel).name("designer")
                .role("设计专家")
                .instruction("描述一个红色按钮样式。完成后必须 transfer_to 给 developer。").build();

        Agent developer = ReActAgent.of(chatModel).name("developer")
                .role("开发专家")
                .instruction("根据设计输出简短 HTML。").build();

        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.A2A)
                .agentAdd(designer, developer)
                .maxTurns(5)
                .build();

        AgentSession session = InMemoryAgentSession.of("a2a_s1");

        // 风格重组
        TeamResponse resp = team.prompt("开始任务")
                .session(session)
                .call();

        List<String> order = resp.getTrace()
                .getRecords()
                .stream()
                .map(TeamTrace.TeamRecord::getSource)
                .filter(n -> !"supervisor".equals(n))
                .collect(Collectors.toList());

        log("Path", String.join(" -> ", order));
        log("Result", resp.getContent());

        Assertions.assertTrue(order.indexOf("designer") < order.indexOf("developer"));
        Assertions.assertTrue(resp.getContent().contains("<button") || resp.getContent().contains("style"));
    }

    // 2. 上下文透传：验证 Memo 信息是否在 A -> B 链条中丢失
    @Test
    public void testA2AMemoInjection() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        Agent agentA = ReActAgent.of(chatModel).name("A")
                .role("发送方")
                .instruction("将 'DATA_777' 存入 transfer_to 的 memo 参数并移交给 B。").build();

        Agent agentB = ReActAgent.of(chatModel).name("B")
                .role("接收方")
                .instruction("重复你收到的 memo 数据。").build();

        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.A2A)
                .agentAdd(agentA, agentB)
                .build();

        AgentSession session = InMemoryAgentSession.of("a2a_s2");

        // 风格重组
        TeamResponse resp = team.prompt("启动").session(session).call();

        String history = resp.getTrace()
                .getFormattedHistory();

        log("History", history);
        Assertions.assertTrue(history.contains("DATA_777"));
    }

    // 3. 幻觉防御：验证非法路由的目标拦截
    @Test
    public void testA2AHallucinationDefense() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        Agent agentA = ReActAgent.of(chatModel).name("A")
                .role("测试节点")
                .instruction("故意移交给不存在的专家 'ghost'。").build();

        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.A2A)
                .agentAdd(agentA)
                .build();

        AgentSession session = InMemoryAgentSession.of("a2a_s3");

        // 风格重组
        TeamResponse resp = team.prompt("触发幻觉").session(session).call();

        log("Final Route", resp.getTrace().getRoute());
        Assertions.assertEquals(Agent.ID_END, resp.getTrace().getRoute());
    }

    // 4. 死循环防御：验证协作上限
    @Test
    public void testA2ALoopAndMaxIteration() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        Agent a = ReActAgent.of(chatModel).name("A").role("节点A").instruction("转给 B。").build();
        Agent b = ReActAgent.of(chatModel).name("B").role("节点B").instruction("转给 A。").build();

        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.A2A)
                .agentAdd(a, b)
                .maxTurns(3)
                .build();

        AgentSession session = InMemoryAgentSession.of("a2a_s4");

        // 风格重组
        TeamResponse resp = team.prompt("踢球").session(session).call();

        log("Record Count", resp.getTrace().getRecordCount());
        Assertions.assertTrue(resp.getTrace().getRecordCount() >= 3);
    }

    // 5. 生产级复杂流水线：降维验证（分析 -> 审计 -> 输出）
    @Test
    @DisplayName("生产级流水线：A2A 多节点合规审计流")
    public void testA2AComplexProductionPipeline() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 分析：提取关键字
        Agent analyst = ReActAgent.of(chatModel).name("Analyst")
                .role("分析员")
                .instruction("提取输入中的‘金额’，放入 memo 转交给 Auditor。").build();

        // 审计：判定金额风险
        Agent auditor = ReActAgent.of(chatModel).name("Auditor")
                .role("审计员")
                .instruction("若 memo 金额 > 1000，移交给 Legal；否则直接给 Designer。").build();

        // 法务：二次确认
        Agent legal = ReActAgent.of(chatModel).name("Legal")
                .role("法务")
                .instruction("对高额单据备注 'APPROVED' 并转交给 Designer。").build();

        // 设计：产出最终代码
        Agent designer = ReActAgent.of(chatModel).name("Designer")
                .role("开发")
                .instruction(t -> "根据上游数据输出 HTML 凭证，以 " + t.getConfig().getFinishMarker() + " 结束。").build();

        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.A2A)
                .agentAdd(analyst, auditor, legal, designer)
                .maxTurns(10)
                .build();

        AgentSession session = InMemoryAgentSession.of("a2a_s5");

        // 测试路径：Analyst -> Auditor -> Legal -> Designer (因为金额 9999 > 1000)
        // 风格重组
        TeamResponse resp = team.prompt("处理订单：金额 9999 元")
                .session(session)
                .call();

        List<String> history = resp.getTrace()
                .getRecords()
                .stream()
                .map(TeamTrace.TeamRecord::getSource)
                .filter(s -> !s.equals("supervisor"))
                .collect(Collectors.toList());

        log("Work Path", String.join(" -> ", history));
        log("Result HTML", resp.getContent());

        Assertions.assertTrue(history.contains("Legal"), "未能触发高额审计路径");
        Assertions.assertTrue(resp.getContent().replace(",", "").contains("9999"), "业务数据在接力中丢失");
        Assertions.assertTrue(resp.getContent().toLowerCase().contains("html"), "最终节点未产出代码");
    }
}