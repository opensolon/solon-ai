package features.ai.react.generated;

import demo.ai.llm.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.annotation.Param;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;

/**
 * ReActAgent 持久化与人工介入（HITL）联合场景测试
 * * <p>验证流程：拦截 -> 状态持久化 -> 异地恢复 -> 人工审批 -> 继续执行</p>
 */
public class ReActAgentPersistenceHitlTest {

    @Test
    public void testPersistenceAndHitlCombined() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        String agentName = "secure_refund_bot";

        // 1. 定义带有 HITL（人工介入）逻辑的拦截器
        ReActInterceptor hitlInterceptor = new ReActInterceptor() {
            @Override
            public void onNodeEnd(FlowContext ctx, Node node) {
                // 仅对 Action（工具调用）节点进行敏感操作拦截
                if (ReActAgent.ID_ACTION_AFT.equals(node.getId())) {
                    // 检查上下文中是否存在审批标记
                    if (!ctx.containsKey("is_approved")) {
                        System.out.println("[拦截器] 发现敏感退款申请，当前未审批。中断流程以待持久化...");
                        ctx.stop(); // 触发流程中断
                    }
                }
            }
        };

        // 2. 构建 Agent 并注入拦截器
        ReActAgent agent = ReActAgent.of(chatModel)
                .name(agentName)
                .defaultToolAdd(new RefundTools())
                .defaultInterceptorAdd(hitlInterceptor)
                .modelOptions(o -> o.temperature(0.0))
                .build();

        // --- 阶段 A：初始请求与拦截持久化 ---
        // 使用 AgentSession 替代原始 FlowContext 操作
        AgentSession session1 = InMemoryAgentSession.of("session_combined_999");
        String promptText = "帮我处理订单 ORD_101 的全额退款。";

        System.out.println("--- 阶段 A：发起初始请求 ---");
        // 执行调用，预期会被拦截器中断
        agent.call(Prompt.of(promptText), session1);

        // 获取快照进行状态验证
        FlowContext context1 = session1.getSnapshot();
        Assertions.assertTrue(context1.isStopped(), "流程应在 Action 节点被拦截");
        Assertions.assertEquals(ReActAgent.ID_ACTION_AFT, context1.lastNodeId());

        // 执行持久化序列化（模拟将当前状态存入数据库）
        String jsonState = context1.toJson();
        System.out.println(">>> 状态已持久化。内容预览：" + jsonState.substring(0, 100) + "...");

        // --- 阶段 B：从持久化数据恢复并人工审批 ---
        System.out.println("\n--- 阶段 B：从持久化数据恢复并注入审批信号 ---");

        // 模拟在另一个环境（或重启后）通过 JSON 恢复 FlowContext
        FlowContext restoredContext = FlowContext.fromJson(jsonState);
        // 将恢复的上下文重新包装进 AgentSession
        AgentSession session2 = InMemoryAgentSession.of(restoredContext);

        // 验证轨迹状态是否成功恢复
        ReActTrace restoredTrace = restoredContext.getAs("__" + agentName);
        Assertions.assertNotNull(restoredTrace, "恢复后的轨迹（Trace）不应为空");
        System.out.println("恢复后的断点路由：" + restoredTrace.getRoute());

        // 注入人工审批信号（满足拦截器放行条件）
        restoredContext.put("is_approved", true);
        System.out.println("[人工操作] 管理员已在线批准该退款申请。");

        // --- 阶段 C：恢复执行并获取最终结果 ---
        System.out.println("\n--- 阶段 C：继续执行 ---");
        // 继续执行时无需重传 Prompt，Agent 会从 session 的快照中自动寻址
        String finalResult = agent.resume(session2).getContent();

        System.out.println("最终回复内容: " + finalResult);

        // 验证最终业务逻辑是否正确完成
        Assertions.assertNotNull(finalResult);
        Assertions.assertTrue(finalResult.contains("ORD_101") &&
                        (finalResult.contains("成功") || finalResult.contains("已执行")),
                "审批并恢复后应成功执行退款工具并返回正确结果");

        // 验证轨迹记录是否完整
        ReActTrace finalTrace = session2.getSnapshot().getAs("__" + agentName);
        Assertions.assertTrue(finalTrace.getFormattedHistory().contains("Action"), "历史记录中应包含工具执行信息");
    }

    /**
     * 退款业务工具类
     */
    public static class RefundTools {
        @ToolMapping(description = "执行退款操作")
        public String do_refund(@Param(description = "订单号") String orderId) {
            return "【系统消息】订单 " + orderId + " 退款指令已执行，款项将原路返回。";
        }
    }
}