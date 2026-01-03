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
 * 一个简单的计算器场景
 * */
public class ReActAgentTest {

    @Test
    public void testMathAndLogic() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        ReActAgent agent = ReActAgent.of(chatModel)
                .addTool(new MethodToolProvider(new MathTools()))
                .chatOptions(o -> o.temperature(0.0F))
                .build();

        FlowContext context = FlowContext.of("demo1");
        String result = agent.call(context, "先计算 12 加 34 的和，再把结果乘以 2 等于多少？");

        Assertions.assertNotNull(result);
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