package features.ai.team.strategy;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
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

/**
 * A2A (Agent-to-Agent) 策略测试
 */
public class TeamAgentA2ATest {

    @Test
    public void testA2ALogic() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 优化：Prompt 变简洁了，不再需要手动告诉它移交语法，协议会自动注入
        Agent designer = ReActAgent.of(chatModel)
                .name("designer")
                .promptProvider(c -> "你是 UI 设计师，负责界面布局设计。")
                .description("擅长 UI/UX 设计，能产出视觉方案。") // 关键：协议会利用这个描述
                .build();

        Agent developer = ReActAgent.of(chatModel)
                .name("developer")
                .promptProvider(c -> "你是开发工程师，负责代码实现。")
                .description("擅长前端 HTML/JS 编码实现。")
                .build();

        TeamAgent team = TeamAgent.of(chatModel)
                .name("a2a_team")
                .protocol(TeamProtocols.A2A) // 使用新协议
                .addAgent(designer)
                .addAgent(developer)
                .finishMarker("FINISH") // 明确结束标识
                .maxTotalIterations(5)
                .build();

        // 打印图结构，验证节点是否连向了 __A2A_DISPATCHER__ (或 Supervisor)
        System.out.println("--- A2A Graph ---\n" + team.getGraph().toYaml());

        AgentSession session = InMemoryAgentSession.of("session_a2a_01");
        String query = "请先设计登录页，然后交给开发实现，完成后告诉我。";

        String result = team.call(Prompt.of(query), session).getContent();
        TeamTrace trace = team.getTrace(session);

        System.out.println("=== 协作轨迹 ===\n" + trace.getFormattedHistory());

        // 验证点 1：流转过程
        // 期望轨迹：Start -> designer -> (Dispatcher) -> developer -> (Dispatcher) -> End
        boolean handedOff = trace.getSteps().stream()
                .anyMatch(s -> s.getAgentName().equals("designer") && s.getAgentName().contains("developer"));

        System.out.println("检测到设计师主动移交给开发: " + handedOff);

        Assertions.assertTrue(trace.getStepCount() >= 2, "应该至少经历两个 Agent");
        Assertions.assertTrue(result.contains("代码") || result.contains("实现"), "最终结果应包含开发的产出");
    }

    @Test
    public void testA2AChainTransfer() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 测试长链移交 A -> B -> C
        Agent researcher = ReActAgent.of(chatModel).name("researcher")
                .description("负责搜集人工智能资料").build();
        Agent writer = ReActAgent.of(chatModel).name("writer")
                .description("负责将资料写成草稿").build();
        Agent editor = ReActAgent.of(chatModel).name("editor")
                .description("负责校对文稿并输出最终版").build();

        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.A2A)
                .addAgent(researcher, writer, editor)
                .build();

        AgentSession session = InMemoryAgentSession.of("s2");
        String result = team.call(Prompt.of("研究 AI 并写成校对后的报告"), session).getContent();
        TeamTrace trace = team.getTrace(session); // 假设可以通过 key 获取

        // 验证参与者多样性
        long distinctAgents = trace.getSteps().stream()
                .map(s -> s.getAgentName())
                .filter(name -> !name.equals("SYSTEM") && !name.equals("START"))
                .distinct()
                .count();

        System.out.println("参与协作的专家数: " + distinctAgents);
        Assertions.assertTrue(distinctAgents >= 2);
    }
}