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
import org.noear.solon.flow.NodeSpec;

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

        // 1. 定义审批专家（SimpleAgent）：专注于给出审批意见
        TeamAgent team = TeamAgent.of(chatModel)
                .name("approval_team")
                .agentAdd(
                        createApprover(chatModel, "department_approver", "部门经理", "同意报销，金额在权限范围内。"),
                        createApprover(chatModel, "finance_approver", "财务总监", "核对票据无误，准予报销。"),
                        createApprover(chatModel, "ceo_approver", "CEO", "符合公司年度战略支出，特批通过。")
                )
                .graphAdjuster(spec -> {
                    // 2. 提交申请节点
                    spec.addActivity("submit_application")
                            .task((ctx, node) -> {
                                ctx.put("app_amount", 8000.0); // 设置测试金额
                                System.out.println(">>> [Node] 申请已提交，金额: 8000");
                            });

                    // 3. 金额网关 (Exclusive)
                    spec.addExclusive("amount_gateway")
                            .task((ctx, node) -> {
                                Double amount = ctx.getAs("app_amount");
                                String target = (amount <= 5000) ? "department_approver" :
                                        (amount <= 20000) ? "finance_approver" : "ceo_approver";
                                ctx.put("next_approver", target);
                                System.out.println(">>> [Gateway] 判定审批人: " + target);
                            });

                    // 4. 归档节点 (汇聚点)
                    spec.addActivity("archive_approval")
                            .task((ctx, node) -> {
                                System.out.println(">>> [Node] 审批流完成，数据已存入 ERP 归档");
                            })
                            .linkAdd(Agent.ID_END);

                    // --- 5. 精准路由配置 ---

                    // A. 修改 Supervisor 默认路径
                    spec.getNode("supervisor").getLinks().clear();
                    spec.getNode("supervisor").linkAdd("submit_application");

                    // B. 申请 -> 网关
                    spec.getNode("submit_application").linkAdd("amount_gateway");

                    // C. 网关分流（基于 next_approver 变量）
                    NodeSpec gatewayNode = spec.getNode("amount_gateway");
                    gatewayNode.linkAdd("department_approver", l -> l.when(ctx -> "department_approver".equals(ctx.get("next_approver"))));
                    gatewayNode.linkAdd("finance_approver", l -> l.when(ctx -> "finance_approver".equals(ctx.get("next_approver"))));
                    gatewayNode.linkAdd("ceo_approver", l -> l.when(ctx -> "ceo_approver".equals(ctx.get("next_approver"))));

                    // D. 所有审批节点完成后汇聚到归档
                    spec.getNode("department_approver").getLinks().clear();
                    spec.getNode("department_approver").linkAdd("archive_approval");

                    spec.getNode("finance_approver").getLinks().clear();
                    spec.getNode("finance_approver").linkAdd("archive_approval");

                    spec.getNode("ceo_approver").getLinks().clear();
                    spec.getNode("ceo_approver").linkAdd("archive_approval");
                })
                .build();

        // 6. 执行流程
        AgentSession session = InMemoryAgentSession.of("session_approval_01");
        team.call(Prompt.of("请处理这笔差旅费报销申请"), session);

        // 7. 轨迹验证
        TeamTrace trace = team.getTrace(session);
        List<String> executedNodes = trace.getSteps().stream()
                .map(TeamTrace.TeamStep::getSource)
                .collect(Collectors.toList());

        System.out.println("审批执行链路: " + String.join(" -> ", executedNodes));

        // 断言
        Assertions.assertTrue(executedNodes.contains("submit_application"));
        Assertions.assertTrue(executedNodes.contains("amount_gateway"));
        Assertions.assertTrue(executedNodes.contains("finance_approver"), "8000元报销未流转至财务审批");
        Assertions.assertFalse(executedNodes.contains("ceo_approver"), "不应触发 CEO 审批");
        Assertions.assertTrue(executedNodes.contains("archive_approval"));
    }

    private Agent createApprover(ChatModel chatModel, String name, String role, String opinion) {
        return SimpleAgent.of(chatModel)
                .name(name)
                .systemPrompt(SimpleSystemPrompt.builder()
                        .role(role)
                        .instruction("你是" + role + "。请基于报销事由给出简短审批意见。参考回复：" + opinion)
                        .build())
                .build();
    }
}