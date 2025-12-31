package features.ai.team;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.flow.FlowContext;

/**
 * TeamAgent 边界条件测试
 */
public class TeamAgentBoundaryTest {

    @Test
    public void testEmptyAgentList() {
        // 测试：无 Agent 的团队
        ChatModel chatModel = LlmUtil.getChatModel();

        Assertions.assertThrows(IllegalStateException.class, () -> {
            TeamAgent.builder(chatModel)
                    .name("empty_team")
                    .build(); // 没有 addAgent，应该抛异常或表现特定行为
        });
    }

    @Test
    public void testSingleAgentTeam() throws Throwable {
        // 测试：只有一个 Agent 的团队（无 Supervisor 决策场景）
        ChatModel chatModel = LlmUtil.getChatModel();

        Agent soloAgent = ReActAgent.builder(chatModel)
                .name("solo")
                .description("独行侠")
                .build();

        TeamAgent team = TeamAgent.builder(chatModel)
                .name("solo_team")
                .addAgent(soloAgent)
                .build();

        FlowContext context = FlowContext.of("test_solo");
        String result = team.call(context, "你好");

        Assertions.assertNotNull(result);
        System.out.println("单 Agent 团队结果: " + result);
    }

    @Test
    public void testMaxIterationsReached() throws Throwable {
        // 测试：达到最大迭代次数的情况
        ChatModel chatModel = LlmUtil.getChatModel();

        // 创建两个会互相传递的 Agent（模拟死循环）
        Agent agentA = ReActAgent.builder(chatModel)
                .name("agent_a")
                .description("A: 总是传给B")
                .build();

        Agent agentB = ReActAgent.builder(chatModel)
                .name("agent_b")
                .description("B: 总是传给A")
                .build();

        TeamAgent team = TeamAgent.builder(chatModel)
                .name("loop_team")
                .addAgent(agentA)
                .addAgent(agentB)
                .maxTotalIterations(3) // 很小，快速触发限制
                .build();

        FlowContext context = FlowContext.of("test_loop");
        String result = team.call(context, "测试循环");

        System.out.println("死循环结果: " + result);
        Assertions.assertNotNull(result);

        TeamTrace trace = team.getTrace(context);
        String history = trace.getFormattedHistory();

        // 应该触发最大迭代限制
        Assertions.assertTrue(history.contains("Maximum iterations reached") ||
                        history.contains("Loop detected"),
                "应该触发迭代限制: " + history);
    }

    @Test
    public void testNullPrompt() throws Throwable {
        // 测试：空提示词的情况
        ChatModel chatModel = LlmUtil.getChatModel();

        Agent agent = ReActAgent.builder(chatModel)
                .name("test_agent")
                .description("测试代理")
                .build();

        TeamAgent team = TeamAgent.builder(chatModel)
                .name("null_prompt_team")
                .addAgent(agent)
                .build();

        FlowContext context = FlowContext.of("test_null");

        // 先调用一次设置 prompt
        team.call(context, "初始提示");

        // 再传 null，应该使用之前的上下文
        String result = team.call(context);

        Assertions.assertNotNull(result);
        System.out.println("Null prompt 结果: " + result);
    }

    @Test
    public void testLargeTeamPerformance() throws Throwable {
        // 测试：大团队的创建和执行性能
        ChatModel chatModel = LlmUtil.getChatModel();

        TeamAgent.Builder builder = TeamAgent.builder(chatModel)
                .name("large_team");

        // 创建多个 Agent
        for (int i = 0; i < 5; i++) { // 可以调整数量
            builder.addAgent(ReActAgent.builder(chatModel)
                    .name("agent_" + i)
                    .description("第 " + i + " 个Agent")
                    .build());
        }

        TeamAgent team = builder.build();

        long startTime = System.currentTimeMillis();
        FlowContext context = FlowContext.of("perf_test");
        String result = team.call(context, "性能测试");
        long endTime = System.currentTimeMillis();

        System.out.println("大团队结果: " + result);
        System.out.println("大团队执行时间: " + (endTime - startTime) + "ms");
        Assertions.assertNotNull(result);

        // 检查轨迹数量
        TeamTrace trace = context.getAs("__large_team");
        Assertions.assertNotNull(trace);
        if (trace != null) {
            System.out.println("执行步数: " + trace.getStepCount());
            Assertions.assertEquals(5, trace.getStepCount());
        }
    }
}