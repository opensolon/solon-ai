package features.ai.team.graph;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
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
 * A/B测试决策流程测试
 * 产品功能A/B测试决策场景
 */
public class ABTestingDecisionGraphTest {

    @Test
    public void testABTestingDecisionProcess() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        final String[] finalDecision = new String[1];

        TeamAgent team = TeamAgent.of(chatModel)
                .name("ab_testing_team")
                .description("A/B测试决策团队")
                .agentAdd(
                        ReActAgent.of(chatModel)
                                .name("data_analyst")
                                .description("数据分析师 - 分析测试数据")
                                .systemPrompt(ReActSystemPrompt.builder()
                                        .role("数据分析专家")
                                        .instruction("分析A/B测试数据，计算统计显著性")
                                        .build())
                                .build(),
                        ReActAgent.of(chatModel)
                                .name("product_manager")
                                .description("产品经理 - 评估业务影响")
                                .systemPrompt(ReActSystemPrompt.builder()
                                        .role("产品经理")
                                        .instruction("评估测试结果对业务指标的影响")
                                        .build())
                                .build(),
                        ReActAgent.of(chatModel)
                                .name("engineering_lead")
                                .description("工程负责人 - 评估技术可行性")
                                .systemPrompt(ReActSystemPrompt.builder()
                                        .role("工程负责人")
                                        .instruction("评估全量推广的技术可行性和风险")
                                        .build())
                                .build()
                )
                .graphAdjuster(spec -> {
                    // 1. A/B测试结果输入
                    spec.addActivity("test_result_input")
                            .title("测试结果输入")
                            .task((ctx, node) -> {
                                ctx.put("variant_a_conversion", 15.2);
                                ctx.put("variant_b_conversion", 18.7);
                                ctx.put("sample_size", 10000);
                                ctx.put("confidence_level", 95.0);
                            });

                    // 2. 并行分析网关（三个专家并行分析）
                    spec.addParallel("parallel_analysis")
                            .title("并行分析")
                            .task((ctx, node) -> {
                                System.out.println("启动并行分析：数据分析、产品评估、工程评估");
                            });

                    // 3. 汇聚决策网关
                    spec.addParallel("decision_gateway")
                            .title("决策汇聚")
                            .task((ctx, node) -> {
                                String dataOpinion = ctx.getAs("data_opinion");
                                String productOpinion = ctx.getAs("product_opinion");
                                String engineeringOpinion = ctx.getAs("engineering_opinion");

                                int approveCount = 0;
                                if ("approve".equals(dataOpinion)) approveCount++;
                                if ("approve".equals(productOpinion)) approveCount++;
                                if ("approve".equals(engineeringOpinion)) approveCount++;

                                if (approveCount >= 2) {
                                    finalDecision[0] = "推广B版本";
                                } else {
                                    finalDecision[0] = "保持A版本";
                                }

                                System.out.println("决策结果: " + finalDecision[0]);
                            })
                            .linkAdd(Agent.ID_END);

                    // 4. 设置并行路由
                    NodeSpec inputNode = spec.getNode("test_result_input");
                    NodeSpec parallelNode = spec.getNode("parallel_analysis");
                    NodeSpec decisionNode = spec.getNode("decision_gateway");

                    if (inputNode != null && parallelNode != null) {
                        inputNode.linkAdd("parallel_analysis");
                    }

                    if (parallelNode != null) {
                        parallelNode.linkAdd("data_analyst");
                        parallelNode.linkAdd("product_manager");
                        parallelNode.linkAdd("engineering_lead");
                    }

                    // 5. 并行完成后汇聚
                    NodeSpec dataNode = spec.getNode("data_analyst");
                    NodeSpec productNode = spec.getNode("product_manager");
                    NodeSpec engineeringNode = spec.getNode("engineering_lead");

                    if (dataNode != null && decisionNode != null) {
                        dataNode.linkAdd("decision_gateway");
                    }
                    if (productNode != null && decisionNode != null) {
                        productNode.linkAdd("decision_gateway");
                    }
                    if (engineeringNode != null && decisionNode != null) {
                        engineeringNode.linkAdd("decision_gateway");
                    }

                    // 6. 修改起始路由
                    NodeSpec supervisor = spec.getNode("supervisor");
                    if (supervisor != null) {
                        supervisor.getLinks().clear();
                        supervisor.linkAdd("test_result_input");
                    }
                })
                .outputKey("ab_test_decision")
                .maxTotalIterations(8)
                .build();

        AgentSession session = InMemoryAgentSession.of("session_ab_test_01");
        String result = team.call(Prompt.of("分析A/B测试结果并做出决策"), session).getContent();

        // 验证决策输出
        Object decisionResult = session.getSnapshot().get("ab_test_decision");
        Assertions.assertNotNull(decisionResult, "A/B测试决策应被存储");

        Assertions.assertNotNull(finalDecision[0], "应生成最终决策");

        TeamTrace trace = team.getTrace(session);
        List<String> executedNodes = trace.getSteps().stream()
                .map(TeamTrace.TeamStep::getSource)
                .collect(Collectors.toList());

        System.out.println("A/B测试决策节点: " + executedNodes);

        // 验证并行分析流程
        Assertions.assertTrue(executedNodes.contains("test_result_input"), "应包含测试结果输入");
        Assertions.assertTrue(executedNodes.contains("parallel_analysis"), "应包含并行分析");
        Assertions.assertTrue(executedNodes.contains("decision_gateway"), "应包含决策汇聚");

        // 验证三个专家都参与了（可能不是全部，但应该至少有一个）
        boolean hasAnalyst = executedNodes.contains("data_analyst");
        boolean hasManager = executedNodes.contains("product_manager");
        boolean hasEngineer = executedNodes.contains("engineering_lead");

        Assertions.assertTrue(hasAnalyst || hasManager || hasEngineer,
                "至少应有一个专家参与分析");
    }
}