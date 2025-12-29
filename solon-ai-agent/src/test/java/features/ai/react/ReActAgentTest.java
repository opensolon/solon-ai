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
    public void testAdd() throws Throwable {
        // 1. 初始化模型 (Ollama)
        ChatModel chatModel = ChatModel.of("http://127.0.0.1:11434/api/chat")
                .provider("ollama")
                .model("qwen3:4b") // 建议使用 7b 以上模型，4b 对复杂 ReAct 遵循度有限
                .build();

        // 2. 定义一个简单的加法工具

        // 3. 配置 Agent
        ReActConfig config = new ReActConfig(chatModel)
                .addTool(new MethodToolProvider(new Tools()))
                .temperature(0.0F) // 测试场景建议 0，保证结果稳定
                .enableLogging(true);

        ReActAgent agent = new ReActAgent(config);

        // 4. 执行
        String result = agent.run("请问 123 加上 456 等于多少？");

        // 5. 断言
        Assertions.assertNotNull(result);
        Assertions.assertTrue(result.contains("579"), "结果应包含正确的数学计算值");
    }

    public static class Tools {
        @ToolMapping(description = "计算两个数字的和")
        public double adder(@Param double a, @Param double b) {
            return a + b;
        }
    }
}