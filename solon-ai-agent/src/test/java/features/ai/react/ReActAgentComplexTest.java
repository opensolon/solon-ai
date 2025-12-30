package features.ai.react;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.annotation.Param;
import org.noear.solon.flow.FlowContext;

/**
 * 复杂的业务场景测试：电商售后智能决策
 * 场景：客户投诉没收到货，Agent 需要查询订单 -> 查询物流 -> 根据金额和状态自动给出补偿建议
 */
public class ReActAgentComplexTest {

    @Test
    public void testCustomerServiceLogic() throws Throwable {
        // 使用高性能模型（如 Qwen2.5-32B 以上）处理复杂逻辑
        ChatModel chatModel = ChatModel.of("https://ai.gitee.com/v1/chat/completions")
                .apiKey("PE6JVMP7UQI81GY6AZ0J8WEWWLFHWHROG15XUP18")
                .model("Qwen3-32B")
                .build();

        ReActAgent agent = ReActAgent.builder(chatModel)
                .addTool(new MethodToolProvider(new OrderTools()))
                .addTool(new MethodToolProvider(new LogisticTools()))
                .addTool(new MethodToolProvider(new MarketingTools()))
                .temperature(0.0F) // 严格遵循逻辑
                .enableLogging(true)
                .maxIterations(10)
                .build();

        // 测试目标：
        // 1. 模型应发现需要先调 get_order 获取物流单号(track_123)
        // 2. 发现物流状态是 "lost" (丢件)
        // 3. 发现订单金额 > 100，触发 "refund" (全额退款) 而不是发优惠券
        String prompt = "我的订单号是 ORD_20251229，到现在还没收到货，帮我查查怎么回事并给出处理方案。";
        FlowContext context = FlowContext.of("demo1");

        String result = agent.ask(context, prompt);

        // 科学验证：
        Assertions.assertNotNull(result);
        // 使用正则或多关键字确保匹配，防止因措辞差异（如“丢失” vs “丢了”）导致失败
        boolean hasLostInfo = result.contains("丢失") || result.contains("lost") || result.contains("丢件");
        boolean hasRefundInfo = result.contains("退款") || result.contains("refund");

        Assertions.assertTrue(hasLostInfo, "应识别出物流丢失，当前结果：" + result);
        Assertions.assertTrue(hasRefundInfo, "应给出退款处理，当前结果：" + result);

        System.out.println("决策结果: " + result);
    }

    /**
     * 订单领域工具
     */
    public static class OrderTools {
        @ToolMapping(description = "根据订单号查询订单详情，包括商品ID、金额、物流单号")
        public String get_order(@Param(description = "订单号") String orderId) {
            if ("ORD_20251229".equals(orderId)) {
                return "{\"orderId\":\"ORD_20251229\", \"amount\": 158.0, \"trackNo\": \"track_123\", \"sku\": \"智能耳机\"}";
            }
            return "订单不存在";
        }
    }

    /**
     * 物流领域工具
     */
    public static class LogisticTools {
        @ToolMapping(description = "根据物流单号查询当前运输状态")
        public String get_logistic_status(@Param(description = "物流单号") String trackNo) {
            if ("track_123".equals(trackNo)) {
                return "{\"status\": \"lost\", \"info\": \"包裹在上海分拨中心丢失\"}";
            }
            return "查无此单";
        }
    }

    /**
     * 营销/补偿领域工具
     */
    public static class MarketingTools {
        @ToolMapping(description = "根据赔付策略发放补偿。如果是小额订单(<=100)发优惠券(coupon)，大额订单(>100)申请全额退款(refund)")
        public String apply_compensation(@Param(description = "赔付策略") String strategy, @Param(description = "订单金额") double amount) {
            if ("refund".equals(strategy) && amount > 100) {
                return "已成功提交退款申请，预计24小时到账。";
            } else if ("coupon".equals(strategy)) {
                return "已发放 20 元无门槛优惠券。";
            }
            return "策略不匹配，请人工审核。";
        }
    }
}