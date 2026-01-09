package features.ai.team.strategy;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamPromptProvider;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;

/**
 * Supervisor 决策逻辑测试
 * <p>
 * 验证目标：
 * 1. 验证在多智能体团队中，Supervisor（主管）如何根据成员描述分发任务。
 * 2. 验证基于 AgentSession 的协作痕迹（Trace）记录与提取。
 * </p>
 */
public class TeamAgentSupervisorTest {

    /**
     * 测试：Supervisor 的标准决策路径
     */
    @Test
    public void testSupervisorDecisionLogic() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 创建两个有明显分工的 Agent
        Agent dataCollector = ReActAgent.of(chatModel)
                .name("collector")
                .description("数据收集员，负责从海量信息中抓取原始事实，不进行主观分析。")
                .build();

        Agent analyzer = ReActAgent.of(chatModel)
                .name("analyzer")
                .description("数据分析师，负责对收集到的事实进行逻辑加工，推导趋势，不负责基础收集。")
                .build();

        // 2. 构建团队智能体，默认协议通常即为 SUPERVISOR
        TeamAgent team = TeamAgent.of(chatModel)
                .name("decision_team")
                .addAgent(dataCollector)
                .addAgent(analyzer)
                .maxTotalIterations(10)
                .build();

        // 打印团队图结构的 YAML（包含节点与连接关系）
        System.out.println("--- 团队图结构 ---\n" + team.getGraph().toYaml());

        // 3. 使用 AgentSession 替换 FlowContext
        AgentSession session = InMemoryAgentSession.of("test_decision_session");

        // 4. 调用团队：由 Supervisor 进行初步评估并指派成员
        String query = "分析一下最近的市场趋势";
        String result = team.call(Prompt.of(query), session).getContent();

        System.out.println("Supervisor 决策结果: " + result);

        // 5. 检查轨迹：验证协作过程是否被正确记录在 Session 快照中
        // 在 3.8.x 中，TeamAgent 会自动维护 Trace，可通过接口便捷获取
        TeamTrace trace = team.getTrace(session);

        Assertions.assertNotNull(trace, "轨迹记录不应为空");
        Assertions.assertTrue(trace.getStepCount() > 0, "协作步骤应大于0");

        // 打印决策历史
        System.out.println("决策历史:\n" + trace.getFormattedHistory());
    }

    /**
     * 测试：自定义提示词提供者（控制 Supervisor 的思考逻辑）
     */
    @Test
    public void testCustomPromptProvider() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 定义自定义提示词提供者：精细化控制 Supervisor 的指派指令
        TeamPromptProvider customProvider = trace -> {
            return "你是团队监督员。当前任务: " + trace.getPrompt().getUserContent() +
                    "\n团队成员: " + String.join(", ", trace.getConfig().getAgentMap().keySet()) +
                    "\n指令：根据协作历史决定下一步由谁执行。\n" +
                    "- 如果任务已圆满完成，请仅回复 '" + trace.getConfig().getFinishMarker() + "'。\n" +
                    "- 否则，请仅回复成员的名字（例如：'worker'）。";
        };

        // 2. 构建包含自定义逻辑的团队
        TeamAgent team = TeamAgent.of(chatModel)
                .name("custom_prompt_team")
                .addAgent(ReActAgent.of(chatModel)
                        .name("worker")
                        .description("负责通用任务执行的工作者")
                        .build())
                .systemPrompt(customProvider)
                .build();

        // 3. 准备会话
        AgentSession session = InMemoryAgentSession.of("test_custom_prompt_session");

        // 4. 执行任务
        String result = team.call(Prompt.of("简单任务"), session).getContent();

        System.out.println("自定义提示词结果: " + result);

        Assertions.assertNotNull(result, "结果不应为空");

        // 验证轨迹提取
        TeamTrace trace = team.getTrace(session);
        Assertions.assertNotNull(trace);
        System.out.println("自定义模式协作轨迹:\n" + trace.getFormattedHistory());
    }
}