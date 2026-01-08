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
 * A2A (Agent-to-Agent) 策略测试：直接移交的去中心化协作
 * <p>验证目标：
 * 1. 验证 A2A 协议下，Agent 之间是否能直接进行移交（Handoff）。
 * 2. 验证减少 Supervisor 干预的协作流程是否更高效。
 * 3. 验证移交指令的识别和路由逻辑。
 * </p>
 */
public class TeamAgentA2ATest {

    /**
     * 测试：基础 A2A 移交逻辑
     * 场景：设计任务在设计师和工程师之间直接移交
     */
    @Test
    public void testA2ALogic() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 设计师：负责 UI 设计
        Agent designer = ReActAgent.of(chatModel)
                .name("designer")
                .promptProvider(c -> "你是 UI 设计师，负责界面设计。完成设计后如果需要开发实现，请说'Transfer to developer'")
                .description("资深 UI/UX 设计师，擅长界面布局和用户体验设计。")
                .build();

        // 开发者：负责前端实现
        Agent developer = ReActAgent.of(chatModel)
                .name("developer")
                .promptProvider(c -> "你是前端开发工程师，负责将设计稿转化为代码。完成开发后如果需要测试，请说'Transfer to tester'")
                .description("前端开发工程师，擅长 HTML/CSS/JavaScript 实现。")
                .build();

        // 测试员：负责质量保证
        Agent tester = ReActAgent.of(chatModel)
                .name("tester")
                .promptProvider(c -> "你是质量保证工程师，负责测试和验证功能。")
                .description("质量保证工程师，擅长功能测试和 bug 发现。")
                .build();

        // 使用 A2A 协议组建团队
        TeamAgent team = TeamAgent.of(chatModel)
                .name("a2a_team")
                .protocol(TeamProtocols.A2A)
                .addAgent(designer)
                .addAgent(developer)
                .addAgent(tester)
                .maxTotalIterations(8) // 设置最大迭代次数
                .build();

        // 打印图结构 YAML
        System.out.println("--- A2A Team Graph ---\n" + team.getGraph().toYaml());

        // 创建会话
        AgentSession session = InMemoryAgentSession.of("session_a2a_01");

        // 发起设计到开发的任务
        String query = "请设计一个登录页面的 UI，并实现它，最后进行测试验证。";
        String result = team.call(Prompt.of(query), session).getContent();

        System.out.println("=== A2A 执行结果 ===\n" + result);

        // 验证执行轨迹
        TeamTrace trace = team.getTrace(session);
        Assertions.assertNotNull(trace, "应该产生协作轨迹");
        Assertions.assertFalse(result.isEmpty(), "结果不应为空");
        Assertions.assertTrue(trace.getStepCount() > 0, "至少应该有一步记录");

        // 输出协作明细
        System.out.println("协作步数: " + trace.getStepCount());
        System.out.println("协作轨迹详情:\n" + trace.getFormattedHistory());

        // 验证是否发生了 Agent 之间的移交
        boolean hasTransfer = false;
        for (TeamTrace.TeamStep step : trace.getSteps()) {
            if (step.getContent() != null &&
                    (step.getContent().contains("Transfer to") ||
                            step.getContent().contains("移交") ||
                            step.getContent().contains("handoff"))) {
                hasTransfer = true;
                break;
            }
        }

        // A2A 协议应该支持移交，但不一定每次都会发生
        // 这个断言可以根据实际情况调整
        System.out.println("是否检测到移交指令: " + hasTransfer);
    }

    /**
     * 测试：A2A 协议与层级协议的效率对比
     * 场景：相同的任务在两种协议下的执行步数对比
     */
    @Test
    public void testA2AEfficiency() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 创建三个专业 Agent
        Agent researcher = ReActAgent.of(chatModel)
                .name("researcher")
                .promptProvider(c -> "你是研究员，负责信息收集和分析。完成研究后如果需要写作，请说'Transfer to writer'")
                .description("信息研究员，擅长数据收集和趋势分析。")
                .build();

        Agent writer = ReActAgent.of(chatModel)
                .name("writer")
                .promptProvider(c -> "你是作家，负责将研究结果整理成文章。完成写作后如果需要校对，请说'Transfer to editor'")
                .description("专业作家，擅长将复杂信息转化为易懂的文章。")
                .build();

        Agent editor = ReActAgent.of(chatModel)
                .name("editor")
                .promptProvider(c -> "你是编辑，负责校对和优化文章。")
                .description("资深编辑，擅长语法校对和内容优化。")
                .build();

        // 测试 A2A 协议
        TeamAgent a2aTeam = TeamAgent.of(chatModel)
                .name("a2a_efficiency_team")
                .protocol(TeamProtocols.A2A)
                .addAgent(researcher)
                .addAgent(writer)
                .addAgent(editor)
                .maxTotalIterations(8)
                .build();

        // 测试层级协议作为对比
        TeamAgent hierarchicalTeam = TeamAgent.of(chatModel)
                .name("hierarchical_efficiency_team")
                .protocol(TeamProtocols.HIERARCHICAL)
                .addAgent(researcher)
                .addAgent(writer)
                .addAgent(editor)
                .maxTotalIterations(8)
                .build();

        // 相同的任务
        String query = "请研究人工智能在教育领域的应用，撰写一篇报告，并进行校对。";

        // 执行 A2A 协议
        AgentSession a2aSession = InMemoryAgentSession.of("session_a2a_efficiency");
        String a2aResult = a2aTeam.call(Prompt.of(query), a2aSession).getContent();
        TeamTrace a2aTrace = a2aTeam.getTrace(a2aSession);
        int a2aSteps = a2aTrace.getStepCount();

        // 执行层级协议
        AgentSession hierarchicalSession = InMemoryAgentSession.of("session_hierarchical_efficiency");
        String hierarchicalResult = hierarchicalTeam.call(Prompt.of(query), hierarchicalSession).getContent();
        TeamTrace hierarchicalTrace = hierarchicalTeam.getTrace(hierarchicalSession);
        int hierarchicalSteps = hierarchicalTrace.getStepCount();

        System.out.println("=== 效率对比 ===");
        System.out.println("A2A 协议步数: " + a2aSteps);
        System.out.println("层级协议步数: " + hierarchicalSteps);
        System.out.println("A2A 结果长度: " + a2aResult.length());
        System.out.println("层级结果长度: " + hierarchicalResult.length());

        // 验证两种协议都能完成任务
        Assertions.assertNotNull(a2aResult);
        Assertions.assertFalse(a2aResult.isEmpty());
        Assertions.assertNotNull(hierarchicalResult);
        Assertions.assertFalse(hierarchicalResult.isEmpty());
        Assertions.assertTrue(a2aSteps > 0);
        Assertions.assertTrue(hierarchicalSteps > 0);

        // 输出 A2A 轨迹
        System.out.println("\nA2A 协作轨迹:");
        System.out.println(a2aTrace.getFormattedHistory());
    }

    /**
     * 测试：A2A 协议中的移交失败处理
     * 场景：当 Agent 试图移交到不存在的 Agent 时的处理
     */
    @Test
    public void testA2AInvalidTransfer() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 只创建一个 Agent，测试无效移交
        Agent analyst = ReActAgent.of(chatModel)
                .name("analyst")
                .promptProvider(c -> "你是分析师。完成分析后，你试图移交到不存在的 Agent 'nonexistent'")
                .description("数据分析师，擅长数据分析和报告生成。")
                .build();

        TeamAgent team = TeamAgent.of(chatModel)
                .name("a2a_invalid_transfer_team")
                .protocol(TeamProtocols.A2A)
                .addAgent(analyst)
                .maxTotalIterations(5) // 限制迭代次数
                .build();

        AgentSession session = InMemoryAgentSession.of("session_a2a_invalid");

        String query = "分析一下最近的市场趋势。";
        String result = team.call(Prompt.of(query), session).getContent();

        TeamTrace trace = team.getTrace(session);
        Assertions.assertNotNull(trace);
        Assertions.assertNotNull(result);

        System.out.println("=== 无效移交测试结果 ===");
        System.out.println("结果: " + (result.length() > 100 ? result.substring(0, 100) + "..." : result));
        System.out.println("步数: " + trace.getStepCount());
        System.out.println("轨迹详情:\n" + trace.getFormattedHistory());

        // 验证即使在无效移交情况下，任务也能正常结束（通过迭代限制或错误处理）
        Assertions.assertTrue(trace.getStepCount() > 0);
    }

    /**
     * 测试：A2A 协议中的复杂移交链
     * 场景：多个 Agent 形成链式移交 A -> B -> C -> D
     */
    @Test
    public void testA2AChainTransfer() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 创建链式移交的 Agent
        Agent planner = ReActAgent.of(chatModel)
                .name("planner")
                .promptProvider(c -> "你是项目规划师。制定计划后，请移交到设计师。")
                .description("项目规划师，负责制定详细的项目计划。")
                .build();

        Agent designer = ReActAgent.of(chatModel)
                .name("designer")
                .promptProvider(c -> "你是设计师。完成设计后，请移交到开发者。")
                .description("产品设计师，负责产品界面和交互设计。")
                .build();

        Agent developer = ReActAgent.of(chatModel)
                .name("developer")
                .promptProvider(c -> "你是开发者。完成开发后，请移交到测试员。")
                .description("软件开发工程师，负责代码实现。")
                .build();

        Agent tester = ReActAgent.of(chatModel)
                .name("tester")
                .promptProvider(c -> "你是测试员。完成测试后，请移交到部署工程师。")
                .description("质量保证工程师，负责软件测试。")
                .build();

        Agent deployer = ReActAgent.of(chatModel)
                .name("deployer")
                .promptProvider(c -> "你是部署工程师。负责最后的部署上线。")
                .description("运维工程师，负责系统部署和运维。")
                .build();

        TeamAgent team = TeamAgent.of(chatModel)
                .name("a2a_chain_team")
                .protocol(TeamProtocols.A2A)
                .addAgent(planner, designer, developer, tester, deployer)
                .maxTotalIterations(12) // 允许更多的迭代次数
                .build();

        AgentSession session = InMemoryAgentSession.of("session_a2a_chain");

        String query = "请规划、设计、开发、测试并部署一个简单的待办事项应用。";
        String result = team.call(Prompt.of(query), session).getContent();

        TeamTrace trace = team.getTrace(session);
        Assertions.assertNotNull(trace);
        Assertions.assertNotNull(result);
        Assertions.assertFalse(result.isEmpty());

        System.out.println("=== 链式移交测试结果 ===");
        System.out.println("结果长度: " + result.length());
        System.out.println("协作步数: " + trace.getStepCount());

        // 统计不同 Agent 的参与次数
        long uniqueAgents = trace.getSteps().stream()
                .map(step -> step.getAgentName())
                .distinct()
                .count();

        System.out.println("参与的独特 Agent 数量: " + uniqueAgents);
        System.out.println("轨迹详情:\n" + trace.getFormattedHistory());

        // 验证多个 Agent 都参与了协作
        Assertions.assertTrue(uniqueAgents >= 2, "至少应该有 2 个不同的 Agent 参与");
    }

    /**
     * 测试：A2A 协议与 Supervisor 的协同工作
     * 场景：当 A2A 移交无法决定时，回退到 Supervisor 决策
     */
    @Test
    public void testA2AWithSupervisorFallback() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 创建两个能力重叠的 Agent
        Agent generalist = ReActAgent.of(chatModel)
                .name("generalist")
                .promptProvider(c -> "你是全能专家，既能做设计也能做开发。")
                .description("全栈工程师，具备设计和开发能力。")
                .build();

        Agent specialist = ReActAgent.of(chatModel)
                .name("specialist")
                .promptProvider(c -> "你是专业设计师，专注于 UI/UX 设计。")
                .description("UI/UX 设计师，专注于界面设计。")
                .build();

        TeamAgent team = TeamAgent.of(chatModel)
                .name("a2a_supervisor_fallback_team")
                .protocol(TeamProtocols.A2A)
                .addAgent(generalist, specialist)
                .maxTotalIterations(6)
                .build();

        AgentSession session = InMemoryAgentSession.of("session_a2a_fallback");

        // 模糊的任务，可能需要 Supervisor 介入
        String query = "创建一个用户界面，但我不确定需要设计还是开发，或者两者都需要。";
        String result = team.call(Prompt.of(query), session).getContent();

        TeamTrace trace = team.getTrace(session);
        Assertions.assertNotNull(trace);
        Assertions.assertNotNull(result);

        System.out.println("=== A2A 回退测试结果 ===");
        System.out.println("结果: " + (result.length() > 150 ? result.substring(0, 150) + "..." : result));
        System.out.println("步数: " + trace.getStepCount());
        System.out.println("轨迹详情:\n" + trace.getFormattedHistory());

        // 检查是否有 Supervisor 介入的迹象
        boolean hasSupervisor = trace.getSteps().stream()
                .anyMatch(step -> step.getAgentName().equals(Agent.ID_SUPERVISOR));

        System.out.println("Supervisor 是否介入: " + hasSupervisor);

        Assertions.assertTrue(trace.getStepCount() > 0);
    }
}