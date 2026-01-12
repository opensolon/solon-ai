package features.ai.team.graph;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActSystemPrompt;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.flow.NodeSpec;

import java.util.List;
import java.util.stream.Collectors;

/**
 * TDD代码审查流程测试
 * 测试驱动开发场景：测试先行 -> 开发 -> 审查
 */
public class TddCodeReviewGraphTest {

    @Test
    public void testCodeReviewProcess() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        TeamAgent team = TeamAgent.of(chatModel)
                .name("tdd_team")
                .description("测试驱动开发团队")
                .agentAdd(
                        ReActAgent.of(chatModel)
                                .name("tester")
                                .description("测试工程师 - 编写测试用例")
                                .systemPrompt(ReActSystemPrompt.builder()
                                        .role("测试专家")
                                        .instruction("为需求编写详细的测试用例，包括边界条件和异常场景")
                                        .build())
                                .build(),
                        ReActAgent.of(chatModel)
                                .name("developer")
                                .description("开发工程师 - 实现功能")
                                .systemPrompt(ReActSystemPrompt.builder()
                                        .role("开发专家")
                                        .instruction("根据测试用例实现功能代码，确保所有测试通过")
                                        .build())
                                .build(),
                        ReActAgent.of(chatModel)
                                .name("reviewer")
                                .description("代码审查员 - 审查代码质量")
                                .systemPrompt(ReActSystemPrompt.builder()
                                        .role("代码审查专家")
                                        .instruction("审查代码是否符合规范，性能是否达标，安全性是否有保障")
                                        .build())
                                .build()
                )
                .graphAdjuster(spec -> {
                    // 1. supervisor 首先路由到 tester
                    NodeSpec supervisor = spec.getNode("supervisor");
                    if (supervisor != null) {
                        supervisor.getLinks().clear();
                        supervisor.linkAdd("tester", l -> l
                                .title("TDD流程：测试先行")
                                .when(ctx -> true));
                    }

                    // 2. tester 完成后到 developer
                    NodeSpec testerNode = spec.getNode("tester");
                    if (testerNode != null) {
                        testerNode.getLinks().clear();
                        testerNode.linkAdd("developer");
                    }

                    // 3. developer 完成后到 reviewer
                    NodeSpec developerNode = spec.getNode("developer");
                    if (developerNode != null) {
                        developerNode.getLinks().clear();
                        developerNode.linkAdd("reviewer");
                    }

                    // 4. 添加质量检查节点
                    spec.addActivity("quality_check")
                            .title("质量检查节点")
                            .task((ctx, node) -> {
                                TeamTrace trace = ctx.getAs("__tdd_team");
                                if (trace != null) {
                                    long testSteps = trace.getSteps().stream()
                                            .filter(s -> s.getSource().equals("tester"))
                                            .count();
                                    long devSteps = trace.getSteps().stream()
                                            .filter(s -> s.getSource().equals("developer"))
                                            .count();

                                    System.out.println(String.format(
                                            "质量检查：测试执行 %d 次，开发执行 %d 次",
                                            testSteps, devSteps));

                                    if (testSteps == 0) {
                                        ctx.put("quality_issue", "缺少测试用例");
                                    }
                                }
                            });

                    // 5. reviewer 完成后到 quality_check
                    NodeSpec reviewerNode = spec.getNode("reviewer");
                    if (reviewerNode != null) {
                        reviewerNode.getLinks().clear();
                        reviewerNode.linkAdd("quality_check");
                    }
                })
                .outputKey("code_review_result")
                .maxTotalIterations(6)
                .build();

        System.out.println("--- TDD团队图结构 ---");
        System.out.println(team.getGraph().toYaml());

        AgentSession session = InMemoryAgentSession.of("session_tdd_01");
        String query = "实现一个用户登录功能，包含用户名密码验证和记住我选项";
        String result = team.call(Prompt.of(query), session).getContent();

        TeamTrace trace = team.getTrace(session);
        List<String> executionOrder = trace.getSteps().stream()
                .filter(s -> !s.getSource().equals("supervisor"))
                .map(TeamTrace.TeamStep::getSource)
                .collect(Collectors.toList());

        System.out.println("TDD执行顺序: " + executionOrder);

        // 验证TDD流程：测试先行
        if (executionOrder.size() >= 1) {
            Assertions.assertEquals("tester", executionOrder.get(0),
                    "TDD流程要求测试先行");
        }

        // 验证输出结果存储
        Object reviewResult = session.getSnapshot().get("code_review_result");
        Assertions.assertNotNull(reviewResult, "outputKey 应该存储最终结果");

        Assertions.assertTrue(trace.getStepCount() > 0);
    }
}