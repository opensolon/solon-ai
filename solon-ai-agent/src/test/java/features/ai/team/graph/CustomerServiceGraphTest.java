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
 * 客户服务流程测试
 * 电商平台客户咨询处理场景
 */
public class CustomerServiceGraphTest {

    @Test
    public void testCustomerServiceProcess() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        final String[] finalSolution = new String[1];

        TeamAgent team = TeamAgent.of(chatModel)
                .name("customer_service_team")
                .description("客户服务团队")
                .agentAdd(
                        ReActAgent.of(chatModel)
                                .name("reception")
                                .description("接待客服 - 初步接待")
                                .systemPrompt(ReActSystemPrompt.builder()
                                        .role("接待客服")
                                        .instruction("接待客户咨询，了解基本问题，进行初步分类")
                                        .build())
                                .build(),
                        ReActAgent.of(chatModel)
                                .name("technical_support")
                                .description("技术支持 - 处理技术问题")
                                .systemPrompt(ReActSystemPrompt.builder()
                                        .role("技术支持")
                                        .instruction("处理产品使用、技术故障等专业问题")
                                        .build())
                                .build(),
                        ReActAgent.of(chatModel)
                                .name("billing_support")
                                .description("财务支持 - 处理账单问题")
                                .systemPrompt(ReActSystemPrompt.builder()
                                        .role("财务客服")
                                        .instruction("处理账单、退款、支付等财务问题")
                                        .build())
                                .build(),
                        ReActAgent.of(chatModel)
                                .name("complaint_handler")
                                .description("投诉处理 - 处理客户投诉")
                                .systemPrompt(ReActSystemPrompt.builder()
                                        .role("投诉处理专员")
                                        .instruction("处理客户投诉，提供解决方案和补偿")
                                        .build())
                                .build()
                )
                .graphAdjuster(spec -> {
                    // 1. 客户接入节点
                    spec.addActivity("customer_checkin")
                            .title("客户接入")
                            .task((ctx, node) -> {
                                String problemType = "technical";
                                ctx.put("problem_type", problemType);
                                ctx.put("customer_id", "CUST20240115001");
                                ctx.put("checkin_time", System.currentTimeMillis());
                            });

                    // 2. 问题分类网关
                    spec.addExclusive("problem_gateway")
                            .title("问题分类")
                            .task((ctx, node) -> {
                                String type = ctx.getAs("problem_type");
                                if ("technical".equals(type)) {
                                    ctx.put("handler", "technical_support");
                                } else if ("billing".equals(type)) {
                                    ctx.put("handler", "billing_support");
                                } else if ("complaint".equals(type)) {
                                    ctx.put("handler", "complaint_handler");
                                }
                            });

                    // 3. 所有问题都先经过接待客服
                    NodeSpec receptionNode = spec.getNode("reception");
                    if (receptionNode != null) {
                        receptionNode.getLinks().clear();
                        receptionNode.linkAdd("technical_support", l -> l
                                .when(ctx -> "technical_support".equals(ctx.get("handler"))));
                        receptionNode.linkAdd("billing_support", l -> l
                                .when(ctx -> "billing_support".equals(ctx.get("handler"))));
                        receptionNode.linkAdd("complaint_handler", l -> l
                                .when(ctx -> "complaint_handler".equals(ctx.get("handler"))));
                    }

                    // 4. 添加满意度调查节点
                    spec.addActivity("satisfaction_survey")
                            .title("满意度调查")
                            .task((ctx, node) -> {
                                String handler = ctx.getAs("handler");
                                System.out.println("问题由 " + handler + " 处理完成，进行满意度调查");
                                finalSolution[0] = "问题已解决，等待客户反馈";
                            })
                            .linkAdd(Agent.ID_END);

                    // 5. 所有支持人员完成后到满意度调查
                    NodeSpec techNode = spec.getNode("technical_support");
                    NodeSpec billingNode = spec.getNode("billing_support");
                    NodeSpec complaintNode = spec.getNode("complaint_handler");

                    if (techNode != null) techNode.linkAdd("satisfaction_survey");
                    if (billingNode != null) billingNode.linkAdd("satisfaction_survey");
                    if (complaintNode != null) complaintNode.linkAdd("satisfaction_survey");

                    // 6. 设置流程路由
                    NodeSpec checkinNode = spec.getNode("customer_checkin");
                    if (checkinNode != null) {
                        checkinNode.linkAdd("problem_gateway");
                    }

                    NodeSpec gatewayNode = spec.getNode("problem_gateway");
                    if (gatewayNode != null) {
                        gatewayNode.linkAdd("reception");
                    }

                    // 7. 修改起始路由
                    NodeSpec supervisor = spec.getNode("supervisor");
                    if (supervisor != null) {
                        supervisor.getLinks().clear();
                        supervisor.linkAdd("customer_checkin");
                    }
                })
                .outputKey("customer_service_result")
                .maxTotalIterations(5)
                .build();

        AgentSession session = InMemoryAgentSession.of("session_customer_01");
        String result = team.call(Prompt.of("处理客户咨询"), session).getContent();

        // 验证输出结果
        Object serviceResult = session.getSnapshot().get("customer_service_result");
        Assertions.assertNotNull(serviceResult, "客户服务结果应被存储");

        Assertions.assertNotNull(finalSolution[0], "应生成最终解决方案");

        TeamTrace trace = team.getTrace(session);
        List<String> executedNodes = trace.getSteps().stream()
                .map(TeamTrace.TeamStep::getSource)
                .collect(Collectors.toList());

        System.out.println("客户服务节点: " + executedNodes);

        // 验证流程完整性
        Assertions.assertTrue(executedNodes.contains("customer_checkin"), "应包含客户接入");
        Assertions.assertTrue(executedNodes.contains("problem_gateway"), "应包含问题分类");
        Assertions.assertTrue(executedNodes.contains("reception"), "应包含接待客服");
        Assertions.assertTrue(executedNodes.contains("satisfaction_survey"), "应包含满意度调查");

        // 根据问题类型technical，应该执行technical_support
        Assertions.assertTrue(executedNodes.contains("technical_support"),
                "技术问题应路由到技术支持");
    }
}