package features.ai.react.plan;

import demo.ai.llm.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActResponse;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.annotation.Param;

import java.util.List;

/**
 * ReAct Planning 规划功能测试 (针对 3.9.3 智能降级版优化)
 */
public class ReActPlanningTest {

    /**
     * 测试 1：验证复杂任务的正常规划能力
     * 优化点：通过“深度对比”和“多阶段要求”强制触发规划
     */
    @Test
    public void testComplexPlanning() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        ReActAgent agent = ReActAgent.of(chatModel)
                .name("planner_agent")
                .planningMode(true)
                .defaultToolAdd(new InfoTools())
                .build();

        AgentSession session = InMemoryAgentSession.of("plan_001");

        // 增加任务深度：要求对比和分阶段规划
        String question = "先帮我查北京和上海的天气，对比两地的气温差异，" +
                "然后根据对比结果为我规划一份明天的户外拍摄行程，包含上午和下午的具体安排。";

        agent.call(Prompt.of(question), session);
        ReActTrace trace = agent.getTrace(session);

        Assertions.assertTrue(trace.hasPlans(), "多阶段任务必须生成执行计划");
        System.out.println("生成的复杂计划：\n" + trace.getPlans());
    }

    /**
     * 测试 2：验证简单任务的“智能降级” (保持不变)
     */
    @Test
    public void testSimpleTaskDegradation() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        ReActAgent agent = ReActAgent.of(chatModel).planningMode(true).build();
        AgentSession session = InMemoryAgentSession.of("plan_002");

        agent.prompt("1+1=?").session(session).call();
        ReActTrace trace = agent.getTrace(session);

        Assertions.assertFalse(trace.hasPlans(), "简单计算任务应被智能降级（plans 为空）");
    }

    /**
     * 测试 3：验证计划在 Session 中的重置逻辑
     * 优化点：第一轮任务必须足够重，确保 100% 触发计划
     */
    @Test
    public void testPlanResetAndClear() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        ReActAgent agent = ReActAgent.of(chatModel)
                .planningMode(true)
                .defaultToolAdd(new InfoTools())
                .build();

        AgentSession session = InMemoryAgentSession.of("plan_reset_003");

        // 1. 第一轮：极其复杂的任务
        String complexTask = "详细查杭州天气，并根据气象情况（是否有雨、气温、风力）为我制定一份详细的户外骑行路线及安全注意事项。";
        ReActResponse resp1 = agent.prompt(complexTask).session(session).call();
        Assertions.assertTrue(resp1.getTrace().hasPlans(), "复杂长链路任务应持有计划");

        // 2. 第二轮：简单任务
        ReActResponse resp2 = agent.prompt("好的，现在请告诉我 2 乘 8 等于几").session(session).call();

        // 核心验证：plans 必须从有变为无
        Assertions.assertFalse(resp2.getTrace().hasPlans(), "切换到简单任务后，旧计划必须被清空");
    }

    /**
     * 测试 5：验证多工具协作下的闭环
     * 优化点：通过“逻辑分支”和“多步确认”强制模型必须拆解步骤
     */
    @Test
    public void testOrderFlowExecution() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        ReActAgent agent = ReActAgent.of(chatModel)
                .planningMode(true)
                .defaultToolAdd(new OrderTools())
                .build();

        AgentSession session = InMemoryAgentSession.of("order_005");

        // 增加逻辑密度：查询 -> 判断 -> 检查附加条件 -> 执行
        String question = "请帮我处理订单 1001：首先核对状态，如果已经支付，" +
                "请再确认一下是否有特殊的发货要求，最后执行发货流程并反馈结果。";

        ReActResponse resp = agent.prompt(question).session(session).call();
        ReActTrace trace = resp.getTrace();

        System.out.println("订单流回复: " + resp.getContent());

        // 核心断言：逻辑分支任务应触发计划
        Assertions.assertTrue(trace.hasPlans(), "涉及多步核对和逻辑分支的任务应生成计划");
        Assertions.assertTrue(resp.getContent().contains("成功"), "执行结果应反馈成功");
    }

    // --- 模拟工具类 ---

    public static class InfoTools {
        @ToolMapping(description = "查询指定城市的天气")
        public String getWeather(@Param(name = "city", description = "城市名称") String city) {
            return city + "当前天气：晴，25度，适宜户外活动。";
        }
    }

    public static class OrderTools {
        @ToolMapping(description = "查询订单状态")
        public String getOrderStatus(@Param(name = "orderId", description = "订单ID") String orderId) {
            return "订单 " + orderId + " 状态为：已支付";
        }

        @ToolMapping(description = "执行发货流程")
        public String shipOrder(@Param(name = "orderId", description = "订单ID") String orderId) {
            return "订单 " + orderId + " 已进入发货流程，操作成功。";
        }
    }
}