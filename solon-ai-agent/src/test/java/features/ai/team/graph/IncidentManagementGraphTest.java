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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 故障处理流程测试
 * 场景：检测 -> 级别判定 -> 专家诊断 -> 恢复确认
 */
public class IncidentManagementGraphTest {

    @Test
    @DisplayName("测试故障处理 Graph：验证基于故障分级的动态路由分发")
    public void testIncidentManagementProcess() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        final AtomicInteger incidentLevel = new AtomicInteger(2); // 模拟2级故障

        // 1. 定义专家角色
        TeamAgent team = TeamAgent.of(chatModel)
                .name("incident_team")
                .agentAdd(
                        createSupportAgent(chatModel, "monitor", "监控系统专家", "CPU 占用 100%"),
                        createSupportAgent(chatModel, "level1_support", "一线支持", "执行服务重启"),
                        createSupportAgent(chatModel, "level2_support", "二线支持", "修复代码逻辑缺陷"),
                        createSupportAgent(chatModel, "level3_support", "三线支持", "启动灾备扩容")
                )
                .graphAdjuster(spec -> {
                    // 入口重新定义
                    spec.getNode("supervisor").linkClear().linkAdd("detect_incident");

                    // 节点 1: 故障检测 -> 连向网关
                    spec.addActivity("detect_incident")
                            .title("故障检测")
                            .task((ctx, node) -> {
                                ctx.put("incident_level", incidentLevel.get());
                                System.out.println(">>> [Node] 检测到故障级别: " + incidentLevel.get());
                            })
                            .linkAdd("level_gateway");

                    // 节点 2: 路由网关 -> 连向监控专家
                    spec.addExclusive("level_gateway")
                            .title("判定路由")
                            .task((ctx, node) -> {
                                Integer level = ctx.getAs("incident_level");
                                String target = (level == 1) ? "level1_support" :
                                        (level == 2) ? "level2_support" : "level3_support";
                                ctx.put("target_handler", target);
                                System.out.println(">>> [Gateway] 路由判定目标: " + target);
                            })
                            .linkAdd("monitor");

                    // 节点 3: 监控专家 -> 根据条件分流至不同级别支持
                    spec.getNode("monitor").linkClear()
                            .linkAdd("level1_support", l -> l.when(ctx -> "level1_support".equals(ctx.get("target_handler"))))
                            .linkAdd("level2_support", l -> l.when(ctx -> "level2_support".equals(ctx.get("target_handler"))))
                            .linkAdd("level3_support", l -> l.when(ctx -> "level3_support".equals(ctx.get("target_handler"))));

                    // 节点 4: 各级专家 -> 全部汇聚到恢复确认
                    spec.getNode("level1_support").linkClear().linkAdd("recovery_confirmation");
                    spec.getNode("level2_support").linkClear().linkAdd("recovery_confirmation");
                    spec.getNode("level3_support").linkClear().linkAdd("recovery_confirmation");

                    // 节点 5: 恢复确认 -> 结束
                    spec.addActivity("recovery_confirmation")
                            .title("恢复确认")
                            .task((ctx, node) -> {
                                ctx.put("recovery_status", "CONFIRMED");
                                System.out.println(">>> [Node] 故障修复已确认");
                            })
                            .linkAdd(Agent.ID_END);
                })
                .build();

        // 2. 运行测试
        AgentSession session = InMemoryAgentSession.of("session_incident_01");
        team.call(Prompt.of("系统出现报警，请立即处理"), session);

        // 3. 结果验证 (Agent 走 Trace，Activity 走 Context)
        TeamTrace trace = team.getTrace(session);
        List<String> agentSteps = trace.getSteps().stream()
                .map(TeamTrace.TeamStep::getSource)
                .collect(Collectors.toList());

        System.out.println("AI 专家足迹: " + String.join(" -> ", agentSteps));

        // --- 断言逻辑 ---
        Assertions.assertEquals(2, session.getSnapshot().get("incident_level"));
        Assertions.assertEquals("level2_support", session.getSnapshot().get("target_handler"));
        Assertions.assertEquals("CONFIRMED", session.getSnapshot().get("recovery_status"));

        Assertions.assertTrue(agentSteps.contains("monitor"));
        Assertions.assertTrue(agentSteps.contains("level2_support"));
        Assertions.assertFalse(agentSteps.contains("level1_support"));

        int monitorIdx = agentSteps.indexOf("monitor");
        int supportIdx = agentSteps.indexOf("level2_support");
        Assertions.assertTrue(monitorIdx < supportIdx, "顺序验证失败：必须先监控再支持");

        System.out.println("单元测试成功！");
    }

    private Agent createSupportAgent(ChatModel chatModel, String name, String role, String mockMsg) {
        return SimpleAgent.of(chatModel)
                .name(name)
                .systemPrompt(SimpleSystemPrompt.builder()
                        .role(role)
                        .instruction("你是" + role + "。请简要回复结论。参考内容：" + mockMsg)
                        .build())
                .build();
    }
}