package features.ai.react;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.annotation.Param;

/**
 * 一个简单的计算器场景
 * */
public class ReActAgentTest {

    @Test
    public void testMathAndLogic() throws Throwable {
        ChatModel chatModel = ChatModel.of("https://ai.gitee.com/v1/chat/completions")
                .apiKey("PE6JVMP7UQI81GY6AZ0J8WEWWLFHWHROG15XUP18")
                .model("Qwen3-32B")
                .build();


        ReActAgent agent = ReActAgent.builder(chatModel)
                .addTool(new MethodToolProvider(new MathTools()))
                .temperature(0.0F)
                .enableLogging(true)
                .build();

        // 测试点：多步计算逻辑
        String result = agent.run("先计算 12 加 34 的和，再把结果乘以 2 等于多少？");

        Assertions.assertNotNull(result);
        // 结果应该是 (12+34)*2 = 92
        Assertions.assertTrue(result.contains("92"), "计算结果应为 92，实际返回: " + result);
    }

    public static class MathTools {
        @ToolMapping(description = "计算两个数字的和")
        public double adder(@Param double a, @Param double b) {
            return a + b;
        }

        @ToolMapping(description = "计算两个数字的乘积")
        public double multiplier(@Param double a, @Param double b) {
            return a * b;
        }
    }
}