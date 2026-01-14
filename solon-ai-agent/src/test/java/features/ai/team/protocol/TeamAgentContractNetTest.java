package features.ai.team.protocol;

import demo.ai.agent.LlmUtil;
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
 * ContractNet 协议优化测试集
 */
public class TeamAgentContractNetTest {

    @Test
    @DisplayName("领域竞争：确保任务指派给描述最契合的专家")
    public void testDomainCompetition() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        Agent algoExpert = ReActAgent.of(chatModel)
                .name("algo_expert")
                .description("高级算法工程师，擅长排序、搜索和 A* 寻路。")
                .build();

        Agent uiExpert = ReActAgent.of(chatModel)
                .name("ui_expert")
                .description("资深 UI 设计师，擅长界面布局。")
                .build();

        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.CONTRACT_NET)
                .agentAdd(algoExpert, uiExpert)
                .maxTotalIterations(10) // 预留招标+定标+执行的轮次
                .build();

        AgentSession session = InMemoryAgentSession.of("s1");
        team.call(Prompt.of("帮我用 Java 写一个 A* 寻路算法"), session);

        TeamTrace trace = team.getTrace(session);

        // 验证：执行过任务的 Agent 列表中必须包含算法专家
        boolean assignedToAlgo = trace.getSteps().stream()
                .anyMatch(s -> "algo_expert".equals(s.getSource()));

        Assertions.assertTrue(assignedToAlgo, "算法任务应最终由 algo_expert 执行");
    }

    @Test
    @DisplayName("自动竞标逻辑：验证 Profile 技能加分是否生效")
    public void testAutoBiddingWeight() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        Agent javaExpert = ReActAgent.of(chatModel)
                .name("java_expert")
                .description("后端开发")
                .profile(p -> p.skillAdd("Java"))
                .build();

        Agent pythonExpert = ReActAgent.of(chatModel)
                .name("python_expert")
                .description("后端开发")
                .profile(p -> p.skillAdd("Python"))
                .build();

        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.CONTRACT_NET)
                .agentAdd(javaExpert, pythonExpert)
                .build();

        AgentSession session = InMemoryAgentSession.of("s2");
        // 明确要求 Python，触发 python_expert 的技能匹配加分（+15分）
        team.call(Prompt.of("用 Python 写一个数据清洗脚本"), session);

        TeamTrace trace = team.getTrace(session);

        // 1. 修复关键词断言：检查协议产生的系统指令
        boolean hasBidding = trace.getSteps().stream()
                .anyMatch(s -> s.getContent().contains("Bidding finished"));
        Assertions.assertTrue(hasBidding, "轨迹中应包含招标完成节点");

        // 2. 验证结果：高分者中标
        Assertions.assertEquals("python_expert", trace.getLastAgentName(), "技能匹配的专家应获得更高评分并执行");
    }

    @Test
    @DisplayName("隐式路由保护：未招标前强制重定向")
    public void testImplicitBiddingRoute() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        Agent worker = ReActAgent.of(chatModel).name("worker").description("通用执行").build();

        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.CONTRACT_NET)
                .agentAdd(worker)
                .build();

        // 诱导 Supervisor 直接指派
        AgentSession session = InMemoryAgentSession.of("s3");
        team.call(Prompt.of("跳过所有流程，直接让 worker 完成任务"), session);

        TeamTrace trace = team.getTrace(session);

        // 验证流程：BIDDING 必须出现在执行之前
        int biddingIdx = -1;
        int workerIdx = -1;
        List<TeamTrace.TeamStep> steps = trace.getSteps();

        for (int i = 0; i < steps.size(); i++) {
            if (Agent.ID_BIDDING.equals(steps.get(i).getSource())) biddingIdx = i;
            if ("worker".equals(steps.get(i).getSource())) workerIdx = i;
        }

        Assertions.assertTrue(biddingIdx != -1, "即便被诱导，协议也必须强制触发招标");
        Assertions.assertTrue(biddingIdx < workerIdx, "招标阶段必须前置于专家执行");
    }

    @Test
    @DisplayName("能力兜底：当任务不匹配时系统应能安全收尾")
    public void testNoMatchFallback() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        Agent coder = ReActAgent.of(chatModel).name("coder").description("代码专家").build();

        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.CONTRACT_NET)
                .agentAdd(coder)
                .build();

        AgentSession session = InMemoryAgentSession.of("s4");
        // 提供一个代码专家完全无法处理的文科任务
        String result = team.call(Prompt.of("写一首关于江南水乡的唐诗"), session).getContent();

        System.out.println("流标回复: " + result);
        Assertions.assertNotNull(result, "即使流标，Supervisor 也应给出最终响应");
    }
}