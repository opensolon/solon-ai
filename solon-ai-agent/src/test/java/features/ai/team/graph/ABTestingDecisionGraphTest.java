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
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.flow.NodeSpec;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A/B测试决策流程测试
 * 场景：并行分析 + 汇聚决策 + 结果上下文透传
 */
public class ABTestingDecisionGraphTest {

    @Test
    @DisplayName("测试 A/B 测试 Graph：验证并行节点执行与结果汇聚决策")
    public void testABTestingDecisionProcess() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 定义专家：使用 SimpleAgent 并通过 handler 实现结果自动提取到上下文
        // 我们通过包装一个简单的逻辑，让 AI 的输出直接影响上下文变量
        Agent dataAnalyst = createExpert(chatModel, "data_analyst", "数据分析专家", "data_opinion");
        Agent productManager = createExpert(chatModel, "product_manager", "产品经理", "product_opinion");
        Agent engineeringLead = createExpert(chatModel, "engineering_lead", "工程负责人", "engineering_opinion");

        TeamAgent team = TeamAgent.of(chatModel)
                .name("ab_testing_team")
                .agentAdd(dataAnalyst, productManager, engineeringLead)
                .graphAdjuster(spec -> {
                    // --- 节点定义 ---

                    spec.getNode("supervisor")
                            .linkClear()
                            .linkAdd("test_result_input");

                    spec.addActivity("test_result_input")
                            .title("准备测试数据")
                            .task((ctx, node) -> {
                                ctx.put("variant_a_conv", 15.2);
                                ctx.put("variant_b_conv", 18.7); // B 更好
                                System.out.println(">>> [Node] 数据已载入上下文");
                            })
                            .linkAdd("parallel_analysis");

                    spec.addParallel("parallel_analysis").title("并行分析开始")
                            .linkAdd(dataAnalyst.name())
                            .linkAdd(productManager.name())
                            .linkAdd(engineeringLead.name());

                    spec.getNode(dataAnalyst.name()).linkClear().linkAdd("decision_gateway");
                    spec.getNode(productManager.name()).linkClear().linkAdd("decision_gateway");
                    spec.getNode(engineeringLead.name()).linkClear().linkAdd("decision_gateway");

                    spec.addParallel("decision_gateway")
                            .title("多数票决策")
                            .task((ctx, node) -> {
                                String d = ctx.getAs("data_opinion");
                                String p = ctx.getAs("product_opinion");
                                String e = ctx.getAs("engineering_opinion");

                                int approveCount = 0;
                                if ("approve".equalsIgnoreCase(d)) approveCount++;
                                if ("approve".equalsIgnoreCase(p)) approveCount++;
                                if ("approve".equalsIgnoreCase(e)) approveCount++;

                                String finalVerdict = (approveCount >= 2) ? "PROMOTED_B" : "RETAINED_A";
                                ctx.put("ab_test_decision", finalVerdict);
                                System.out.println(">>> [Decision] 赞成票: " + approveCount + ", 最终决策: " + finalVerdict);
                            })
                            .linkAdd(Agent.ID_END);
                })
                .build();

        // 2. 执行任务
        AgentSession session = InMemoryAgentSession.of("session_ab_test_01");
        // Prompt 中带入数据特征，引导模型输出 approve
        String query = "当前 A 转化率 15%, B 转化率 18%。请分析是否可以推广 B？";
        team.call(Prompt.of(query), session);

        // 3. 验证与断言
        TeamTrace trace = team.getTrace(session);
        List<String> executedNodes = trace.getSteps().stream()
                .map(TeamTrace.TeamStep::getSource)
                .collect(Collectors.toList());

        System.out.println("实际执行路径: " + executedNodes);

        // 检测点 1: 关键业务节点必须存在于 Trace 中
        Assertions.assertTrue(executedNodes.contains("test_result_input"));
        Assertions.assertTrue(executedNodes.contains("decision_gateway"));

        // 检测点 2: 验证专家参与度（并行节点）
        Assertions.assertTrue(executedNodes.contains("data_analyst") ||
                executedNodes.contains("product_manager"), "并行专家节点未触发");

        // 检测点 3: 验证决策逻辑结果是否已写入上下文
        String decision = session.getSnapshot().getAs("ab_test_decision");
        Assertions.assertNotNull(decision, "决策结果未写入 Context");
        System.out.println("单元测试成功。最终决策结果: " + decision);
    }

    /**
     * 辅助方法：创建一个能自动将结果写入上下文的专家 Agent
     */
    private Agent createExpert(ChatModel chatModel, String name, String role, String outputKey) {
        return SimpleAgent.of(chatModel)
                .name(name)
                .systemPrompt(SimpleSystemPrompt.builder()
                        .role(role)
                        .instruction("你负责评估 A/B 测试。如果 B 优于 A，回复 'approve'，否则回复 'reject'。只输出单词。")
                        .build())
                .handler((prompt, session) -> {
                    // 执行模型调用
                    AssistantMessage msg = chatModel.prompt(prompt).call().getMessage();
                    String content = msg.getContent().toLowerCase();
                    // 模拟结果提取逻辑：将结果注入 FlowContext
                    session.getSnapshot().put(outputKey, content.contains("approve") ? "approve" : "reject");
                    return msg;
                })
                .build();
    }
}