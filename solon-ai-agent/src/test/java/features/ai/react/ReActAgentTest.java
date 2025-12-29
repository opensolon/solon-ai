package features.ai.react;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActConfig;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.annotation.Param;

public class ReActAgentTest {

    @Test
    public void testMathAndLogic() throws Throwable {
        ChatModel chatModel = ChatModel.of("http://127.0.0.1:11434/api/chat")
                .provider("ollama")
                .model("llama3.2")
                .build();

        ReActConfig config = new ReActConfig(chatModel)
                .addTool(new MethodToolProvider(new MathTools()))
                .temperature(0.0F)
                .enableLogging(true);

        ReActAgent agent = config.create();

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