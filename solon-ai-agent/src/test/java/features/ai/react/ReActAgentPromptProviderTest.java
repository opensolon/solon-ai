package features.ai.react;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActPromptProvider;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.annotation.Param;
import org.noear.solon.flow.FlowContext;

/**
 * 自定义提示词提供者测试
 */
public class ReActAgentPromptProviderTest {

    @Test
    public void testCustomPromptProvider() throws Throwable {
        // 测试：自定义提示词提供者
        ChatModel chatModel = LlmUtil.getChatModel();

        ReActPromptProvider customProvider = config -> {
            return "你是专门处理数学问题的专家。\n" +
                    "可用工具: " + config.getTools().size() + " 个\n" +
                    "请按照以下格式思考:\n" +
                    "分析 -> 计算 -> 验证\n" +
                    "最终答案请以 '答案:' 开头";
        };

        ReActAgent agent = ReActAgent.builder(chatModel)
                .addTool(new MethodToolProvider(new MathTools()))
                .promptProvider(customProvider)
                .temperature(0.0F)
                .enableLogging(true)
                .build();

        FlowContext context = FlowContext.of("test_custom_prompt");
        String result = agent.call(context, "计算 25 + 37");

        Assertions.assertNotNull(result);
        System.out.println("自定义提示词结果: " + result);

        // 可能包含自定义格式
        if (result.contains("答案:")) {
            System.out.println("检测到自定义格式");
        }
    }

    @Test
    public void testChinesePromptProvider() throws Throwable {
        // 测试：中文提示词提供者
        ChatModel chatModel = LlmUtil.getChatModel();

        ReActAgent agent = ReActAgent.builder(chatModel)
                .addTool(new MethodToolProvider(new ChineseTools()))
                .promptProvider(org.noear.solon.ai.agent.react.ReActPromptProviderCn.getInstance())
                .temperature(0.0F)
                .enableLogging(true)
                .build();

        FlowContext context = FlowContext.of("test_chinese_prompt");
        String result = agent.call(context, "查询北京的天气");

        Assertions.assertNotNull(result);
        System.out.println("中文提示词结果: " + result);

        // 应该能用中文正常交互
        Assertions.assertTrue(result.contains("北京") || result.contains("天气"),
                "应该返回中文结果: " + result);
    }

    @Test
    public void testEmptySystemPrompt() throws Throwable {
        // 测试：空系统提示词
        ChatModel chatModel = LlmUtil.getChatModel();

        ReActPromptProvider emptyProvider = config -> "";

        ReActAgent agent = ReActAgent.builder(chatModel)
                .addTool(new MethodToolProvider(new BasicTools()))
                .promptProvider(emptyProvider)
                .temperature(0.0F)
                .build();

        FlowContext context = FlowContext.of("test_empty_prompt");
        String result = agent.call(context, "测试");

        Assertions.assertNotNull(result);
        System.out.println("空系统提示词结果: " + result);
    }

    public static class MathTools {
        @ToolMapping(description = "加法计算")
        public double add(@Param double a, @Param double b) {
            return a + b;
        }
    }

    public static class ChineseTools {
        @ToolMapping(description = "查询城市天气")
        public String get_weather(@Param(description = "城市名称") String city) {
            return city + " 今天天气晴朗，温度适宜";
        }
    }

    public static class BasicTools {
        @ToolMapping(description = "基础工具")
        public String basic() {
            return "基础工具调用";
        }
    }
}