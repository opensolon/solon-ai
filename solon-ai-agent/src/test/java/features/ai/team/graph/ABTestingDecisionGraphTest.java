package features.ai.team.graph;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.simple.SimpleAgent;
import org.noear.solon.ai.agent.simple.SimpleSystemPrompt;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A/B测试决策流程测试（优化版）
 */
public class ABTestingDecisionGraphTest {

    @Test
    @DisplayName("测试 A/B 测试 Graph：验证并行节点执行与结果汇聚决策")
    public void testABTestingDecisionProcess() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 使用 SimpleAgent 的原生 outputKey 自动回填 Context
        Agent dataAnalyst = createExpert(chatModel, "data_analyst", "数据分析专家", "data_opinion");
        Agent productManager = createExpert(chatModel, "product_manager", "产品经理", "product_opinion");
        Agent engineeringLead = createExpert(chatModel, "engineering_lead", "工程负责人", "engineering_opinion");

        TeamAgent team = TeamAgent.of(chatModel)
                .name("ab_testing_team")
                .agentAdd(dataAnalyst, productManager, engineeringLead)
                .graphAdjuster(spec -> {
                    // 让主管先去加载数据
                    spec.getNode("supervisor").linkClear().linkAdd("test_result_input");

                    // Activity 节点：负责业务逻辑处理
                    spec.addActivity("test_result_input")
                            .title("准备测试数据")
                            .task((ctx, node) -> {
                                ctx.put("variant_a_conv", 15.2);
                                ctx.put("variant_b_conv", 18.7);
                                System.out.println(">>> [Node] 业务数据载入成功");
                            })
                            .linkAdd("parallel_analysis");

                    // 并行网关
                    spec.addParallel("parallel_analysis").title("并行分析开始")
                            .linkAdd(dataAnalyst.name())
                            .linkAdd(productManager.name())
                            .linkAdd(engineeringLead.name());

                    // 专家节点汇聚
                    spec.getNode(dataAnalyst.name()).linkClear().linkAdd("decision_gateway");
                    spec.getNode(productManager.name()).linkClear().linkAdd("decision_gateway");
                    spec.getNode(engineeringLead.name()).linkClear().linkAdd("decision_gateway");

                    // 决策节点
                    spec.addParallel("decision_gateway")
                            .title("多数票决策")
                            .task((ctx, node) -> {
                                // 直接从 Context 获取各 Agent 自动回填的结果
                                String d = ctx.getAs("data_opinion");
                                String p = ctx.getAs("product_opinion");
                                String e = ctx.getAs("engineering_opinion");

                                int approveCount = 0;
                                if (isApprove(d)) approveCount++;
                                if (isApprove(p)) approveCount++;
                                if (isApprove(e)) approveCount++;

                                String finalVerdict = (approveCount >= 2) ? "PROMOTED_B" : "RETAINED_A";
                                ctx.put("ab_test_decision", finalVerdict);
                                System.out.println(">>> [Decision] 统计赞成票: " + approveCount + ", 最终裁决: " + finalVerdict);
                            })
                            .linkAdd(Agent.ID_END);
                })
                .build();

        // 2. 执行
        AgentSession session = InMemoryAgentSession.of("session_ab_test_01");
        String query = "当前 A 转化率 15.2%, B 转化率 18.7%。请给出你的评估意见（approve/reject）。";
        team.call(Prompt.of(query), session);

        // 3. 断言优化
        // A. 验证业务逻辑节点是否生效（通过观察上下文变量）
        Assertions.assertEquals(15.2, session.getSnapshot().getAs("variant_a_conv"), "Activity 节点未执行或数据丢失");

        // B. 验证 Agent 执行轨迹（Trace 记录 Agent 足迹）
        TeamTrace trace = team.getTrace(session);
        List<String> agentFootprints = trace.getSteps().stream()
                .map(TeamTrace.TeamStep::getSource)
                .collect(Collectors.toList());

        System.out.println("Agent 执行足迹: " + agentFootprints);
        Assertions.assertTrue(agentFootprints.contains("data_analyst"), "Trace 中缺失专家记录");

        // C. 验证最终业务决策
        String decision = session.getSnapshot().getAs("ab_test_decision");
        Assertions.assertNotNull(decision);
        System.out.println("测试通过，最终决策结果: " + decision);
    }

    private Agent createExpert(ChatModel chatModel, String name, String role, String outputKey) {
        // 充分利用 SimpleAgent 设计：outputKey 会在 Agent.call 结束时自动同步到 session
        return SimpleAgent.of(chatModel)
                .name(name)
                .systemPrompt(SimpleSystemPrompt.builder()
                        .role(role)
                        .instruction("你负责评估 A/B 测试。如果 B 优于 A，回复 'approve'，否则回复 'reject'。只输出单词。")
                        .build())
                .outputKey(outputKey) // 利用原生 outputKey，无需手动写 handler 注入
                .chatOptions(o -> o.temperature(0.1F))
                .build();
    }

    private boolean isApprove(String opinion) {
        return opinion != null && opinion.toLowerCase().contains("approve");
    }
}