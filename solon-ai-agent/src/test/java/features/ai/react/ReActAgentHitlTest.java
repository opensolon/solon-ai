package features.ai.react;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.ReActRecord;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.annotation.Param;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.NodeType;

/**
 * 人工介入（HITL）场景测试
 * 场景：Agent 申请退款，拦截器发现是 node_tools 节点且涉及敏感操作，执行中断。
 * 人工审核通过后，恢复执行。
 */
public class ReActAgentHitlTest {

    @Test
    public void testHumanInTheLoop() throws Throwable {
        ChatModel chatModel = ChatModel.of("https://ai.gitee.com/v1/chat/completions")
                .apiKey("PE6JVMP7UQI81GY6AZ0J8WEWWLFHWHROG15XUP18")
                .model("Qwen3-32B")
                .build();

        // 1. 定义人工介入拦截器
        ReActInterceptor hitlInterceptor = ReActInterceptor.builder()
                .onNodeStart((ctx, node) -> {
                    // 如果进入工具执行节点，且尚未获得人工批准
                    if (ReActRecord.ROUTE_ACTION.equals(node.getId())) {
                        Boolean approved = ctx.getAs("is_approved");
                        if (approved == null) {
                            System.out.println("[拦截器] 检测到敏感工具调用，等待人工审批...");
                            ctx.stop(); // 关键：中断流程
                        }
                    }
                })
                .build();

        ReActAgent agent = ReActAgent.builder(chatModel)
                .addTool(new MethodToolProvider(new RefundTools()))
                .interceptor(hitlInterceptor) // 注入拦截器
                .temperature(0.0F)
                .enableLogging(true)
                .build();

        FlowContext context = FlowContext.of("hitl_session_123");
        String prompt = "订单 ORD_888 没收到货，请帮我全额退款。";

        // --- 第一步：发起请求，预期会被拦截 ---
        System.out.println("--- 第一次调用 (预期拦截) ---");
        String result1 = agent.ask(context, prompt);

        // 验证：结果应为空（或中间态），且 context 处于 stopped 状态
        Assertions.assertTrue(context.lastNode().getType() != NodeType.END, "流程应该被拦截并停止");
        Assertions.assertEquals(ReActRecord.ROUTE_ACTION, context.lastNodeId(), "最后停留在工具节点");

        ReActRecord state = context.getAs(ReActRecord.TAG);
        Assertions.assertTrue(state.getIteration().get() > 0);
        System.out.println("当前状态：" + state.getRoute());

        // --- 第二步：人工介入，注入批准信号 ---
        System.out.println("\n--- 人工介入：批准退款 ---");
        context.put("is_approved", true);

        // --- 第三步：恢复执行 ---
        System.out.println("--- 第二次调用 (恢复执行) ---");
        // 恢复时传入原 context，prompt 会从 state 中自动获取
        String result2 = agent.ask(context, null);

        // 验证：最终结果应包含退款成功的关键字
        Assertions.assertNotNull(result2);
        Assertions.assertTrue(result2.contains("成功") || result2.contains("退款"), "审批后应执行成功");
        System.out.println("最终答复: " + result2);
    }

    public static class RefundTools {
        @ToolMapping(description = "执行退款操作")
        public String do_refund(@Param(description = "订单号") String orderId) {
            return "订单 " + orderId + " 已退款成功，金额将原路返回。";
        }
    }
}