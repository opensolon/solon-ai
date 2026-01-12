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
 * 多级审批流程测试
 * 企业费用报销审批场景
 */
public class MultiLevelApprovalGraphTest {

    @Test
    public void testMultiLevelApprovalProcess() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        final StringBuilder approvalLog = new StringBuilder();

        TeamAgent team = TeamAgent.of(chatModel)
                .name("approval_team")
                .description("多级审批团队")
                .agentAdd(
                        ReActAgent.of(chatModel)
                                .name("department_approver")
                                .description("部门审批人 - 审批部门内费用")
                                .systemPrompt(ReActSystemPrompt.builder()
                                        .role("部门经理")
                                        .instruction("审批部门内的费用报销，金额小于5000元可直接审批")
                                        .build())
                                .build(),
                        ReActAgent.of(chatModel)
                                .name("finance_approver")
                                .description("财务审批人 - 审批大额费用")
                                .systemPrompt(ReActSystemPrompt.builder()
                                        .role("财务总监")
                                        .instruction("审批金额超过5000元的费用报销，检查票据合规性")
                                        .build())
                                .build(),
                        ReActAgent.of(chatModel)
                                .name("ceo_approver")
                                .description("CEO审批人 - 审批特大额费用")
                                .systemPrompt(ReActSystemPrompt.builder()
                                        .role("CEO")
                                        .instruction("审批金额超过20000元的费用报销，考虑预算和战略意义")
                                        .build())
                                .build()
                )
                .graphAdjuster(spec -> {
                    // 1. 添加申请提交节点
                    spec.addActivity("submit_application")
                            .title("提交申请")
                            .task((ctx, node) -> {
                                approvalLog.append("申请已提交 -> ");
                                double amount = 8000.0; // 测试用例：8000元
                                ctx.put("application_amount", amount);
                                ctx.put("applicant", "张三");
                                ctx.put("purpose", "项目差旅费");
                            });

                    // 2. 添加金额判断网关
                    spec.addExclusive("amount_gateway")
                            .title("金额判断网关")
                            .task((ctx, node) -> {
                                Double amount = ctx.getAs("application_amount");
                                if (amount != null) {
                                    if (amount <= 5000) {
                                        ctx.put("next_approver", "department_approver");
                                    } else if (amount <= 20000) {
                                        ctx.put("next_approver", "finance_approver");
                                    } else {
                                        ctx.put("next_approver", "ceo_approver");
                                    }
                                }
                            });

                    // 3. 设置条件路由
                    NodeSpec submitNode = spec.getNode("submit_application");
                    if (submitNode != null) {
                        submitNode.linkAdd("amount_gateway");
                    }

                    NodeSpec gatewayNode = spec.getNode("amount_gateway");
                    if (gatewayNode != null) {
                        gatewayNode.linkAdd("department_approver", l -> l
                                .when(ctx -> "department_approver".equals(ctx.get("next_approver"))));
                        gatewayNode.linkAdd("finance_approver", l -> l
                                .when(ctx -> "finance_approver".equals(ctx.get("next_approver"))));
                        gatewayNode.linkAdd("ceo_approver", l -> l
                                .when(ctx -> "ceo_approver".equals(ctx.get("next_approver"))));
                    }

                    // 4. 添加归档节点
                    spec.addActivity("archive_approval")
                            .title("审批归档")
                            .task((ctx, node) -> {
                                approvalLog.append("审批完成，已归档");
                                String approver = ctx.getAs("final_approver");
                                if (approver != null) {
                                    System.out.println("最终审批人: " + approver);
                                }
                            })
                            .linkAdd(Agent.ID_END);

                    // 5. 所有审批人完成后都到归档节点
                    NodeSpec deptNode = spec.getNode("department_approver");
                    NodeSpec financeNode = spec.getNode("finance_approver");
                    NodeSpec ceoNode = spec.getNode("ceo_approver");

                    if (deptNode != null) deptNode.linkAdd("archive_approval");
                    if (financeNode != null) financeNode.linkAdd("archive_approval");
                    if (ceoNode != null) ceoNode.linkAdd("archive_approval");

                    // 6. 修改起始路由
                    NodeSpec supervisor = spec.getNode("supervisor");
                    if (supervisor != null) {
                        supervisor.getLinks().clear();
                        supervisor.linkAdd("submit_application");
                    }
                })
                .maxTotalIterations(5)
                .build();

        System.out.println("--- 审批流程图结构 ---");
        System.out.println(team.getGraph().toYaml());

        AgentSession session = InMemoryAgentSession.of("session_approval_01");
        String result = team.call(Prompt.of("处理费用报销审批流程"), session).getContent();

        System.out.println("审批日志: " + approvalLog.toString());

        TeamTrace trace = team.getTrace(session);
        List<String> executedNodes = trace.getSteps().stream()
                .map(TeamTrace.TeamStep::getSource)
                .collect(Collectors.toList());

        System.out.println("执行的节点: " + executedNodes);

        // 验证关键节点
        Assertions.assertTrue(executedNodes.contains("submit_application"), "应包含提交申请");
        Assertions.assertTrue(executedNodes.contains("amount_gateway"), "应包含金额判断");
        Assertions.assertTrue(executedNodes.contains("archive_approval"), "应包含归档");

        // 根据金额8000，应该路由到财务审批
        Assertions.assertTrue(executedNodes.contains("finance_approver"),
                "8000元应路由到财务审批");
    }
}