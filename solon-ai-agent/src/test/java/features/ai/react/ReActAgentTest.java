package features.ai.react;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.tool.FunctionTool;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ReActAgent 测试类
 */
public class ReActAgentTest {

    @Test
    public void testReActAgent() throws Throwable {
        // 创建一个模拟的 ChatModel（实际使用时需要配置真实的 LLM）
        ChatModel chatModel = ChatModel.of("http://127.0.0.1:11434/api/chat")
                .provider("ollama")
                .model("qwen2.5:1.5b")
                .build();

        // 创建一些测试工具
        FunctionTool calculatorTool = new FunctionTool() {
            @Override
            public String name() {
                return "calculator";
            }

            @Override
            public String description() {
                return "A simple calculator for basic arithmetic operations";
            }

            @Override
            public String handle(Map<String, Object> arguments) {
                String operation = (String) arguments.get("operation");
                Double a = (Double) arguments.get("a");
                Double b = (Double) arguments.get("b");

                switch (operation) {
                    case "add":
                        return String.valueOf(a + b);
                    case "subtract":
                        return String.valueOf(a - b);
                    case "multiply":
                        return String.valueOf(a * b);
                    case "divide":
                        if (b != 0) {
                            return String.valueOf(a / b);
                        } else {
                            return "Error: Division by zero";
                        }
                    default:
                        return "Error: Unknown operation";
                }
            }
            
            @Override
            public String inputSchema() {
                return "{\n" +
                       "  \"type\": \"object\",\n" +
                       "  \"properties\": {\n" +
                       "    \"operation\": { \"type\": \"string\", \"enum\": [\"add\", \"subtract\", \"multiply\", \"divide\"] },\n" +
                       "    \"a\": { \"type\": \"number\" },\n" +
                       "    \"b\": { \"type\": \"number\" }\n" +
                       "  },\n" +
                       "  \"required\": [\"operation\", \"a\", \"b\"]\n" +
                       "}";
            }
        };

        FunctionTool searchTool = new FunctionTool() {
            @Override
            public String name() {
                return "search";
            }

            @Override
            public String description() {
                return "A search tool for finding information";
            }

            @Override
            public String handle(Map<String, Object> arguments) {
                String query = (String) arguments.get("query");
                // 模拟搜索结果
                return "Search result for: " + query + " - This is a mock search result.";
            }
            
            @Override
            public String inputSchema() {
                return "{\n" +
                       "  \"type\": \"object\",\n" +
                       "  \"properties\": {\n" +
                       "    \"query\": { \"type\": \"string\" }\n" +
                       "  },\n" +
                       "  \"required\": [\"query\"]\n" +
                       "}";
            }
        };

        List<FunctionTool> tools = Arrays.asList(calculatorTool, searchTool);

        // 创建 ReActAgent
        ReActAgent agent = new ReActAgent(chatModel, tools, 5);

        // 运行代理
        String prompt = "What is 15 plus 25? Then search for information about the result.";
        String result = agent.run(prompt);

        System.out.println("Final result: " + result);
    }

    @Test
    public void testReActAgentWithoutTools() throws Throwable {
        // 创建一个模拟的 ChatModel
        ChatModel chatModel = ChatModel.of("http://127.0.0.1:11434/api/chat")
                .provider("ollama")
                .model("qwen2.5:1.5b")
                .build();

        // 创建不带工具的 ReActAgent
        ReActAgent agent = new ReActAgent(chatModel);

        // 运行代理
        String prompt = "Explain the concept of ReAct pattern in AI agents.";
        String result = agent.run(prompt);

        System.out.println("Result without tools: " + result);
    }
}