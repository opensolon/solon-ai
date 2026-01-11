package features.ai.react;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.annotation.Param;

/**
 * 复杂的业务场景测试：电商售后智能决策
 * <p>场景：客户投诉没收到货。ReAct 模式下的 Agent 需要自主完成以下链式决策：
 * 查询订单 -> 识别物流单号 -> 查询物流状态 -> 发现丢件 -> 根据订单金额选择赔付策略（退款）。</p>
 */
public class ReActAgentComplexTest {

    /**
     * 测试客户服务自动化决策逻辑
     * <p>验证目标：</p>
     * 1. 自动调用 get_order 获取物流单号 (track_123)。<br>
     * 2. 自动调用 get_logistic_status 识别出状态为 "lost" (丢件)。<br>
     * 3. 识别订单金额 > 100，最终调用 apply_compensation 触发 "refund" (全额退款) 流程。
     */
    @Test
    public void testCustomerServiceLogic() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 构建配置了领域工具集的 ReActAgent
        ReActAgent agent = ReActAgent.of(chatModel)
                .addDefaultTool(new MethodToolProvider(new OrderTools()))
                .addDefaultTool(new MethodToolProvider(new LogisticTools()))
                .addDefaultTool(new MethodToolProvider(new MarketingTools()))
                .chatOptions(o -> o.temperature(0.0F)) // 设置 0 温度以确保逻辑推导的确定性
                .maxSteps(10) // 给予充足的思考步数以支持复杂的链式调用
                .build();

        // 2. 初始化 AgentSession（替换原有的 FlowContext）
        // AgentSession 会自动持有会话状态并支持后续的持久化或恢复
        AgentSession session = InMemoryAgentSession.of("demo_customer_job_001");

        String userPrompt = "我的订单号是 ORD_20251229，到现在还没收到货，帮我查查怎么回事并给出处理方案。";

        // 3. 执行智能体调用
        // 使用 call(Prompt, AgentSession) 契约，这是 3.8.x 推荐的调用方式
        String result = agent.call(Prompt.of(userPrompt), session).getContent();

        // 4. 科学验证决策产出的准确性
        Assertions.assertNotNull(result, "智能体回复不应为空");

        // 校验逻辑：是否识别到丢件
        boolean hasLostInfo = result.contains("丢失") || result.contains("lost") || result.contains("丢件");
        // 校验逻辑：是否给出了正确的全额退款赔付方案
        boolean hasRefundInfo = result.contains("退款") || result.contains("refund");

        Assertions.assertTrue(hasLostInfo, "Agent 应通过工具查询识别出物流丢失状态。当前结果：" + result);
        Assertions.assertTrue(hasRefundInfo, "针对高额丢失订单，Agent 应自动触发退款申请。当前结果：" + result);

        System.out.println("--- 智能售后决策结果 ---");
        System.out.println(result);
    }

    // --- 领域工具定义 (Domain Tools) ---

    /**
     * 订单领域工具
     */
    public static class OrderTools {
        @ToolMapping(description = "根据订单号查询订单详情，获取商品名、金额、物流单号")
        public String get_order(@Param(description = "订单号") String orderId) {
            if ("ORD_20251229".equals(orderId)) {
                return "{\"orderId\":\"ORD_20251229\", \"amount\": 158.0, \"trackNo\": \"track_123\", \"sku\": \"智能耳机\"}";
            }
            return "{\"error\": \"订单不存在\"}";
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
            return "{\"error\": \"查无此单\"}";
        }
    }

    /**
     * 营销/补偿领域工具
     */
    public static class MarketingTools {
        @ToolMapping(description = "根据赔付策略发放补偿。规则：小额订单(<=100)发优惠券(coupon)；大额订单(>100)申请全额退款(refund)")
        public String apply_compensation(@Param(description = "赔付策略：coupon 或 refund") String strategy,
                                         @Param(description = "订单金额") double amount) {
            if ("refund".equals(strategy) && amount > 100) {
                return "【系统指令】已成功提交退款申请，金额 158.0 元预计 24 小时内原路退回。";
            } else if ("coupon".equals(strategy)) {
                return "【系统指令】已发放 20 元补偿优惠券至用户账户。";
            }
            return "【人工逻辑】赔付策略与金额不匹配，已转交人工客服审核。";
        }
    }
}