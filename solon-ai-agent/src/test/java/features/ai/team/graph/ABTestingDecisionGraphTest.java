package features.ai.team.graph;

import demo.ai.llm.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.simple.SimpleAgent;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamProtocols;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A/B 测试决策流程测试
 * 场景：并行数据分析 -> 多数票共识决策 -> 结果自动回填上下文
 */
public class ABTestingDecisionGraphTest {

    @Test
    @DisplayName("测试 A/B 测试 Graph：验证并行节点执行与结果汇聚决策")
    public void testABTestingDecisionProcess() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // --- 1. 初始化专家角色 (利用 SimpleAgent 的 outputKey 自动回填能力) ---
        Agent dataAnalyst = createExpert(chatModel, "data_analyst", "数据分析专家", "data_opinion");
        Agent productManager = createExpert(chatModel, "product_manager", "产品经理", "product_opinion");
        Agent engineeringLead = createExpert(chatModel, "engineering_lead", "工程负责人", "engineering_opinion");

        TeamAgent team = TeamAgent.of(chatModel)
                .name("ab_testing_team")
                .protocol(TeamProtocols.NONE)
                .graphAdjuster(spec -> {

                    // A. 入口指派：从 Supervisor 指向数据准备节点
                    spec.addStart(Agent.ID_START)
                            .linkAdd("test_result_input");

                    // B. 数据准备 (Activity)：模拟从数据库/API 加载测试指标
                    spec.addActivity("test_result_input")
                            .title("准备测试数据")
                            .task((ctx, node) -> {
                                ctx.put("variant_a_conv", 15.2);
                                ctx.put("variant_b_conv", 18.7);
                                System.out.println(">>> [Node] 业务指标载入 Context");
                            })
                            .linkAdd("parallel_analysis");

                    // C. 并行分发 (Parallel)：同时触发三个专家的异步分析
                    spec.addParallel("parallel_analysis").title("启动多维度并行分析")
                            .linkAdd(dataAnalyst.name())
                            .linkAdd(productManager.name())
                            .linkAdd(engineeringLead.name());

                    // D. 结果汇聚：所有专家处理完后，自动跳转至决策网关
                    spec.addActivity(dataAnalyst).linkAdd("decision_gateway");
                    spec.addActivity(productManager).linkAdd("decision_gateway");
                    spec.addActivity(engineeringLead).linkAdd("decision_gateway");

                    // E. 共识决策 (Parallel 汇聚)：基于 Context 中的专家意见进行多数票表决
                    spec.addParallel("decision_gateway")
                            .title("多数票共识决策")
                            .task((ctx, node) -> {
                                // 提取由 SimpleAgent.outputKey 自动同步过来的结果
                                String d = ctx.getAs("data_opinion");
                                String p = ctx.getAs("product_opinion");
                                String e = ctx.getAs("engineering_opinion");

                                int approveCount = 0;
                                if (isApprove(d)) approveCount++;
                                if (isApprove(p)) approveCount++;
                                if (isApprove(e)) approveCount++;

                                String finalVerdict = (approveCount >= 2) ? "PROMOTED_B" : "RETAINED_A";
                                ctx.put("ab_test_decision", finalVerdict);
                                System.out.println(">>> [Decision] 赞成票: " + approveCount + ", 最终裁决: " + finalVerdict);
                            })
                            .linkAdd(Agent.ID_END);

                    spec.addEnd(Agent.ID_END);
                })
                .build();

        // --- 2. 运行流程 ---
        AgentSession session = InMemoryAgentSession.of("session_ab_test_01");
        String query = "当前 A 转化率 15.2%, B 转化率 18.7%。请给出你的评估意见（approve/reject）。";
        team.call(Prompt.of(query), session);

        // --- 3. 结果验证 ---

        // A. 验证业务 Activity 逻辑：数据是否成功写入上下文
        Assertions.assertEquals(15.2, session.getSnapshot().getAs("variant_a_conv"), "数据加载节点未执行");

        // B. 验证 Agent 参与轨迹：Trace 记录 AI 专家的交互足迹
        TeamTrace trace = team.getTrace(session);
        List<String> agentFootprints = trace.getRecords().stream()
                .map(TeamTrace.TeamRecord::getSource)
                .collect(Collectors.toList());

        System.out.println("AI 执行足迹: " + agentFootprints);
        Assertions.assertTrue(agentFootprints.contains("data_analyst"), "Trace 记录缺失专家节点");

        // C. 验证最终业务决策结果
        String decision = session.getSnapshot().getAs("ab_test_decision");
        Assertions.assertNotNull(decision, "决策结果未生成");
        System.out.println("测试成功。最终结论: " + decision);
    }

    /**
     * 构建专家 Agent，配置 outputKey 实现 Agent 输出与 Session Context 的自动映射
     */
    private Agent createExpert(ChatModel chatModel, String name, String role, String outputKey) {
        return SimpleAgent.of(chatModel)
                .name(name)
                .systemPrompt(p->p
                        .role(role)
                        .instruction("你负责评估 A/B 测试。如果 B 优于 A，回复 'approve'，否则回复 'reject'。只输出单词。"))
                .outputKey(outputKey) // 重要：Agent 执行完后会自动将 Content 写入 Context[outputKey]
                .chatOptions(o -> o.temperature(0.1F))
                .build();
    }

    private boolean isApprove(String opinion) {
        return opinion != null && opinion.toLowerCase().contains("approve");
    }
}