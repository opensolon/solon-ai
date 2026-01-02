package features.ai.agent.team;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.TaskComponent;

/**
 * TeamAgent 异常处理测试
 */
public class TeamAgentExceptionTest {

    @Test
    public void testAgentExceptionPropagation() throws Throwable {
        // 测试：Agent 抛异常时，团队是否能正确处理
        ChatModel chatModel = LlmUtil.getChatModel();

        Agent throwingAgent = new Agent() {
            @Override
            public String name() { return "trouble_maker"; }

            @Override
            public String description() { return "总是出问题的Agent"; }

            @Override
            public String call(FlowContext context, Prompt prompt) throws Throwable {
                throw new RuntimeException("模拟Agent内部异常");
            }
        };

        TeamAgent team = TeamAgent.of(chatModel)
                .name("exception_team")
                .addAgent(throwingAgent)
                .build();

        FlowContext context = FlowContext.of("test_exception");

        // 应该能捕获异常，而不是直接崩溃
        try {
            String result = team.call(context, "触发异常");
            // 如果能正常返回，应该包含错误信息
            Assertions.assertNotNull(result);
            System.out.println("异常处理后结果: " + result);
        } catch (Exception e) {
            // 或者异常能正确传播
            Assertions.assertTrue(e.getCause().getMessage().contains("模拟Agent内部异常"));
        }
    }

    @Test
    public void testGraphNodeException() throws Throwable {
        // 测试：Graph 节点抛异常
        ChatModel chatModel = LlmUtil.getChatModel();

        TeamAgent team = TeamAgent.of(chatModel)
                .name("graph_exception_team")
                .graphAdjuster(spec -> {
                    spec.addStart(Agent.ID_START).linkAdd("problem_node");
                    spec.addActivity("problem_node")
                            .task(new TaskComponent() {
                                @Override
                                public void run(FlowContext context, org.noear.solon.flow.Node node) throws Throwable {
                                    throw new IllegalStateException("节点执行异常");
                                }
                            })
                            .linkAdd(Agent.ID_END);
                    spec.addEnd(Agent.ID_END);
                })
                .build();

        FlowContext context = FlowContext.of("test_graph_exception");

        try {
            team.call(context, "测试");
            Assertions.fail("应该抛出异常");
        } catch (Exception e) {
            Assertions.assertTrue(e.getCause().getMessage().contains("节点执行异常"));
        }
    }
}