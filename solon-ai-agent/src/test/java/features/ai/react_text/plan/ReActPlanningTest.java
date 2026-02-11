package features.ai.react_text.plan;

import demo.ai.llm.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActResponse;
import org.noear.solon.ai.agent.react.ReActStyle;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.annotation.Param;

import java.util.List;

/**
 * ReAct Planning 规划功能测试
 */
public class ReActPlanningTest {

    /**
     * 测试 1：验证基础规划能力
     * 目标：确认 planningMode(true) 后，Trace 中是否成功生成了 Plans
     */
    @Test
    public void testBasicPlanning() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 构建开启 Planning 的 Agent
        ReActAgent agent = ReActAgent.of(chatModel)
                .style(ReActStyle.STRUCTURED_TEXT)
                .name("planner_agent")
                .planningMode(true) // 开启规划
                .defaultToolAdd(new InfoTools())
                .build();

        AgentSession session = InMemoryAgentSession.of("plan_001");
        String question = "帮我查一下北京的天气，然后根据天气建议我穿什么。";

        // 2. 执行调用
        agent.call(Prompt.of(question), session);

        // 3. 核心验证：从 Trace 中获取生成的计划
        ReActTrace trace = agent.getTrace(session);

        Assertions.assertNotNull(trace, "轨迹不应为空");
        Assertions.assertTrue(trace.hasPlans(), "应该生成了执行计划");

        List<String> plans = trace.getPlans();
        System.out.println("生成的计划步骤：");
        plans.forEach(p -> System.out.println("- " + p));

        // 验证计划是否包含关键步骤（模糊匹配）
        boolean hasWeatherPlan = plans.stream().anyMatch(p -> p.contains("天气") || p.contains("Weather"));
        Assertions.assertTrue(hasWeatherPlan, "计划中应包含查询天气的步骤");
    }

    /**
     * 测试 2：验证多步骤复杂规划执行
     * 目标：通过多个工具组合，观察 Agent 是否按照规划完成闭环
     */
    @Test
    public void testComplexTaskExecutionWithPlan() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        ReActAgent agent = ReActAgent.of(chatModel)
                .style(ReActStyle.STRUCTURED_TEXT)
                .planningMode(true)
                .defaultToolAdd(new OrderTools())
                .build();

        AgentSession session = InMemoryAgentSession.of("plan_002");
        // 这是一个典型的需要拆解的任务
        String question = "先查询订单号为 1001 的状态，如果是'已支付'，就触发发货流程。";

        String result = agent.call(Prompt.of(question), session).getContent();

        System.out.println("最终回复: " + result);

        // 验证结果
        Assertions.assertTrue(result.contains("发货") || result.contains("Success"), "任务执行结果不符合预期");

        // 验证 Trace 步数
        ReActTrace trace = agent.getTrace(session);
        Assertions.assertTrue(trace.getStepCount() >= 2, "多步骤任务推理步数应大于等于2");
    }

    /**
     * 测试 3：验证计划重置逻辑
     * 目标：确认在同一 Session 下，发送新问题时，旧计划会被清空并重新生成
     */
    @Test
    public void testPlanResetOnNewPrompt() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        ReActAgent agent = ReActAgent.of(chatModel)
                .style(ReActStyle.STRUCTURED_TEXT)
                .planningMode(true)
                .build();

        AgentSession session = InMemoryAgentSession.of("plan_003");

        // 第一轮：任务 A
        agent.call(Prompt.of("计算 1+1"), session);
        String firstPlans = agent.getTrace(session).getPlans().toString();
        Assertions.assertFalse(firstPlans.isEmpty());

        // 第二轮：任务 B（此时应触发 call 中的 trace.setPlans(null)）
        agent.call(Prompt.of("查询明天的天气"), session);
        String secondPlans = agent.getTrace(session).getPlans().toString();

        System.out.println("第一轮计划：" + firstPlans);
        System.out.println("第二轮计划：" + secondPlans);

        // 验证计划已更新（不相等）
        Assertions.assertNotEquals(firstPlans, secondPlans, "新问题应该生成新的计划");
    }

    // --- 模拟工具类 ---

    public static class InfoTools {
        @ToolMapping(description = "查询指定城市的天气")
        public String getWeather(@Param(description = "城市名称") String city) {
            return city + "当前天气：晴，25度。";
        }
    }

    public static class OrderTools {
        @ToolMapping(description = "查询订单状态")
        public String getOrderStatus(@Param(description = "订单ID") String orderId) {
            return "订单 " + orderId + " 状态为：已支付";
        }

        @ToolMapping(description = "执行发货流程")
        public String shipOrder(@Param(description = "订单ID") String orderId) {
            return "订单 " + orderId + " 已进入发货流程，操作成功。";
        }
    }

    @Test
    public void testDynamicPlanningToggle() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. Builder 默认关闭
        ReActAgent agent = ReActAgent.of(chatModel)
                .style(ReActStyle.STRUCTURED_TEXT)
                .planningMode(false)
                .build();
        AgentSession session = InMemoryAgentSession.of("dynamic_001");

        // 2. 在 call 级别动态开启
        agent.prompt("计算 1+2+3")
                .session(session)
                .options(o -> o.planningMode(true))
                .call();

        // 3. 验证是否有计划
        Assertions.assertFalse(agent.getTrace(session).hasPlans(), "简单问题应该不需要计划");

        // 4. 下一次调用不传 options（回归默认关闭）
        agent.call(Prompt.of("再计算一次"), session);
        Assertions.assertFalse(agent.getTrace(session).hasPlans(), "回归默认后计划应被清空且不再生成");
    }

    @Test
    public void testFeedbackMode() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        ReActAgent agent = ReActAgent.of(chatModel)
                .style(ReActStyle.STRUCTURED_TEXT)
                .planningMode(true)
                .feedbackMode(true)
                .build();
        AgentSession session = InMemoryAgentSession.of("custom_plan_001");

        ReActResponse resp = agent.prompt("请通过不断思考，尽可能深入地分析这个问题")
                .session(session)
                .call();

        System.out.println("=====最终输出=====");
        System.out.println(resp.getContent());

        Assertions.assertEquals(1, resp.getTrace().getStepCount(), "反馈模式没有生效");
    }
}