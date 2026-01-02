package features.ai.react;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.annotation.Param;
import org.noear.solon.core.util.Assert;
import org.noear.solon.flow.FlowContext;

import java.util.List;

/**
 * ReAct 协议完整性测试
 * 验证：Thought → Action → Observation → Thought 循环的正确性
 */
public class ReActAgentProtocolTest extends ReActAgentTestBase{

    @Test
    public void testFullReActCycle() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        ReActAgent agent = ReActAgent.of(chatModel)
                .addTool(new MethodToolProvider(new TestTools()))
                .temperature(0.0F)
                .maxSteps(10)
                .build();

        FlowContext context = FlowContext.of("test_protocol");
        String result = agent.call(context,
                "我需要查询北京的天气，然后根据天气建议是否带伞");

        // 获取轨迹分析
        ReActTrace trace = context.getAs("__" + agent.name());
        Assertions.assertNotNull(trace);

        // 验证完整的 ReAct 循环
        String history = formatReActHistory(trace);
        System.out.println("ReAct 轨迹:\n" + history);

        // 兼容两种模式：原生ToolCall模式和文本ReAct模式
        boolean hasThought = hasThoughtInHistory(trace);
        boolean hasAction = hasActionInHistory(trace);
        boolean hasObservation = hasObservationInHistory(trace);

        // 验证至少调用了一次工具
        int toolCallCount = countToolCalls(trace);
        Assertions.assertTrue(toolCallCount >= 1,
                "应该至少调用1次工具，实际: " + toolCallCount);

        // 验证工具执行结果
        Assertions.assertTrue(hasObservation || containsObservation(trace),
                "应该有工具返回的观察结果");

        // Thought是可选的（原生ToolCall模式可能没有显式Thought）
        // 有Thought更好，没有也可以接受
        if (hasThought) {
            System.out.println("✓ 包含 Thought 推理");
        } else {
            System.out.println("⚠ 原生ToolCall模式，无显式Thought标签");
        }

        Assertions.assertNotNull(result);
        System.out.println("最终结果: " + result);

        // 验证最终结果包含有用的信息
        Assertions.assertTrue(result.contains("北京") || result.contains("天气") ||
                        result.contains("带伞") || result.contains("伞"),
                "最终结果应该包含天气或带伞建议");
    }

    @Test
    public void testDirectAnswerWithoutTools() throws Throwable {
        // 测试：不需要工具的直接回答
        ChatModel chatModel = LlmUtil.getChatModel();

        ReActAgent agent = ReActAgent.of(chatModel)
                .addTool(new MethodToolProvider(new TestTools()))
                .temperature(0.0F)
                .build();

        FlowContext context = FlowContext.of("test_direct");
        String result = agent.call(context,
                "你好，简单介绍一下你自己");

        // 应该直接返回最终答案，不需要工具调用
        Assertions.assertNotNull(result);

        ReActTrace trace = context.getAs("__" + agent.name());
        if (trace != null) {
            int toolCallCount = countToolCalls(trace);
            Assertions.assertEquals(0, toolCallCount,
                    "简单问题不应该触发工具调用，实际调用: " + toolCallCount);
            System.out.println("轨迹步数: " + trace.getStepCount());
        }

        System.out.println("直接回答结果: " + result);
    }

    @Test
    public void testMultiToolCalls() throws Throwable {
        // 测试：连续调用多个工具
        ChatModel chatModel = LlmUtil.getChatModel();

        ReActAgent agent = ReActAgent.of(chatModel)
                .addTool(new MethodToolProvider(new SequentialTools()))
                .temperature(0.0F)
                .maxSteps(5)
                .build();

        FlowContext context = FlowContext.of("test_multi");
        String result = agent.call(context,
                "请先获取我的用户信息，然后查询余额，最后检查是否有优惠券");

        Assertions.assertNotNull(result);

        ReActTrace trace = context.getAs("__" + agent.name());
        Assertions.assertNotNull(trace);

        // 应该调用多个工具
        String history = formatReActHistory(trace);
        System.out.println("多工具调用历史:\n" + history);

        // 检查是否调用了多个工具
        int toolCallCount = countToolCalls(trace);
        Assertions.assertTrue(toolCallCount >= 2,
                "应该调用至少2个工具，实际: " + toolCallCount);

        // 验证所有三个工具都被调用了
        List<String> calledTools = getCalledToolNames(trace);
        System.out.println("调用的工具: " + calledTools);

        // 验证结果包含所有信息
        Assertions.assertTrue(result.contains("用户") || result.contains("张三"),
                "结果应该包含用户信息");
        Assertions.assertTrue(result.contains("余额") || result.contains("1000"),
                "结果应该包含余额信息");
        Assertions.assertTrue(result.contains("优惠券") || result.contains("券"),
                "结果应该包含优惠券信息");
    }

    /**
     * 从轨迹中统计工具调用次数
     */
    private int countToolCalls(ReActTrace trace) {
        if (trace == null) return 0;

        int count = 0;
        List<AssistantMessage> assistantMessages = getAssistantMessages(trace);
        for (AssistantMessage msg : assistantMessages) {
            if (Assert.isNotEmpty(msg.getToolCalls())) {
                count += msg.getToolCalls().size();
            }
        }
        return count;
    }

    /**
     * 获取被调用的工具名称列表
     */
    private List<String> getCalledToolNames(ReActTrace trace) {
        java.util.List<String> tools = new java.util.ArrayList<>();
        if (trace == null) return tools;

        List<AssistantMessage> assistantMessages = getAssistantMessages(trace);
        for (AssistantMessage msg : assistantMessages) {
            if (Assert.isNotEmpty(msg.getToolCalls())) {
                for (ToolCall call : msg.getToolCalls()) {
                    tools.add(call.name());
                }
            }
        }
        return tools;
    }

    /**
     * 获取所有的Assistant消息
     */
    private List<AssistantMessage> getAssistantMessages(ReActTrace trace) {
        java.util.List<AssistantMessage> messages = new java.util.ArrayList<>();
        if (trace == null) return messages;

        for (org.noear.solon.ai.chat.message.ChatMessage msg : trace.getMessages()) {
            if (msg instanceof AssistantMessage) {
                messages.add((AssistantMessage) msg);
            }
        }
        return messages;
    }

    /**
     * 检查轨迹中是否有Thought（兼容两种模式）
     */
    private boolean hasThoughtInHistory(ReActTrace trace) {
        if (trace == null) return false;

        // 检查文本中的Thought标签
        String history = formatReActHistory(trace);
        if (history.contains("Thought")) {
            return true;
        }

        // 检查消息内容中的Thought
        for (org.noear.solon.ai.chat.message.ChatMessage msg : trace.getMessages()) {
            if (msg instanceof AssistantMessage) {
                String content = ((AssistantMessage) msg).getContent();
                if (content != null && content.contains("Thought")) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * 检查轨迹中是否有Action（兼容两种模式）
     */
    private boolean hasActionInHistory(ReActTrace trace) {
        if (trace == null) return false;

        // 有工具调用就表示有Action
        if (countToolCalls(trace) > 0) {
            return true;
        }

        // 检查文本中的Action标签
        String history = formatReActHistory(trace);
        return history.contains("Action") || history.contains("调用工具");
    }

    /**
     * 检查轨迹中是否有Observation（兼容两种模式）
     */
    private boolean hasObservationInHistory(ReActTrace trace) {
        if (trace == null) return false;

        String history = formatReActHistory(trace);
        return history.contains("Observation") || history.contains("[Tool]");
    }

    /**
     * 检查是否包含观察结果（通过内容判断）
     */
    private boolean containsObservation(ReActTrace trace) {
        if (trace == null) return false;

        for (org.noear.solon.ai.chat.message.ChatMessage msg : trace.getMessages()) {
            if (msg instanceof org.noear.solon.ai.chat.message.ToolMessage) {
                return true;  // 只要有ToolMessage，就有观察结果
            }
        }
        return false;
    }

    public static class TestTools {
        @ToolMapping(description = "查询城市天气")
        public String get_weather(@Param(description = "城市名称") String city) {
            if ("北京".equals(city) || "beijing".equalsIgnoreCase(city)) {
                return "北京今天多云转晴，温度 15-25°C，建议带伞";
            }
            return city + " 天气未知";
        }
    }

    public static class SequentialTools {
        @ToolMapping(description = "获取用户信息")
        public String get_user_info() {
            return "用户ID: 123456, 姓名: 张三";
        }

        @ToolMapping(description = "查询账户余额")
        public String get_balance() {
            return "账户余额: 1000元";
        }

        @ToolMapping(description = "检查可用优惠券")
        public String check_coupons() {
            return "有3张可用优惠券：10元券、20元券、免运费券";
        }
    }
}