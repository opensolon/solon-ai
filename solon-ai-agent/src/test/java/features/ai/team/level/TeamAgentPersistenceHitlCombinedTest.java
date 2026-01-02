package features.ai.team.level;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamInterceptor;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;

public class TeamAgentPersistenceHitlCombinedTest {

    @Test
    public void testCombinedScenario() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        TeamAgent projectTeam = TeamAgent.builder(chatModel)
                .name("combined_manager")
                .addAgent(new Agent() {
                    @Override public String name() { return "Worker"; }
                    @Override public String description() { return "执行者"; }
                    @Override public String call(FlowContext ctx, Prompt p) { return "任务完成。"; }
                })
                .addAgent(new Agent() {
                    @Override public String name() { return "Approver"; }
                    @Override public String description() { return "审批者"; }
                    @Override public String call(FlowContext ctx, Prompt p) { return "签字通过。[FINISH]"; }
                })
                .interceptor(new TeamInterceptor() {
                    @Override
                    public void onNodeStart(FlowContext ctx, Node n) {
                        if (Agent.ID_SUPERVISOR.equals(n.getId())) {
                            TeamTrace trace = ctx.getAs("__combined_manager");
                            // 关键逻辑：Worker 已经出现在历史里，且没被签字
                            if (trace != null && trace.getFormattedHistory().contains("Worker")) {
                                if (!ctx.containsKey("signed")) {
                                    System.out.println("[HITL] 拦截：任务需要经理签名，正在挂起并等待持久化...");
                                    ctx.stop();
                                }
                            }
                        }
                    }
                })
                .build();

        // --- 1. 第一阶段：运行并被拦截 ---
        FlowContext context1 = FlowContext.of("c_001");
        projectTeam.call(context1, "处理重要单据");

        Assertions.assertTrue(context1.isStopped(), "流程必须停在中间节点");
        String jsonState = context1.toJson(); // 持久化
        System.out.println(">>> 阶段1完成：快照已落库。");

        // --- 2. 第二阶段：恢复并批准 ---
        FlowContext context2 = FlowContext.fromJson(jsonState);
        context2.put("signed", true); // 模拟人工审批
        System.out.println(">>> 阶段2：从快照恢复，注入签名。");

        String finalResult = projectTeam.call(context2);

        // --- 3. 验证 ---
        TeamTrace trace = context2.getAs("__combined_manager");
        Assertions.assertTrue(trace.getFormattedHistory().contains("Approver"), "审批人必须在恢复后的流程中出现");
        Assertions.assertTrue(finalResult.contains("签字通过"), "最终结果必须符合预期");
        System.out.println("联合测试通过，最终产出: " + finalResult);
    }
}