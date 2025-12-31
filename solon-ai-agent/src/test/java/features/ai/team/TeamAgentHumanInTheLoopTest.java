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

/**
 * 【业务场景测试】：人工介入（Human-in-the-loop）审批流
 * 场景：AI 规划师生成行程 -> 流程自动挂起进入“待审批”状态 -> 人工在后台点击“同意” -> AI 继续执行确认
 */
public class TeamAgentHumanInTheLoopTest {

    @Test
    public void testHumanInTheLoop() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        String teamId = "audit_travel_team";

        // 1. 定义团队：包含规划师和确认员
        TeamAgent auditTeam = TeamAgent.builder(chatModel)
                .name(teamId)
                .addAgent(ReActAgent.builder(chatModel).name("planner").description("负责生成详细方案").build())
                .addAgent(ReActAgent.builder(chatModel).name("confirmer").description("负责在审批通过后完成最终确认").build())
                .graph(spec -> {
                    spec.addStart(Agent.ID_START).linkAdd("planner");
                    spec.addActivity(ReActAgent.builder(chatModel).name("planner").build())
                            .linkAdd("human_audit"); // 执行完 planner 后跳转到人工审核节点

                    // 2. 定义人工审核节点：此节点不写 Task 逻辑，仅作为“停顿点”
                    spec.addActivity("human_audit").linkAdd("confirmer");
                })
                .build();

        // --- 阶段 A：AI 自动生成方案并挂起 ---
        FlowContext context = FlowContext.of("order_888");
        // 设置“运行到 human_audit 节点时自动停止”
        context.lastNode(auditTeam.getGraph().getNode("human_audit"));

        System.out.println(">>> [系统]：AI 开始规划方案...");
        auditTeam.call(context, "帮我策划一个去拉萨的行程");

        // 检测：确认流程是否在人工节点停住
        Assertions.assertEquals("human_audit", context.lastNodeId(), "流程未能在人工审计节点按预期挂起");
        System.out.println(">>> [系统]：方案已生成，流程已挂起，等待人工审批...");

        // --- 阶段 B：人工干预（模拟后台审核通过） ---
        // 模拟人工在数据库/界面修改了状态，并准备恢复
        System.out.println(">>> [人工审批]：审核通过！准予执行。");

        // 继续执行：传入 null，从 human_audit 的下一个节点（confirmer）开始
        String finalOutput = auditTeam.call(context, null);

        // 3. 单测检测
        System.out.println(">>> [最终输出]：\n" + finalOutput);
        TeamTrace trace = context.getAs("__" + teamId);
        Assertions.assertTrue(trace.getStepCount() >= 2, "轨迹应同时包含规划阶段和确认阶段");
    }
}