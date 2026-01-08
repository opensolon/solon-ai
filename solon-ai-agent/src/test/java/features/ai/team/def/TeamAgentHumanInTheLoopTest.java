package features.ai.team.def;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;
import org.noear.solon.flow.TaskComponent;

/**
 * 【业务场景测试】：人工介入（Human-in-the-loop）审批流
 * * <p>场景验证：AI 规划师生成行程 -> 流程自动在自定义节点挂起 -> 人工在外部注入审批状态 -> AI 恢复执行并确认</p>
 */
public class TeamAgentHumanInTheLoopTest {

    @Test
    public void testHumanInTheLoop() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        String teamName = "audit_travel_team";

        // 1. 定义团队：通过 graphAdjuster 自定义 DAG 流程
        TeamAgent auditTeam = TeamAgent.of(chatModel)
                .name(teamName)
                .maxTotalIterations(10)
                .graphAdjuster(spec -> {
                    // 移除默认的 Supervisor（管家模式），构建线性审批流
                    spec.removeNode(Agent.ID_SUPERVISOR);

                    // 入口：开始 -> 规划师
                    spec.addStart(Agent.ID_START).linkAdd("planner");

                    // 节点1：Planner (AI 生成方案)
                    spec.addActivity(ReActAgent.of(chatModel)
                                    .name("planner")
                                    .description("负责生成详细方案")
                                    .build())
                            .linkAdd("human_audit");

                    // 节点2：人工审核 (TaskComponent 模拟挂起)
                    spec.addActivity("human_audit")
                            .title("人工审核")
                            .task(new HumanAuditTask())
                            .linkAdd("confirmer");

                    // 节点3：Confirmer (AI 最终确认)
                    spec.addActivity(ReActAgent.of(chatModel)
                                    .name("confirmer")
                                    .description("负责在审批通过后完成最终确认")
                                    .build())
                            .linkAdd(Agent.ID_END);

                    spec.addEnd(Agent.ID_END);
                })
                .build();

        // 打印图结构，方便调试
        System.out.println("--- 团队执行流图结构 ---\n" + auditTeam.getGraph().toYaml());

        // --- 阶段 A：发起请求并触发自动挂起 ---
        AgentSession session = InMemoryAgentSession.of("order_888");
        System.out.println(">>> [系统]：AI 开始规划方案...");

        // 第一次调用：执行到 human_audit 节点会因为没有审批标记而 stop
        String firstResult = auditTeam.call(Prompt.of("帮我策划一个去拉萨的行程"), session).getContent();

        System.out.println(">>> [阶段A结果]：\n" + firstResult);

        // 验证流程是否在 human_audit 处挂起
        FlowContext context = session.getSnapshot();
        Assertions.assertTrue(context.isStopped(), "流程应在人工审核节点挂起");
        System.out.println(">>> [当前节点位置]：" + context.lastNodeId());

        // --- 阶段 B：人工干预（模拟审批通过） ---
        System.out.println("\n>>> [人工审批]：管理员审核通过！");

        // 在 Session 对应的快照中注入审批标记
        context.put("audit_approved", true);

        // --- 阶段 C：继续执行 ---
        System.out.println(">>> [系统]：审批通过，恢复流程执行最终确认...");

        // 第二次调用：传入 session 即可，Agent 会从挂起点自动恢复
        String finalOutput = auditTeam.call(session).getContent();

        System.out.println(">>> [最终输出]：\n" + finalOutput);

        // 验证结果
        Assertions.assertNotNull(finalOutput);
        Assertions.assertTrue(finalOutput.contains("拉萨") || finalOutput.contains("行程"), "最终结果应包含行程确认信息");

        // 验证轨迹是否包含所有节点
        TeamTrace trace = auditTeam.getTrace(session);
        Assertions.assertTrue(trace.getFormattedHistory().contains("planner"), "历史应记录 planner 的规划");
    }

    /**
     * 自定义人工审核任务组件
     * 逻辑：检查 audit_approved 标志，若无则 stop 流程，若有则路由至下一个 Agent
     */
    static class HumanAuditTask implements TaskComponent {
        @Override
        public void run(FlowContext context, Node node) throws Throwable {
            // 从上下文中获取审批状态
            Boolean approved = context.getOrDefault("audit_approved", false);

            if (!approved) {
                System.out.println(">>> [拦截]：未检测到审批信号，流程自动挂起等待...");
                // 停止 Flow 执行，此时 context.isStopped() 将变为 true
                context.stop();
            } else {
                System.out.println(">>> [放行]：检测到审批信号，准备进入确认节点...");
                // 获取当前团队轨迹，并手动指定下一个路由目标
                String traceKey = context.getAs(Agent.KEY_CURRENT_TRACE_KEY);
                TeamTrace trace = context.getAs(traceKey);
                if (trace != null) {
                    trace.setRoute("confirmer");
                }
            }
        }
    }
}