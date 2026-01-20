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
 * 客户服务流程测试
 * 场景：分类 -> 接待 -> 专项处理 -> 满意度调查
 */
public class CustomerServiceGraphTest {

    @Test
    @DisplayName("测试客服 Graph：验证 Exclusive 网关下的精准路由与分流")
    public void testCustomerServiceProcess() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        final String[] finalSolution = new String[1];

        // 1. 使用 SimpleAgent 定义客服专家，职责更纯粹
        TeamAgent team = TeamAgent.of(chatModel)
                .name("customer_service_team")
                .agentAdd(
                        createServiceAgent(chatModel, "reception", "接待客服", "你好！我是接待员，正在为您转接专项服务。"),
                        createServiceAgent(chatModel, "technical_support", "技术支持", "技术问题已收到，正在为您检测故障..."),
                        createServiceAgent(chatModel, "billing_support", "财务支持", "账单核对中，请稍后..."),
                        createServiceAgent(chatModel, "complaint_handler", "投诉处理", "非常抱歉给您带来不便，我们正在处理您的投诉。")
                )
                .graphAdjuster(spec -> {

                    spec.getNode("supervisor").linkClear()
                            .linkAdd("customer_checkin");

                    // 1. 客户接入（初始化数据）
                    spec.addActivity("customer_checkin")
                            .task((ctx, node) -> {
                                ctx.put("problem_type", "technical"); // 模拟技术问题
                                System.out.println(">>> [Node] 客户接入，类型: technical");
                            })
                            .linkAdd("problem_gateway");

                    // 2. 问题分类网关 (Exclusive)
                    spec.addExclusive("problem_gateway")
                            .task((ctx, node) -> {
                                String type = ctx.getAs("problem_type");
                                String handler = "technical_support"; // 默认
                                if ("billing".equals(type)) handler = "billing_support";
                                if ("complaint".equals(type)) handler = "complaint_handler";

                                ctx.put("handler", handler);
                                System.out.println(">>> [Gateway] 路由至: " + handler);
                            })
                            .linkAdd("reception");

                    spec.getNode("reception").linkClear()
                            .linkAdd("technical_support", l -> l.when(ctx -> "technical_support".equals(ctx.get("handler"))))
                            .linkAdd("billing_support", l -> l.when(ctx -> "billing_support".equals(ctx.get("handler"))))
                            .linkAdd("complaint_handler", l -> l.when(ctx -> "complaint_handler".equals(ctx.get("handler"))));


                    spec.getNode("technical_support").linkClear()
                            .linkAdd("satisfaction_survey");
                    spec.getNode("billing_support").linkClear()
                            .linkAdd("satisfaction_survey");
                    spec.getNode("complaint_handler").linkClear()
                            .linkAdd("satisfaction_survey");


                    // 3. 满意度调查（终点前置节点）
                    spec.addActivity("satisfaction_survey")
                            .task((ctx, node) -> {
                                finalSolution[0] = "Survey_Completed";
                                System.out.println(">>> [Node] 满意度调查完成");
                            })
                            .linkAdd(Agent.ID_END);
                })
                .build();

        // 2. 执行测试
        AgentSession session = InMemoryAgentSession.of("session_customer_01");
        team.call(Prompt.of("我的电脑无法开机了，请帮我处理。"), session);

        // 3. 验证 Agent 路径（使用 trace）
        TeamTrace trace = team.getTrace(session);
        List<String> agentPath = trace.getRecords().stream()
                .map(TeamTrace.TeamRecord::getSource)
                .collect(Collectors.toList());

        System.out.println("AI 专家路径: " + agentPath);
        Assertions.assertTrue(agentPath.contains("reception"));
        Assertions.assertTrue(agentPath.contains("technical_support"));

        // 4. 验证业务逻辑节点（使用 Context 或 外部变量）
        // 既然日志输出了 ">>> [Node] 满意度调查完成"，说明链路走通了
        Assertions.assertEquals("Survey_Completed", finalSolution[0], "满意度调查节点未被触发");

        // 验证分类网关是否生效
        Assertions.assertEquals("technical_support", session.getSnapshot().get("handler"), "分类网关逻辑错误");
    }

    private Agent createServiceAgent(ChatModel chatModel, String name, String role, String reply) {
        return SimpleAgent.of(chatModel)
                .name(name)
                .systemPrompt(p->p
                        .role(role)
                        .instruction("你是" + role + "。请基于用户问题提供服务。回复示例：" + reply))
                .build();
    }
}