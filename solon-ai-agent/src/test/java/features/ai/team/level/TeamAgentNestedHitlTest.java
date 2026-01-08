package features.ai.team.level;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamInterceptor;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;

/**
 * TeamAgent 嵌套人工介入（HITL）测试
 * <p>场景：父团队包含一个子团队。当子团队任务完成后，拦截器在父团队层面触发挂起，等待人工批准后再交由父团队的其他成员（Reviewer）完成。</p>
 */
public class TeamAgentNestedHitlTest {

    @Test
    public void testNestedHumanIntervention() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        String parentTeamId = "manager_team";

        // 1. 构建子团队（Dev Team）
        TeamAgent devTeam = TeamAgent.of(chatModel)
                .name("dev_team")
                .addAgent(new Agent() {
                    @Override public String name() { return "Coder"; }
                    @Override public String description() { return "负责核心代码编写"; }
                    @Override public AssistantMessage call(Prompt prompt, AgentSession session) {
                        return ChatMessage.ofAssistant("支付模块代码已根据需求编写完成，请求 Review。");
                    }
                }).build();

        // 2. 构建父团队，并将子团队作为一个 Agent 加入
        TeamAgent projectTeam = TeamAgent.of(chatModel)
                .name(parentTeamId)
                .addAgent(devTeam) // 嵌套子团队
                .addAgent(new Agent() {
                    @Override public String name() { return "Reviewer"; }
                    @Override public String description() { return "负责代码质量终审"; }
                    @Override public AssistantMessage call(Prompt prompt, AgentSession session) {
                        return ChatMessage.ofAssistant("代码逻辑严谨，准予发布。 [FINISH]");
                    }
                })
                .addInterceptor(new TeamInterceptor() {
                    @Override
                    public void onNodeStart(FlowContext ctx, Node n) {
                        // 在 Supervisor 决策前拦截
                        if (Agent.ID_SUPERVISOR.equals(n.getId())) {
                            TeamTrace trace = ctx.getAs("__" + parentTeamId);
                            // 逻辑：如果 dev_team 已经产出了结果，但 manager 尚未批准，则强制挂起
                            if (trace != null && trace.getFormattedHistory().contains("dev_team")) {
                                if (!ctx.containsKey("approved")) {
                                    System.out.println("[HITL] 拦截器触发：检测到 dev_team 成果，等待经理人工批准...");
                                    ctx.stop();
                                }
                            }
                        }
                    }
                })
                .build();

        // 打印图结构 YAML 方便观察嵌套层级
        System.out.println("--- 团队执行图 (YAML) ---\n" + projectTeam.getGraph().toYaml());

        // 3. 创建 Session 包装执行上下文
        AgentSession session = InMemoryAgentSession.of("job_007");

        // --- 阶段 A：发起任务，预期在子团队完成后拦截 ---
        System.out.println(">>> 阶段 A: 提交开发任务...");
        projectTeam.call(Prompt.of("开发支付模块"), session);

        FlowContext context = session.getSnapshot();
        Assertions.assertTrue(context.isStopped(), "流程应在 dev_team 执行后被父团队拦截器挂起");
        Assertions.assertEquals(Agent.ID_SUPERVISOR, context.lastNodeId());

        TeamTrace trace = projectTeam.getTrace(session);
        Assertions.assertTrue(trace.getFormattedHistory().contains("dev_team"), "历史应包含子团队执行记录");
        Assertions.assertFalse(trace.getFormattedHistory().contains("Reviewer"), "Reviewer 不应在批准前介入");

        // --- 阶段 B：人工介入，批准成果 ---
        System.out.println("\n>>> 人工介入：经理查看 dev_team 成果，点击【批准】...");
        context.put("approved", true);

        // --- 阶段 C：恢复执行，完成后续 Review ---
        System.out.println(">>> 阶段 C: 恢复执行，转交给 Reviewer...");
        String finalResult = projectTeam.call(session).getContent();

        System.out.println("\n=== 最终协作结果 ===");
        System.out.println(finalResult);

        // 最终验证
        Assertions.assertTrue(finalResult.contains("准予发布"), "任务最终应通过 Reviewer 审核");
        System.out.println("\n=== 完整协作轨迹 ===\n" + trace.getFormattedHistory());
    }
}