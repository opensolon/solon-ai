package features.ai.react;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.flow.FlowContext;

import java.util.List;
import java.util.stream.Collectors;

/**
 * ReActAgent 边界条件和错误处理测试
 */
public class ReActAgentBoundaryTest {

    @Test
    public void testMaxStepsReached() throws Throwable {
        // 测试：达到最大步数限制
        ChatModel chatModel = LlmUtil.getChatModel();

        ReActAgent agent = ReActAgent.of(chatModel)
                .addTool(new MethodToolProvider(new LoopTools()))
                .chatOptions(o -> o.temperature(0.0F))
                .maxSteps(3) // 设置很小的步数限制
                .build();

        FlowContext context = FlowContext.of("test_max_steps");
        String result = agent.call(context,
                "这个问题需要多次思考");

        Assertions.assertNotNull(result);
        Assertions.assertTrue(result.contains("Maximum iterations") ||
                        result.contains("max steps") ||
                        result.contains("迭代"),
                "应该触发最大步数限制: " + result);

        ReActTrace trace = context.getAs("__" + agent.name());
        Assertions.assertNotNull(trace);
        Assertions.assertTrue(trace.getStepCount() >= 3);
    }

    @Test
    public void testToolNotFound() throws Throwable {
        // 测试：请求不存在的工具
        ChatModel chatModel = LlmUtil.getChatModel();

        ReActAgent agent = ReActAgent.of(chatModel)
                .addTool(new MethodToolProvider(new BasicTools()))
                .chatOptions(o -> o.temperature(0.0F))
                .build();

        FlowContext context = FlowContext.of("test_tool_not_found");
        String result = agent.call(context,
                "请调用一个不存在的工具，比如 non_existent_tool");

        Assertions.assertNotNull(result);

        // 可能返回错误信息或尝试其他方式
        System.out.println("工具不存在时的结果: " + result);
    }

    @Test
    public void testToolExceptionHandling() throws Throwable {
        // 测试：工具抛异常的处理
        ChatModel chatModel = LlmUtil.getChatModel();

        ReActAgent agent = ReActAgent.of(chatModel)
                .addTool(new MethodToolProvider(new ErrorTools()))
                .chatOptions(o -> o.temperature(0.0F))
                .build();

        FlowContext context = FlowContext.of("test_tool_error");
        String result = agent.call(context,
                "调用会出错的工具");

        Assertions.assertNotNull(result);

        ReActTrace trace = context.getAs("__" + agent.name());
        if (trace != null) {
            // 使用 getMessages() 而不是 getFormattedHistory()
            List<ChatMessage> messages = trace.getMessages();
            String history = messages.stream()
                    .map(msg -> msg.toString())
                    .collect(Collectors.joining("\n"));

            System.out.println("工具异常后的历史:\n" + history);

            // 应该包含错误信息
            String historyStr = history.toLowerCase();
            Assertions.assertTrue(historyStr.contains("error") ||
                            historyStr.contains("异常") ||
                            historyStr.contains("失败"),
                    "应该记录工具错误: " + history);
        }
    }

    @Test
    public void testEmptyPrompt() throws Throwable {
        // 测试：空提示词
        ChatModel chatModel = LlmUtil.getChatModel();

        ReActAgent agent = ReActAgent.of(chatModel)
                .addTool(new MethodToolProvider(new BasicTools()))
                .build();

        FlowContext context = FlowContext.of("test_empty");

        // 先设置一个正常提示
        agent.call(context, "初始提示");

        // 再传空
        String result = agent.call(context, "");

        Assertions.assertNotNull(result);
        System.out.println("空提示词结果: " + result);
    }

    @Test
    public void testNullContext() throws Throwable {
        // 测试：null 上下文处理
        ChatModel chatModel = LlmUtil.getChatModel();

        ReActAgent agent = ReActAgent.of(chatModel)
                .addTool(new MethodToolProvider(new BasicTools()))
                .build();

        // 应该能处理 null 或新创建的上下文
        Assertions.assertThrows(NullPointerException.class, () -> agent.call(null, "测试"));
    }

    // 辅助方法：格式化 ReActTrace 的消息
    private String formatReActHistory(ReActTrace trace) {
        if (trace == null) return "";

        List<ChatMessage> messages = trace.getMessages();
        return messages.stream()
                .map(msg -> {
                    // 根据消息类型格式化
                    if (msg instanceof org.noear.solon.ai.chat.message.UserMessage) {
                        return "User: " + msg.getContent();
                    } else if (msg instanceof org.noear.solon.ai.chat.message.AssistantMessage) {
                        return "Assistant: " + msg.getContent();
                    } else if (msg instanceof org.noear.solon.ai.chat.message.ToolMessage) {
                        return "Tool: " + msg.getContent();
                    } else {
                        return "System: " + msg.getContent();
                    }
                })
                .collect(Collectors.joining("\n"));
    }

    public static class LoopTools {
        @ToolMapping(description = "返回一个需要进一步思考的响应")
        public String think_more() {
            return "这个问题还需要进一步分析，请继续思考";
        }
    }

    public static class BasicTools {
        @ToolMapping(description = "基础工具")
        public String basic_tool() {
            return "基础工具调用成功";
        }
    }

    public static class ErrorTools {
        @ToolMapping(description = "会抛出异常的工具")
        public String failing_tool() {
            throw new RuntimeException("模拟工具内部异常");
        }
    }
}