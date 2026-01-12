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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 故障处理流程测试
 * IT系统故障诊断和修复场景
 */
public class IncidentManagementGraphTest {

    @Test
    public void testIncidentManagementProcess() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        final AtomicInteger incidentLevel = new AtomicInteger(2); // 模拟2级故障

        TeamAgent team = TeamAgent.of(chatModel)
                .name("incident_team")
                .description("故障处理团队")
                .agentAdd(
                        ReActAgent.of(chatModel)
                                .name("monitor")
                                .description("监控系统 - 检测故障")
                                .systemPrompt(ReActSystemPrompt.builder()
                                        .role("监控系统")
                                        .instruction("检测系统异常，分析故障现象和影响范围")
                                        .build())
                                .build(),
                        ReActAgent.of(chatModel)
                                .name("level1_support")
                                .description("一线支持 - 处理简单故障")
                                .systemPrompt(ReActSystemPrompt.builder()
                                        .role("一线技术支持")
                                        .instruction("处理1级故障，如服务重启、配置恢复等简单操作")
                                        .build())
                                .build(),
                        ReActAgent.of(chatModel)
                                .name("level2_support")
                                .description("二线支持 - 处理复杂故障")
                                .systemPrompt(ReActSystemPrompt.builder()
                                        .role("二线技术专家")
                                        .instruction("处理2级故障，需要代码级调试和深入分析")
                                        .build())
                                .build(),
                        ReActAgent.of(chatModel)
                                .name("level3_support")
                                .description("三线支持 - 处理严重故障")
                                .systemPrompt(ReActSystemPrompt.builder()
                                        .role("三线架构师")
                                        .instruction("处理3级故障，涉及架构调整和紧急预案")
                                        .build())
                                .build()
                )
                .graphAdjuster(spec -> {
                    // 1. 故障检测节点
                    spec.addActivity("detect_incident")
                            .title("故障检测")
                            .task((ctx, node) -> {
                                ctx.put("incident_level", incidentLevel.get());
                                ctx.put("incident_time", System.currentTimeMillis());
                                System.out.println("检测到 " + incidentLevel.get() + " 级故障");
                            });

                    // 2. 故障级别判断网关
                    spec.addExclusive("level_gateway")
                            .title("故障级别判断")
                            .task((ctx, node) -> {
                                Integer level = ctx.getAs("incident_level");
                                if (level != null) {
                                    if (level == 1) {
                                        ctx.put("handler", "level1_support");
                                    } else if (level == 2) {
                                        ctx.put("handler", "level2_support");
                                    } else {
                                        ctx.put("handler", "level3_support");
                                    }
                                }
                            });

                    // 3. 设置路由逻辑
                    NodeSpec detectNode = spec.getNode("detect_incident");
                    if (detectNode != null) {
                        detectNode.linkAdd("level_gateway");
                    }

                    NodeSpec monitorNode = spec.getNode("monitor");
                    if (monitorNode != null) {
                        monitorNode.getLinks().clear();
                        monitorNode.linkAdd("level1_support", l -> l
                                .when(ctx -> "level1_support".equals(ctx.get("handler"))));
                        monitorNode.linkAdd("level2_support", l -> l
                                .when(ctx -> "level2_support".equals(ctx.get("handler"))));
                        monitorNode.linkAdd("level3_support", l -> l
                                .when(ctx -> "level3_support".equals(ctx.get("handler"))));
                    }

                    // 4. 添加故障恢复确认节点
                    spec.addActivity("recovery_confirmation")
                            .title("恢复确认")
                            .task((ctx, node) -> {
                                String handler = ctx.getAs("handler");
                                System.out.println("故障由 " + handler + " 处理完成，等待确认恢复");
                                ctx.put("recovery_time", System.currentTimeMillis());
                            })
                            .linkAdd(Agent.ID_END);

                    // 5. 所有支持人员完成后到恢复确认
                    NodeSpec level1Node = spec.getNode("level1_support");
                    NodeSpec level2Node = spec.getNode("level2_support");
                    NodeSpec level3Node = spec.getNode("level3_support");

                    if (level1Node != null) level1Node.linkAdd("recovery_confirmation");
                    if (level2Node != null) level2Node.linkAdd("recovery_confirmation");
                    if (level3Node != null) level3Node.linkAdd("recovery_confirmation");

                    // 6. 修改起始路由
                    NodeSpec supervisor = spec.getNode("supervisor");
                    if (supervisor != null) {
                        supervisor.getLinks().clear();
                        supervisor.linkAdd("detect_incident");
                    }

                    // 7. 故障级别判断后到监控分析
                    NodeSpec gatewayNode = spec.getNode("level_gateway");
                    if (gatewayNode != null) {
                        gatewayNode.linkAdd("monitor");
                    }
                })
                .maxTotalIterations(6)
                .build();

        AgentSession session = InMemoryAgentSession.of("session_incident_01");
        String result = team.call(Prompt.of("处理系统故障"), session).getContent();

        TeamTrace trace = team.getTrace(session);
        List<String> executedNodes = trace.getSteps().stream()
                .map(TeamTrace.TeamStep::getSource)
                .collect(Collectors.toList());

        System.out.println("故障处理节点: " + executedNodes);

        // 验证故障处理流程
        Assertions.assertTrue(executedNodes.contains("detect_incident"), "应包含故障检测");
        Assertions.assertTrue(executedNodes.contains("level_gateway"), "应包含级别判断");
        Assertions.assertTrue(executedNodes.contains("monitor"), "应包含监控分析");
        Assertions.assertTrue(executedNodes.contains("recovery_confirmation"), "应包含恢复确认");

        // 根据故障级别2，应该执行 level2_support
        Assertions.assertTrue(executedNodes.contains("level2_support"),
                "2级故障应路由到二线支持");
    }
}