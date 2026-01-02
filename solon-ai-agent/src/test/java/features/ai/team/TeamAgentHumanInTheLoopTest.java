package features.ai.team;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;
import org.noear.solon.flow.TaskComponent;

/**
 * 【业务场景测试】：人工介入（Human-in-the-loop）审批流
 * 场景：AI 规划师生成行程 -> 流程自动挂起进入"待审批"状态 -> 人工在后台点击"同意" -> AI 继续执行确认
 */
public class TeamAgentHumanInTheLoopTest {

    @Test
    public void testHumanInTheLoop() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        String teamId = "audit_travel_team";

        // 1. 定义团队：包含规划师和确认员
        TeamAgent auditTeam = TeamAgent.of(chatModel)
                .name(teamId)
                .addAgent(ReActAgent.of(chatModel)
                        .name("planner")
                        .description("负责生成详细方案")
                        .build())
                .addAgent(ReActAgent.of(chatModel)
                        .name("confirmer")
                        .description("负责在审批通过后完成最终确认")
                        .build())
                .maxTotalIterations(20) // 增加最大迭代次数
                .graphAdjuster(spec -> {
                    // 使用自定义graph覆盖自动管家模式
                    spec.addStart(Agent.ID_START).linkAdd("planner");

                    spec.addActivity("planner").task(ReActAgent.of(chatModel)
                                    .name("planner")
                                    .description("负责生成详细方案")
                                    .build())
                            .linkAdd("human_audit"); // 执行完 planner 后跳转到人工审核节点

                    // 2. 人工审核节点：使用任务组件来模拟挂起
                    spec.addActivity("human_audit")
                            .task(new HumanAuditTask())
                            .linkAdd("confirmer"); // 人工审核后进入confirmer

                    spec.addActivity("confirmer").task(ReActAgent.of(chatModel)
                                    .name("confirmer")
                                    .description("负责在审批通过后完成最终确认")
                                    .build())
                            .linkAdd(Agent.ID_END);

                    spec.addEnd(Agent.ID_END);
                })
                .build();

        // --- 阶段 A：AI 自动生成方案并挂起 ---
        FlowContext context = FlowContext.of("order_888");

        System.out.println(">>> [系统]：AI 开始规划方案...");
        String firstResult = auditTeam.call(context, "帮我策划一个去拉萨的行程");

        System.out.println(">>> [阶段A结果]：\n" + firstResult);
        System.out.println(">>> [系统]：方案已生成，流程已挂起，等待人工审批...");

        // 检查是否在human_audit节点（基于上下文状态）
        String lastNode = context.lastNode() != null ? context.lastNode().getId() : "";
        System.out.println(">>> [当前节点]：" + lastNode);

        // --- 阶段 B：人工干预（模拟后台审核通过） ---
        System.out.println(">>> [人工审批]：审核通过！准予执行。");

        // 设置审核通过标志
        context.put("audit_approved", true);

        // 继续执行：传入null，从human_audit的下一个节点（confirmer）开始
        String finalOutput = auditTeam.call(context);

        // 3. 单测检测
        System.out.println(">>> [最终输出]：\n" + finalOutput);
        TeamTrace trace = context.getAs("__" + teamId);
        Assertions.assertNotNull(finalOutput);
        Assertions.assertTrue(finalOutput.contains("拉萨") || finalOutput.contains("Lhasa"),
                "最终输出应包含行程相关内容");
    }

    // 自定义人工审核任务组件
    static class HumanAuditTask implements TaskComponent {
        @Override
        public void run(FlowContext context, Node node) throws Throwable {
            Boolean approved = context.getOrDefault("audit_approved", false);

            if (!approved) {
                // 可以在这里记录到数据库，等待人工操作
                System.out.println(">>> [系统]：等待人工审批中...");

                // 暂停执行，等待下次调用
                context.stop();
            } else {
                String traceKey = context.getAs(Agent.KEY_CURRENT_TRACE_KEY);
                // 审核通过，继续到confirmer
                context.<TeamTrace>getAs(traceKey).setRoute("confirmer");
            }
        }
    }
}