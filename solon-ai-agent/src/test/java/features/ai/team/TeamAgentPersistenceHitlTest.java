package features.ai.team;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamInterceptor;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;

public class TeamAgentPersistenceHitlTest {

    @Test
    public void testCombinedPersistenceAndHitl() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        String teamId = "approval_team";

        TeamAgent teamAgent = TeamAgent.builder(chatModel)
                .name(teamId)
                .addAgent(ReActAgent.builder(chatModel).name("worker").description("初稿撰写").build())
                .addAgent(ReActAgent.builder(chatModel).name("approver").description("修辞优化").build())
                .interceptor(new TeamInterceptor() {
                    @Override
                    public void onNodeStart(FlowContext ctx, Node n) {
                        // 逻辑修改：只要检测到 worker 已经产生过至少一条记录，且没有人工信号，就强制挂起
                        TeamTrace trace = ctx.getAs("__" + teamId);
                        if (trace != null && trace.getStepCount() > 0) {
                            if (!ctx.model().containsKey("manager_ok")) {
                                System.out.println("[HITL] 拦截器触发：检测到已有阶段性产出，强制 Suspend 流程等待审批...");
                                ctx.stop();
                            }
                        }
                    }
                })
                .build();

        // --- 阶段 A: 发起并挂起 ---
        FlowContext context1 = FlowContext.of("order_hitl_001");
        System.out.println(">>> 阶段 A: 启动流程...");
        teamAgent.call(context1, "请起草一份周报内容");

        // 验证点：此时流程应该因为至少有一次 worker 运行而被挂起
        Assertions.assertTrue(context1.isStopped(), "流程应该在 Supervisor 准备第二次分配任务前被拦截");

        String jsonState = context1.toJson();
        System.out.println(">>> 流程已挂起并序列化。当前步骤数: " + ((TeamTrace)context1.getAs("__" + teamId)).getStepCount());

        // --- 阶段 B: 恢复并完成 ---
        System.out.println("\n>>> 阶段 B: 恢复流程并注入 manager_ok 信号...");
        FlowContext context2 = FlowContext.fromJson(jsonState);
        context2.put("manager_ok", true); // 注入信号跳过拦截器

        String finalResult = teamAgent.call(context2);

        System.out.println("=== 最终结果 ===");
        System.out.println(finalResult);

        Assertions.assertTrue(context2.lastNode().isEnd(), "流程最终应成功结束");
        Assertions.assertTrue(finalResult.contains("周报"), "结果应包含周报内容");
    }
}