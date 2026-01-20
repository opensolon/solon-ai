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
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 多级审批流程测试
 * 场景：金额分级 -> 对应负责人审批 -> 自动归档
 */
public class MultiLevelApprovalGraphTest {

    @Test
    @DisplayName("测试审批 Graph：验证金额条件网关与专家分级审批的准确性")
    public void testMultiLevelApprovalProcess() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 定义审批专家（SimpleAgent）
        TeamAgent team = TeamAgent.of(chatModel)
                .name("approval_team")
                .agentAdd(
                        createApprover(chatModel, "department_approver", "部门经理", "同意报销"),
                        createApprover(chatModel, "finance_approver", "财务总监", "核对无误，准予报销"),
                        createApprover(chatModel, "ceo_approver", "CEO", "特批通过")
                )
                .graphAdjuster(spec -> {
                    // --- 节点定义与链路紧凑编排 ---

                    // A. 入口：主管 -> 提交申请
                    spec.getNode("supervisor").linkClear().linkAdd("submit_application");

                    // B. 提交申请 -> 金额网关
                    spec.addActivity("submit_application")
                            .title("提交申请")
                            .task((ctx, node) -> {
                                ctx.put("app_amount", 8000.0);
                                System.out.println(">>> [Node] 申请已提交，金额: 8000");
                            })
                            .linkAdd("amount_gateway");

                    // C. 金额网关 -> 决定下一步审批人 (不直接连 Agent，先由网关逻辑判定)
                    spec.addExclusive("amount_gateway")
                            .title("金额判定")
                            .task((ctx, node) -> {
                                Double amount = ctx.getAs("app_amount");
                                String target = (amount <= 5000) ? "department_approver" :
                                        (amount <= 20000) ? "finance_approver" : "ceo_approver";
                                ctx.put("next_approver", target);
                                System.out.println(">>> [Gateway] 路由至: " + target);
                            })
                            .linkAdd("department_approver", l -> l.when(ctx -> "department_approver".equals(ctx.get("next_approver"))))
                            .linkAdd("finance_approver", l -> l.when(ctx -> "finance_approver".equals(ctx.get("next_approver"))))
                            .linkAdd("ceo_approver", l -> l.when(ctx -> "ceo_approver".equals(ctx.get("next_approver"))));

                    // D. 审批 Agent 处理完成后 -> 统一汇聚到归档
                    spec.getNode("department_approver").linkClear().linkAdd("archive_approval");
                    spec.getNode("finance_approver").linkClear().linkAdd("archive_approval");
                    spec.getNode("ceo_approver").linkClear().linkAdd("archive_approval");

                    // E. 归档节点 -> 结束
                    spec.addActivity("archive_approval")
                            .title("自动归档")
                            .task((ctx, node) -> {
                                ctx.put("archive_status", "SUCCESS");
                                System.out.println(">>> [Node] 审批流完成，数据已归档");
                            })
                            .linkAdd(Agent.ID_END);
                })
                .build();

        // 2. 执行
        AgentSession session = InMemoryAgentSession.of("session_approval_01");
        team.call(Prompt.of("请处理这笔差旅费报销申请"), session);

        // 3. 结果验证 (Agent 走 Trace，Activity 走 Context)
        TeamTrace trace = team.getTrace(session);
        List<String> agentSteps = trace.getRecords().stream()
                .map(TeamTrace.TeamRecord::getSource)
                .collect(Collectors.toList());

        System.out.println("AI 专家审批足迹: " + String.join(" -> ", agentSteps));

        // --- 断言逻辑 ---

        // 验证 Activity 执行状态
        Assertions.assertEquals(8000.0, session.getSnapshot().get("app_amount"));
        Assertions.assertEquals("SUCCESS", session.getSnapshot().get("archive_status"));

        // 验证 Agent 路径
        Assertions.assertTrue(agentSteps.contains("finance_approver"), "8000元应由财务总监审批");
        Assertions.assertFalse(agentSteps.contains("department_approver"), "不应走部门经理审批");
        Assertions.assertFalse(agentSteps.contains("ceo_approver"), "不应走 CEO 审批");

        System.out.println("单测成功：多级审批流转精准。");
    }

    private Agent createApprover(ChatModel chatModel, String name, String role, String opinion) {
        return SimpleAgent.of(chatModel)
                .name(name)
                .systemPrompt(p->p
                        .role(role)
                        .instruction("你是" + role + "。请基于报销事由给出简短审批意见。参考回复：" + opinion))
                .build();
    }
}