package features.ai.team.level;

import demo.ai.llm.LlmUtil;
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
        // 优化点：加入 [FINISH] 信号，防止子团队 Supervisor 因为“看不懂是否结束”而反复调用 Coder
        TeamAgent devTeam = TeamAgent.of(chatModel)
                .name("dev_team")
                .description("代码实现小组")
                .agentAdd(new Agent() {
                    @Override public String name() { return "Coder"; }
                    @Override public String description() { return "负责核心代码编写"; }
                    @Override public AssistantMessage call(Prompt prompt, AgentSession session) {
                        return ChatMessage.ofAssistant("支付模块代码已编写完成，请求 Review。[FINISH]");
                    }
                }).build();

        // 2. 构建父团队：注入 SystemPrompt 确保结果收敛
        TeamAgent projectTeam = TeamAgent.of(chatModel)
                .name(parentTeamId)
                .systemPrompt(p->p
                        .role("你是一个严谨的研发主管。")
                        .instruction(trace ->
                                "协作规则：\n" +
                                        "1. dev_team 提交后，必须指派 Reviewer 审核。\n" +
                                        "2. 当任务完成时，请务必汇总 Reviewer 的最终评价作为回复内容。"))
                .agentAdd(devTeam)
                .agentAdd(new Agent() {
                    @Override public String name() { return "Reviewer"; }
                    @Override public String description() { return "负责代码质量终审"; }
                    @Override public AssistantMessage call(Prompt prompt, AgentSession session) {
                        return ChatMessage.ofAssistant("代码逻辑严谨，准予发布。[FINISH]");
                    }
                })
                .defaultInterceptorAdd(new TeamInterceptor() {
                    @Override
                    public void onNodeEnd(FlowContext ctx, Node n) {
                        // 关键点：当 dev_team 这个节点执行完时，立即挂起，不给 Supervisor 决策的机会
                        if ("dev_team".equals(n.getId())) {
                            if (!ctx.containsKey("approved")) {
                                System.out.println("[HITL] 拦截器捕获：dev_team 已完成，强制挂起等待人工批准...");
                                ctx.stop();
                            }
                        }
                    }
                })
                .build();

        // 3. 创建 Session
        AgentSession session = InMemoryAgentSession.of("job_007");

        // --- 阶段 A：发起任务 ---
        System.out.println(">>> 阶段 A: 提交开发任务...");
        projectTeam.call(Prompt.of("开发支付模块"), session);

        FlowContext context = session.getSnapshot();
        Assertions.assertTrue(context.isStopped(), "流程应在 dev_team 执行后被挂起");

        // --- 阶段 B：人工介入 ---
        System.out.println("\n>>> 阶段 B: 人工介入，点击【批准】...");
        context.put("approved", true);

        // --- 阶段 C：恢复执行 ---
        System.out.println(">>> 阶段 C: 恢复执行，转交给 Reviewer...");
        // 续跑流程
        projectTeam.call(session);

        // 关键优化：从 Trace 获取 FinalAnswer。
        // 框架逻辑中，call().getContent() 可能返回的是最后一次交互消息，
        // 而真正的业务结论在 Trace 的 finalAnswer 字段里。
        TeamTrace trace = projectTeam.getTrace(session);
        String finalResult = trace.getFinalAnswer();

        // 如果 LLM 没有在结束时重复结论，我们兜底取最后一个专家的内容
        if (finalResult == null || !finalResult.contains("准予发布")) {
            finalResult = trace.getLastAgentContent();
        }

        System.out.println("\n=== 最终协作结果 ===");
        System.out.println(finalResult);

        // 最终验证
        Assertions.assertTrue(finalResult.contains("准予发布"), "任务最终应包含 Reviewer 的审核结论");
        System.out.println("\n=== 完整协作轨迹 ===\n" + trace.getFormattedHistory());
    }
}