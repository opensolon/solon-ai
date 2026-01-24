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

import java.util.List;

/**
 * TeamAgent 持久化与人工介入（HITL）联合场景测试
 */
public class TeamAgentPersistenceHitlCombinedTest {

    @Test
    public void testCombinedScenario() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        String teamName = "combined_manager";

        // 1. 构建团队
        TeamAgent projectTeam = TeamAgent.of(chatModel)
                .name(teamName)
                .systemPrompt(p->p
                        .role("你是一个财务合规主管。")
                        .instruction(trace -> "规则：Worker 提交后流程挂起；收到 signed 信号后指派 Approver。任务结束时请复述 Approver 的结论。"))
                .agentAdd(new Agent() {
                    @Override public String name() { return "Worker"; }
                    @Override public String description() { return "处理业务逻辑"; }
                    @Override public AssistantMessage call(Prompt prompt, AgentSession session) {
                        return ChatMessage.ofAssistant("单据初稿已处理完成。[FINISH]");
                    }
                })
                .agentAdd(new Agent() {
                    @Override public String name() { return "Approver"; }
                    @Override public String description() { return "最终签字审批"; }
                    @Override public AssistantMessage call(Prompt prompt, AgentSession session) {
                        return ChatMessage.ofAssistant("核对无误，签字通过。[FINISH]");
                    }
                })
                .defaultInterceptorAdd(new TeamInterceptor() {
                    @Override
                    public void onNodeStart(FlowContext ctx, Node n) {
                        if (TeamAgent.ID_SUPERVISOR.equals(n.getId())) {
                            TeamTrace trace = ctx.getAs("__" + teamName);
                            if (trace == null) return;

                            boolean hasWorker = trace.getFormattedHistory().contains("Worker");
                            boolean hasApprover = trace.getFormattedHistory().contains("Approver");

                            if (hasWorker && !hasApprover && !ctx.containsKey("signed")) {
                                System.out.println("[HITL] 拦截成功：等待人工注入 signed 信号...");
                                ctx.stop();
                            }
                        }
                    }
                })
                .build();

        // --- 阶段 1：启动并触发挂起 ---
        System.out.println(">>> 阶段 1：提交初始任务...");
        AgentSession session1 = InMemoryAgentSession.of("job_001");
        projectTeam.call(Prompt.of("处理财务单据"), session1);

        FlowContext context1 = session1.getSnapshot();
        Assertions.assertTrue(context1.isStopped(), "流程应在 Worker 执行后被拦截器挂起");

        String jsonState = context1.toJson();
        System.out.println(">>> 阶段 1 完成：快照已序列化。");

        // --- 阶段 2：恢复快照并注入人工信号 ---
        System.out.println("\n>>> 阶段 2：人工批准，注入 signed 信号并恢复执行...");
        FlowContext context2 = FlowContext.fromJson(jsonState);
        AgentSession session2 = InMemoryAgentSession.of(context2);

        // 注入人工干预信号
        context2.put("signed", true);

        // 恢复执行
        projectTeam.resume(session2);

        // --- 阶段 3：精准结果提取与最终验证 ---
        TeamTrace trace = projectTeam.getTrace(session2);

        // 获取历史步骤
        List<TeamTrace.TeamRecord> steps = trace.getRecords();

        // 修正点：从 Steps 列表中【倒序】查找第一个非主管的专家消息
        // 之前的 reduce 在某些 List 实现或反序列化集合中可能指向了错误位置
        String finalResult = "";
        for (int i = steps.size() - 1; i >= 0; i--) {
            TeamTrace.TeamRecord step = steps.get(i);
            if (!TeamAgent.ID_SUPERVISOR.equalsIgnoreCase(step.getSource())) {
                finalResult = step.getContent();
                break;
            }
        }

        // 兜底：如果专家列表没取到，尝试从 FinalAnswer 取
        if (finalResult == null || !finalResult.contains("签字通过")) {
            String finalAnswer = trace.getFinalAnswer();
            if (finalAnswer != null) finalResult = finalAnswer;
        }

        System.out.println("\n=== 最终提取结果 ===");
        System.out.println(finalResult);

        System.out.println("\n=== 协作轨迹调试 ===");
        steps.forEach(s -> System.out.println("[" + s.getSource() + "]: " + s.getContent()));

        // 最终验证
        Assertions.assertTrue(trace.getFormattedHistory().contains("Approver"), "轨迹中应包含 Approver 的记录");
        Assertions.assertTrue(finalResult != null && finalResult.contains("签字通过"), "最终提取的结果必须包含审批通过的信息");
        System.out.println("\n[SUCCESS] 测试通过！");
    }
}