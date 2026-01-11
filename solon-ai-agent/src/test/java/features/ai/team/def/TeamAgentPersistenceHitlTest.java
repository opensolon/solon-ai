package features.ai.team.def;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamInterceptor;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;

/**
 * TeamAgent 持久化与人工介入（HITL）联合场景测试
 * * <p>测试场景：
 * 1. Agent 团队执行过程中产生阶段性产出。
 * 2. 拦截器检测到产出后强制挂起流程，模拟进入人工审批。
 * 3. 状态序列化为 JSON 模拟持久化。
 * 4. 从 JSON 恢复状态并注入审批通过信号，AI 继续执行完成后续任务。</p>
 */
public class TeamAgentPersistenceHitlTest {

    @Test
    public void testCombinedPersistenceAndHitl() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        String teamId = "approval_team";

        // 1. 构建团队并注入 HITL 拦截器
        TeamAgent teamAgent = TeamAgent.of(chatModel)
                .name(teamId)
                .agentAdd(ReActAgent.of(chatModel).name("worker").title("初稿撰写").build())
                .agentAdd(ReActAgent.of(chatModel).name("approver").title("修辞优化").build())
                .defaultInterceptorAdd(new TeamInterceptor() {
                    @Override
                    public void onNodeStart(FlowContext ctx, Node n) {
                        // 逻辑：只要检测到已有阶段性产出（StepCount > 0），且没有审批信号，则挂起
                        TeamTrace trace = ctx.getAs("__" + teamId);
                        if (trace != null && trace.getStepCount() > 0) {
                            if (!ctx.model().containsKey("manager_ok")) {
                                System.out.println("[HITL] 拦截器触发：检测到 worker 已产出初稿，挂起流程等待人工审批...");
                                ctx.stop();
                            }
                        }
                    }
                })
                .build();

        // --- 阶段 A: 发起请求并触发自动挂起 ---
        // 使用 AgentSession 替代直接操作 FlowContext
        AgentSession session1 = InMemoryAgentSession.of("order_hitl_001");

        System.out.println(">>> 阶段 A: 启动流程...");
        teamAgent.call(Prompt.of("请起草一份周报内容"), session1);

        // 获取底层快照验证状态
        FlowContext context1 = session1.getSnapshot();
        Assertions.assertTrue(context1.isStopped(), "流程应该在产生初步结果后被拦截器挂起");

        // 模拟持久化：将 Context 序列化为 JSON 字符串
        String jsonState = context1.toJson();
        TeamTrace trace1 = context1.getAs("__" + teamId);
        System.out.println(">>> 流程已挂起并持久化。当前执行步骤数: " + trace1.getStepCount());


        // --- 阶段 B: 从持久化快照恢复并注入审批信号 ---
        System.out.println("\n>>> 阶段 B: 从 JSON 恢复流程并注入审批信号...");

        // 从反序列化的 Context 重建 Session
        FlowContext restoredContext = FlowContext.fromJson(jsonState);
        AgentSession session2 = InMemoryAgentSession.of(restoredContext);

        // 模拟人工操作：注入审批通过信号
        restoredContext.put("manager_ok", true);

        // 继续执行：此时不传 Prompt，系统会基于 Session 中的 Trace 自动恢复执行
        String finalResult = teamAgent.call(session2).getContent();

        System.out.println("=== 最终执行结果 ===");
        System.out.println(finalResult);

        // 验证流程最终完成
        Assertions.assertTrue(restoredContext.lastRecord().isEnd(), "流程在审批通过后应成功结束");
        Assertions.assertTrue(finalResult.contains("周报"), "结果应包含周报核心内容");

        TeamTrace finalTrace = teamAgent.getTrace(session2);
        Assertions.assertTrue(finalTrace.getStepCount() > trace1.getStepCount(), "恢复执行后应产生更多的执行轨迹");
    }
}