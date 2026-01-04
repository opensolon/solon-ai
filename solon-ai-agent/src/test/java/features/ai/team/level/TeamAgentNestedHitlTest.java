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

public class TeamAgentNestedHitlTest {
    @Test
    public void testNestedHumanIntervention() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 构建子团队
        TeamAgent devTeam = TeamAgent.of(chatModel).name("dev_team")
                .addAgent(new Agent() {
                    @Override public String name() { return "Coder"; }
                    @Override public String description() { return "写代码"; }
                    @Override public String call(FlowContext ctx, Prompt p) { return "代码写好了。"; }
                }).build();

        // 2. 构建父团队
        TeamAgent projectTeam = TeamAgent.of(chatModel)
                .name("manager")
                .addAgent(devTeam)
                .addAgent(new Agent() {
                    @Override public String name() { return "Reviewer"; }
                    @Override public String description() { return "审代码"; }
                    @Override public String call(FlowContext ctx, Prompt p) { return "Perfect. [FINISH]"; }
                })
                .interceptor(new TeamInterceptor() {
                    @Override
                    public void onNodeStart(FlowContext ctx, Node n) {
                        if (Agent.ID_SUPERVISOR.equals(n.getId())) {
                            TeamTrace trace = ctx.getAs("__manager");
                            // 逻辑：如果开发已完成，但未获得经理批准，则中断
                            if (trace != null && trace.getFormattedHistory().contains("dev_team")) {
                                if (!ctx.containsKey("approved")) {
                                    System.out.println("[HITL] 拦截：等待经理批准 dev_team 的成果...");
                                    ctx.stop();
                                }
                            }
                        }
                    }
                })
                .build();


        String yaml = projectTeam.getGraph().toYaml();

        System.out.println("------------------\n\n");
        System.out.println(yaml);
        System.out.println("\n\n------------------");


        FlowContext context = FlowContext.of("hitl_job");

        // 第一次调用：执行完 dev_team 后在 Supervisor 处被拦截
        projectTeam.call(context, "开发支付模块");

        // 使用 isStopped 判定流程停止
        Assertions.assertTrue(context.isStopped(), "流程应该被拦截，未到达结束节点");
        Assertions.assertEquals(Agent.ID_SUPERVISOR, context.lastNodeId());

        // 验证 Reviewer 尚未介入
        TeamTrace trace1 = context.getAs("__manager");
        Assertions.assertFalse(trace1.getFormattedHistory().contains("Reviewer"));

        // 第二次调用：注入批准信号，恢复执行
        context.put("approved", true);
        String result = projectTeam.call(context);

        Assertions.assertTrue(result.contains("Perfect"));
        System.out.println("最终协作历史:\n" + trace1.getFormattedHistory());
    }
}