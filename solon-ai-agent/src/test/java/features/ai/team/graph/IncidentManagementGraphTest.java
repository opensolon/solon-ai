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

        // 1. 定义 Agent：切换为 SimpleAgent，让 AI 专注于生成诊断报告，而不是折腾执行格式
        TeamAgent team = TeamAgent.of(chatModel)
                .name("incident_team")
                .agentAdd(
                        createSupportAgent(chatModel, "monitor", "监控系统", "检测到系统 CPU 占用 100%，开始分析..."),
                        createSupportAgent(chatModel, "level1_support", "一线支持", "执行服务重启，配置回滚。"),
                        createSupportAgent(chatModel, "level2_support", "二线支持", "分析 Dump 文件，修复代码逻辑缺陷。"),
                        createSupportAgent(chatModel, "level3_support", "三线支持", "启动跨机房灾备预案，架构扩容。")
                )
                .graphAdjuster(spec -> {
                    // 2. 故障检测（初始化数据载入）
                    spec.addActivity("detect_incident")
                            .task((ctx, node) -> {
                                ctx.put("incident_level", incidentLevel.get());
                                System.out.println(">>> [Node] 检测到级别: " + incidentLevel.get());
                            });

                    // 3. 级别判断网关 (Exclusive)
                    spec.addExclusive("level_gateway")
                            .task((ctx, node) -> {
                                Integer level = ctx.getAs("incident_level");
                                String target = (level == 1) ? "level1_support" :
                                        (level == 2) ? "level2_support" : "level3_support";
                                ctx.put("handler", target);
                                System.out.println(">>> [Gateway] 判定处理者: " + target);
                            });

                    // 4. 恢复确认节点 (汇聚点)
                    spec.addActivity("recovery_confirmation")
                            .task((ctx, node) -> {
                                System.out.println(">>> [Node] 故障修复确认中...");
                            })
                            .linkAdd(Agent.ID_END);

                    // --- 5. 路由精准编排 ---

                    // Supervisor -> 入口
                    spec.getNode("supervisor").getLinks().clear();
                    spec.getNode("supervisor").linkAdd("detect_incident");

                    // 链路：检测 -> 网关 -> 监控分析 -> 分发处理
                    spec.getNode("detect_incident").linkAdd("level_gateway");
                    spec.getNode("level_gateway").linkAdd("monitor");

                    // 监控分析后的分发（这里是关键：基于 handler 变量）
                    NodeSpec monitorNode = spec.getNode("monitor");
                    monitorNode.getLinks().clear();
                    monitorNode.linkAdd("level1_support", l -> l.when(ctx -> "level1_support".equals(ctx.get("handler"))));
                    monitorNode.linkAdd("level2_support", l -> l.when(ctx -> "level2_support".equals(ctx.get("handler"))));
                    monitorNode.linkAdd("level3_support", l -> l.when(ctx -> "level3_support".equals(ctx.get("handler"))));

                    // 所有支持节点 -> 恢复确认
                    spec.getNode("level1_support").linkAdd("recovery_confirmation");
                    spec.getNode("level2_support").linkAdd("recovery_confirmation");
                    spec.getNode("level3_support").linkAdd("recovery_confirmation");
                })
                .build();

        // 6. 运行测试
        AgentSession session = InMemoryAgentSession.of("session_incident_01");
        team.call(Prompt.of("系统出现报警，请立即处理"), session);

        // 7. 轨迹验证
        TeamTrace trace = team.getTrace(session);
        List<String> executedNodes = trace.getSteps().stream()
                .map(TeamTrace.TeamStep::getSource)
                .collect(Collectors.toList());

        System.out.println("故障处理全链路: " + String.join(" -> ", executedNodes));

        // 断言逻辑
        Assertions.assertTrue(executedNodes.contains("detect_incident"));
        Assertions.assertTrue(executedNodes.contains("level_gateway"));
        Assertions.assertTrue(executedNodes.contains("monitor"));

        // 关键断言：2级故障必须由 level2 处理
        Assertions.assertTrue(executedNodes.contains("level2_support"), "未正确流转至二线专家");
        Assertions.assertFalse(executedNodes.contains("level1_support"), "不应触发一线支持");

        Assertions.assertTrue(executedNodes.contains("recovery_confirmation"));
    }

    private Agent createSupportAgent(ChatModel chatModel, String name, String role, String logMsg) {
        return SimpleAgent.of(chatModel)
                .name(name)
                .systemPrompt(SimpleSystemPrompt.builder()
                        .role(role)
                        .instruction("你是" + role + "。请根据故障现象提供诊断结论。示例响应：" + logMsg)
                        .build())
                .build();
    }
}