package features.ai.react;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.annotation.Param;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;

/**
 * ReActAgent 持久化与人工介入（HITL）联合场景测试
 * 验证：拦截 -> 状态持久化 -> 异地恢复 -> 人工审批 -> 继续执行
 */
public class ReActAgentPersistenceHitlTest {

    @Test
    public void testPersistenceAndHitlCombined() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        String agentName = "secure_refund_bot";

        // 1. 定义带有 HITL 逻辑的拦截器
        ReActInterceptor hitlInterceptor = new ReActInterceptor() {
            @Override
            public void onNodeStart(FlowContext ctx, Node node) {
                // 仅对 Action（工具调用）节点进行敏感操作拦截
                if (Agent.ID_ACTION.equals(node.getId())) {
                    if (!ctx.model().containsKey("is_approved")) {
                        System.out.println("[拦截器] 发现退款申请，当前未审批。中断流程以待持久化...");
                        ctx.stop();
                    }
                }
            }
        };

        // 2. 构建 Agent
        ReActAgent agent = ReActAgent.of(chatModel)
                .name(agentName)
                .addTool(new MethodToolProvider(new RefundTools()))
                .interceptor(hitlInterceptor)
                .chatOptions(o -> o.temperature(0.0F))
                .build();

        // --- 阶段 A：初始请求与拦截持久化 ---
        FlowContext context1 = FlowContext.of("session_combined_999");
        String prompt = "帮我处理订单 ORD_101 的全额退款。";

        System.out.println("--- 阶段 A：发起请求 ---");
        agent.call(context1, prompt);

        // 验证：是否在 Action 节点停止
        Assertions.assertTrue(context1.isStopped(), "流程应在 Action 节点被拦截");
        Assertions.assertEquals(Agent.ID_ACTION, context1.lastNodeId());

        // 执行持久化序列化（模拟存入数据库）
        String jsonState = context1.toJson();
        System.out.println(">>> 状态已序列化并持久化。内容预览：" + jsonState.substring(0, 100) + "...");

        // --- 阶段 B：从持久化数据恢复并人工审批 ---
        System.out.println("\n--- 阶段 B：从持久化数据恢复并执行审批 ---");

        // 模拟在一个全新的 context 对象（甚至可能是另一台服务器）中恢复
        FlowContext context2 = FlowContext.fromJson(jsonState);

        // 验证恢复后的状态
        ReActTrace restoredTrace = context2.getAs("__" + agentName);
        Assertions.assertNotNull(restoredTrace, "恢复后的轨迹不应为空");
        System.out.println("恢复后的断点路由：" + restoredTrace.getRoute());

        // 注入人工审批信号
        context2.put("is_approved", true);
        System.out.println("[人工操作] 管理员已在线批准该退款申请。");

        // --- 阶段 C：恢复执行 ---
        System.out.println("\n--- 阶段 C：继续执行 ---");
        // 调用时无需再次传入 prompt，Agent 会自动从恢复的 Trace 中读取上下文
        String finalResult = agent.call(context2);

        // 验证最终结果
        Assertions.assertNotNull(finalResult);
        Assertions.assertTrue(finalResult.contains("ORD_101") && finalResult.contains("成功"),
                "审批并恢复后应成功执行工具并返回结果");

        System.out.println("最终回复内容: " + finalResult);

        // 验证轨迹是否完整
        ReActTrace finalTrace = context2.getAs("__" + agentName);
        Assertions.assertTrue(finalTrace.getFormattedHistory().contains("Action"), "历史中应包含工具调用记录");
    }

    public static class RefundTools {
        @ToolMapping(description = "执行退款操作")
        public String do_refund(@Param(description = "订单号") String orderId) {
            return "【系统消息】订单 " + orderId + " 退款指令已执行，款项将在1-3个工作日内原路返回。";
        }
    }
}