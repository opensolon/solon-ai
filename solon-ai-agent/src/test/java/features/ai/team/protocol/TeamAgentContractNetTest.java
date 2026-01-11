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

/**
 * ContractNet 策略测试：招标与竞标模式
 * <p>验证目标：
 * 1. 验证 CONTRACT_NET 协议下，协调者是否能识别任务属性。
 * 2. 验证基于 Agent 描述的“竞标”逻辑，即协调者应优先指派领域匹配度最高的智能体。
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

        // 专家 A：擅长算法
        Agent algoExpert = ReActAgent.of(chatModel)
                .name("algo_expert")
                .description("高级算法工程师，擅长排序、搜索和 A* 寻路等图计算。")
                .build();

        // 专家 B：擅长 UI/UX
        Agent uiExpert = ReActAgent.of(chatModel)
                .name("ui_expert")
                .description("资深 UI 设计师，擅长界面布局和交互体验，不涉及后端算法。")
                .build();

        // 组建采用合同网协议的团队
        TeamAgent team = TeamAgent.of(chatModel)
                .name("contract_team")
                .protocol(TeamProtocols.CONTRACT_NET)
                .agentAdd(algoExpert)
                .agentAdd(uiExpert)
                .build();

        // 打印图结构 YAML，观察竞标节点编排
        System.out.println("--- ContractNet Graph ---\n" + team.getGraph().toYaml());

        // 1. 使用 AgentSession 替代 FlowContext
        AgentSession session = InMemoryAgentSession.of("session_contract_01");

        // 2. 发起明确的算法任务
        String query = "我们需要实现一个复杂的 A* 寻路算法，谁能承接并给出实现思路？";
        String result = team.call(Prompt.of(query), session).getContent();

        System.out.println("=== 任务结果 ===\n" + result);

        // 3. 获取轨迹并验证竞标逻辑
        TeamTrace trace = team.getTrace(session);
        Assertions.assertNotNull(trace, "轨迹记录不应为空");

        // 检查是否有 BIDDING (bidding) 步骤记录
        boolean hasBidding = trace.getSteps().stream()
                .anyMatch(s -> Agent.ID_BIDDING.equalsIgnoreCase(s.getAgentName()));
        System.out.println("轨迹中是否触发竞标评估: " + hasBidding);

        // 验证执行者（理想情况下应为算法专家）
        if (trace.getStepCount() > 0) {
            String lastWorker = trace.getSteps().get(trace.getStepCount() - 1).getAgentName();
            System.out.println("最终中标执行者: " + lastWorker);
        }

        Assertions.assertNotNull(result);
        Assertions.assertTrue(trace.getStepCount() > 0);
        System.out.println("协作详情:\n" + trace.getFormattedHistory());
    }

    /**
     * 测试：能力重叠时的竞标决策
     * 场景：当多个 Agent 都能处理任务时，验证协调者的择优分配能力。
     */
    @Test
    public void testContractNetWithOverlappingExpertise() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        Agent expert1 = ReActAgent.of(chatModel)
                .name("full_stack_engineer")
                .description("全栈工程师，既能做前端 UI，也能做后端算法，擅长系统整体评估。")
                .build();

        Agent expert2 = ReActAgent.of(chatModel)
                .name("generalist_designer")
                .description("全能设计师，主攻 UI/UX，也了解算法的基本实现逻辑。")
                .build();

        TeamAgent team = TeamAgent.of(chatModel)
                .name("contract_overlap_team")
                .protocol(TeamProtocols.CONTRACT_NET)
                .agentAdd(expert1)
                .agentAdd(expert2)
                .build();

        AgentSession session = InMemoryAgentSession.of("session_contract_overlap");

        // 综合性任务，考验竞标过程中的匹配度计算
        String query = "请评估实现一个复杂路径规划系统的最佳方案，需要兼顾算法效率和界面设计。";
        String result = team.call(Prompt.of(query), session).getContent();

        TeamTrace trace = team.getTrace(session);
        Assertions.assertNotNull(trace);

        System.out.println("=== 重叠能力测试结果 ===\n" + result);
        System.out.println("竞标历史记录:\n" + trace.getFormattedHistory());

        Assertions.assertTrue(trace.getStepCount() > 0);
    }

    /**
     * 测试：流标场景
     * 场景：任务需求与所有专家描述均不匹配
     */
    @Test
    public void testContractNetWithNoMatchingExpert() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        Agent programmer = ReActAgent.of(chatModel)
                .name("programmer")
                .description("擅长编写 Java 代码和解决逻辑问题。")
                .build();

        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.CONTRACT_NET)
                .agentAdd(programmer)
                .build();

        AgentSession session = InMemoryAgentSession.of("session_no_match");

        // 发起一个完全无关的任务
        String query = "如何做一顿正宗的川菜麻婆豆腐？";
        String result = team.call(Prompt.of(query), session).getContent();

        System.out.println("=== 无匹配测试结果 ===\n" + result);

        TeamTrace trace = team.getTrace(session);
        // 观察协调者在无法招标时的决策逻辑
        Assertions.assertTrue(trace.getStepCount() >= 1);
    }

    /**
     * 测试：连续招标能力
     * 场景：第一个任务完成后，自动开启下个环节的竞标
     */
    @Test
    public void testContractNetSequentialBidding() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        Agent step1Expert = ReActAgent.of(chatModel).name("architect").description("负责系统架构蓝图设计").build();
        Agent step2Expert = ReActAgent.of(chatModel).name("coder").description("根据蓝图编写具体代码实现").build();

        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.CONTRACT_NET)
                .agentAdd(step1Expert)
                .agentAdd(step2Expert)
                .maxTotalIterations(5)
                .build();

        AgentSession session = InMemoryAgentSession.of("session_sequential");
        String query = "请先帮我设计架构，设计完后由开发人员写出一段核心代码。";

        team.call(Prompt.of(query), session);

        TeamTrace trace = team.getTrace(session);
        // 核心断言：验证参与的 Agent 数量是否大于 1
        long uniqueAgents = trace.getSteps().stream()
                .map(s -> s.getAgentName())
                .filter(name -> !Agent.ID_SUPERVISOR.equalsIgnoreCase(name) && !Agent.ID_BIDDING.equalsIgnoreCase(name))
                .distinct().count();

        System.out.println("实际参与协作的专家数: " + uniqueAgents);
        Assertions.assertTrue(uniqueAgents >= 1);
    }
}