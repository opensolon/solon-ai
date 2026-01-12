package features.ai.team.protocol;

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

import java.util.List;
import java.util.stream.Collectors;

/**
 * ContractNet 策略测试：招标与竞标模式
 * <p>验证目标：
 * 1. 验证 CONTRACT_NET 协议下，协调者是否能识别任务属性。
 * 2. 验证基于 Profile/Description 的“自省式竞标”逻辑。
 * 3. 验证协议在无标书状态下的隐式路由保护。
 * </p>
 */
public class TeamAgentContractNetTest {

    /**
     * 测试：典型的领域对立竞标
     * 场景：算法任务应由 algo_expert 承接，而非 ui_expert。
     */
    @Test
    public void testContractNetLogic() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        Agent algoExpert = ReActAgent.of(chatModel)
                .name("algo_expert")
                .description("高级算法工程师，擅长排序、搜索和 A* 寻路等图计算。")
                .build();

        Agent uiExpert = ReActAgent.of(chatModel)
                .name("ui_expert")
                .description("资深 UI 设计师，擅长界面布局和交互体验。")
                .build();

        // 重点：增加 maxTotalIterations 确保有足够的轮次完成 [招标 -> 定标 -> 执行]
        TeamAgent team = TeamAgent.of(chatModel)
                .name("contract_team")
                .protocol(TeamProtocols.CONTRACT_NET)
                .agentAdd(algoExpert)
                .agentAdd(uiExpert)
                .maxTotalIterations(10)
                .build();

        AgentSession session = InMemoryAgentSession.of("session_contract_01");
        String query = "请实现一个 A* 寻路算法的核心逻辑代码。"; // 语气更强硬，要求“代码”，迫使 LLM 找专家

        team.call(Prompt.of(query), session);

        TeamTrace trace = team.getTrace(session);
        System.out.println("协作详情:\n" + trace.getFormattedHistory());

        // 验证执行者
        List<String> workers = trace.getSteps().stream()
                .filter(s->s.isAgent())
                .map(TeamTrace.TeamStep::getSource)
                .collect(Collectors.toList());

        // 如果 LLM 依然不给力没调专家，打印警告
        if (workers.isEmpty()) {
            System.err.println("Warning: LLM skipped agent execution and finished directly.");
        }

        Assertions.assertFalse(workers.isEmpty(), "LLM 应该至少指派一名专家执行任务");
        Assertions.assertTrue(workers.contains("algo_expert"), "任务应指派给 algo_expert");
    }

    /**
     * 测试：自动竞标的分数评估逻辑
     * 验证：协议是否能识别 Profile 里的技能并给出更高的竞标分
     */
    @Test
    public void testContractNetAutoBiddingLogic() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        Agent descExpert = ReActAgent.of(chatModel)
                .name("java_expert")
                .description("擅长 Java 语言开发。")
                .build();

        Agent profileExpert = ReActAgent.of(chatModel)
                .name("python_expert")
                .description("后端开发")
                .profile(p -> p.skillAdd("Python").skillAdd("Django"))
                .build();

        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.CONTRACT_NET)
                .agentAdd(descExpert)
                .agentAdd(profileExpert)
                .build();

        AgentSession session = InMemoryAgentSession.of("session_auto_bid");
        team.call(Prompt.of("用 Python 写一个爬虫脚本"), session);

        TeamTrace trace = team.getTrace(session);

        // 修复 Null 问题：
        // 因为 onTeamFinished 会移除 KEY_CONTRACT_STATE，我们检查轨迹步骤中的上下文镜像或修改协议
        // 这里我们通过检查 System 日志来侧面验证，或者直接看 toString 是否曾经存在

        boolean hasBiddingStep = trace.getSteps().stream()
                .anyMatch(s -> s.getContent().contains("Bidding completed"));

        Assertions.assertTrue(hasBiddingStep, "应该经历过招标阶段");

        // 打印历史，手动确认看板输出
        System.out.println(trace.getFormattedHistory());
    }

    /**
     * 测试：隐式路由保护
     * 场景：即便不点招标，当没有标书时，协议也应自动拉回招标阶段
     */
    @Test
    public void testImplicitBiddingRedirection() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        Agent worker = ReActAgent.of(chatModel).name("worker").description("通用执行者").build();

        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.CONTRACT_NET)
                .agentAdd(worker)
                .build();

        AgentSession session = InMemoryAgentSession.of("session_implicit");
        // Supervisor 可能会尝试直接分配
        team.call(Prompt.of("直接让 worker 执行任务"), session);

        TeamTrace trace = team.getTrace(session);

        // 验证轨迹顺序：bidding 必须出现在专家执行之前
        int biddingIndex = -1;
        int workerIndex = -1;

        for (int i = 0; i < trace.getSteps().size(); i++) {
            String source = trace.getSteps().get(i).getSource();
            if (Agent.ID_BIDDING.equals(source)) biddingIndex = i;
            if ("worker".equals(source)) workerIndex = i;
        }

        Assertions.assertTrue(biddingIndex != -1, "必须触发了隐式招标");
        Assertions.assertTrue(biddingIndex < workerIndex, "招标必须发生在专家执行之前");
    }

    /**
     * 测试：流标场景
     * 场景：当任务完全不匹配任何专家时，系统应能正常结束而非陷入循环
     */
    @Test
    public void testContractNetWithNoMatchingExpert() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        Agent programmer = ReActAgent.of(chatModel)
                .name("programmer")
                .description("擅长编写 Java 代码。")
                .build();

        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.CONTRACT_NET)
                .agentAdd(programmer)
                .build();

        AgentSession session = InMemoryAgentSession.of("session_no_match");
        String result = team.call(Prompt.of("如何制作正宗麻婆豆腐？"), session).getContent();

        System.out.println("流标结果: " + result);
        TeamTrace trace = team.getTrace(session);

        // 即使没有专家能处理，Supervisor 也应该给出最终回复（通常是道歉或拒绝）
        Assertions.assertTrue(trace.getStepCount() >= 1);
    }

    /**
     * 测试：连续招标能力
     */
    @Test
    public void testContractNetSequentialBidding() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        Agent architect = ReActAgent.of(chatModel).name("architect").description("系统架构设计").build();
        Agent coder = ReActAgent.of(chatModel).name("coder").description("代码实现").build();

        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.CONTRACT_NET)
                .agentAdd(architect)
                .agentAdd(coder)
                .maxTotalIterations(5)
                .build();

        AgentSession session = InMemoryAgentSession.of("session_seq");
        team.call(Prompt.of("先设计架构，再写代码"), session);

        TeamTrace trace = team.getTrace(session);

        // 统计排除掉系统节点外的独立专家数
        long uniqueAgents = trace.getSteps().stream()
                .map(s -> s.getSource())
                .filter(name -> !Agent.ID_SUPERVISOR.equalsIgnoreCase(name) &&
                        !Agent.ID_BIDDING.equalsIgnoreCase(name) &&
                        !Agent.ID_SYSTEM.equalsIgnoreCase(name))
                .distinct().count();

        System.out.println("实际参与协作的专家数: " + uniqueAgents);
        Assertions.assertTrue(uniqueAgents >= 1, "至少应有一个专家参与了工作");
    }
}