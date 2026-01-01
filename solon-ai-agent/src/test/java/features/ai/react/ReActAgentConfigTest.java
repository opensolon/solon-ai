package features.ai.react;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.annotation.Param;
import org.noear.solon.flow.FlowContext;

/**
 * ReActAgent 配置参数测试
 */
public class ReActAgentConfigTest {

    @Test
    public void testDifferentTemperatures() throws Throwable {
        // 测试：不同温度参数的影响
        ChatModel chatModel = LlmUtil.getChatModel();

        // 低温（确定性高）
        ReActAgent lowTempAgent = ReActAgent.builder(chatModel)
                .addTool(new MethodToolProvider(new CreativeTools()))
                .temperature(0.1F)
                .name("low_temp")
                .build();

        // 高温（创造性高）
        ReActAgent highTempAgent = ReActAgent.builder(chatModel)
                .addTool(new MethodToolProvider(new CreativeTools()))
                .temperature(0.9F)
                .name("high_temp")
                .build();

        String prompt = "给这个产品想一个宣传口号";

        FlowContext context1 = FlowContext.of("test_temp_1");
        String result1 = lowTempAgent.call(context1, prompt);

        FlowContext context2 = FlowContext.of("test_temp_2");
        String result2 = highTempAgent.call(context2, prompt);

        System.out.println("低温结果: " + result1);
        System.out.println("高温结果: " + result2);

        Assertions.assertNotEquals(result1, result2,
                "不同温度应该产生不同结果");
    }

    @Test
    public void testDifferentMaxTokens() throws Throwable {
        // 测试：不同 maxTokens 参数
        ChatModel chatModel = LlmUtil.getChatModel();

        ReActAgent shortAgent = ReActAgent.builder(chatModel)
                .addTool(new MethodToolProvider(new StoryTools()))
                .maxTokens(50) // 很短的token限制
                .name("short")
                .build();

        ReActAgent longAgent = ReActAgent.builder(chatModel)
                .addTool(new MethodToolProvider(new StoryTools()))
                .maxTokens(500) // 较长的token限制
                .name("long")
                .build();

        String prompt = "写一个简短的故事";

        FlowContext context1 = FlowContext.of("test_tokens_1");
        String result1 = shortAgent.call(context1, prompt);

        FlowContext context2 = FlowContext.of("test_tokens_2");
        String result2 = longAgent.call(context2, prompt);

        System.out.println("短token结果长度: " + result1.length());
        System.out.println("长token结果长度: " + result2.length());

        // 长token的结果应该更长（但不是绝对的，取决于模型行为）
        Assertions.assertTrue(result2.length() >= result1.length(),
                "长token限制应该允许更长的输出");
    }

    @Test
    public void testLoggingEnabled() throws Throwable {
        // 测试：日志开启/关闭
        ChatModel chatModel = LlmUtil.getChatModel();

        ReActAgent loggingAgent = ReActAgent.builder(chatModel)
                .addTool(new MethodToolProvider(new BasicTools()))
                .enableLogging(true)
                .name("with_logging")
                .build();

        ReActAgent noLoggingAgent = ReActAgent.builder(chatModel)
                .addTool(new MethodToolProvider(new BasicTools()))
                .enableLogging(false)
                .name("no_logging")
                .build();

        // 主要验证不抛出异常
        FlowContext context1 = FlowContext.of("test_logging_1");
        String result1 = loggingAgent.call(context1, "测试日志");

        FlowContext context2 = FlowContext.of("test_logging_2");
        String result2 = noLoggingAgent.call(context2, "测试无日志");

        Assertions.assertNotNull(result1);
        Assertions.assertNotNull(result2);
    }

    @Test
    public void testFinishMarker() throws Throwable {
        // 测试：自定义结束标记
        ChatModel chatModel = LlmUtil.getChatModel();

        ReActAgent agent = ReActAgent.builder(chatModel)
                .addTool(new MethodToolProvider(new BasicTools()))
                .finishMarker("[结束]") // 自定义结束标记
                .name("custom_finish")
                .build();

        FlowContext context = FlowContext.of("test_finish");
        String result = agent.call(context, "简单问候");

        System.out.println("自定义结束标记结果: " + result);
        Assertions.assertNotNull(result);

        // 如果使用了结束标记，应该包含它
        if (result.contains("[结束]")) {
            System.out.println("检测到自定义结束标记");
        }
    }

    public static class CreativeTools {
        @ToolMapping(description = "生成创意内容")
        public String generate_idea() {
            return "这是一个创意想法";
        }
    }

    public static class StoryTools {
        @ToolMapping(description = "生成故事")
        public String generate_story() {
            return "从前有座山，山里有座庙...";
        }
    }

    public static class BasicTools {
        @ToolMapping(description = "基础工具")
        public String basic_tool() {
            return "基础工具";
        }
    }
}