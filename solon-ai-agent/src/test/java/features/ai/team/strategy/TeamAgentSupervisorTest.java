package features.ai.team.strategy;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamPromptProvider;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.flow.FlowContext;

/**
 * Supervisor 决策逻辑测试
 */
public class TeamAgentSupervisorTest {

    @Test
    public void testSupervisorDecisionLogic() throws Throwable {
        // 测试：Supervisor 的不同决策路径
        ChatModel chatModel = LlmUtil.getChatModel();

        // 创建两个有明显分工的 Agent
        Agent dataCollector = ReActAgent.builder(chatModel)
                .name("collector")
                .description("数据收集员，只收集不分析")
                .build();

        Agent analyzer = ReActAgent.builder(chatModel)
                .name("analyzer")
                .description("数据分析师，只分析不收集")
                .build();

        TeamAgent team = TeamAgent.builder(chatModel)
                .name("decision_team")
                .addAgent(dataCollector)
                .addAgent(analyzer)
                .maxTotalIterations(10)
                .build();

        FlowContext context = FlowContext.of("test_decision");
        String result = team.call(context, "分析一下最近的市场趋势");

        System.out.println("Supervisor 决策结果: " + result);

        // 检查轨迹，应该看到 Supervisor 的决策逻辑
        TeamTrace trace = context.getAs("__decision_team");
        Assertions.assertNotNull(trace);
        Assertions.assertTrue(trace.getStepCount() > 0);

        // 打印决策历史
        System.out.println("决策历史:\n" + trace.getFormattedHistory());
    }

    @Test
    public void testCustomPromptProvider() throws Throwable {
        // 测试：自定义提示词提供者
        ChatModel chatModel = LlmUtil.getChatModel();

        // 自定义提示词提供者
        TeamPromptProvider customProvider = (config, prompt) -> {
            return "你是团队监督员。当前任务: " + prompt.getUserContent() +
                    "\n团队成员: " + String.join(", ", config.getAgentMap().keySet()) +
                    "\n指令：根据协作历史决定下一步由谁执行。\n" +
                    "- 如果任务已圆满完成，请仅回复 '" + config.getFinishMarker() + "'。\n" +
                    "- 否则，请仅回复成员的名字（例如：'worker'）。";
        };

        TeamAgent team = TeamAgent.builder(chatModel)
                .name("custom_prompt_team")
                .addAgent(ReActAgent.builder(chatModel)
                        .name("worker")
                        .description("工作者")
                        .build())
                .promptProvider(customProvider)
                .build();

        FlowContext context = FlowContext.of("test_custom_prompt");
        String result = team.call(context, "简单任务");

        System.out.println("自定义提示词结果: " + result);
        Assertions.assertNotNull(result);
    }
}