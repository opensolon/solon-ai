package demo.ai.react;

import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActConfig;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.tool.FunctionTool;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * ReActAgent 演示类
 */
public class ReActAgentDemo {

    public static void main(String[] args) throws Throwable {
        // 创建一个模拟的 ChatModel（实际使用时需要配置真实的 LLM）
        ChatModel chatModel = ChatModel.of("http://127.0.0.1:11434/api/chat")
                .provider("ollama")
                .model("qwen3:4b")
                .build();

        // 创建一些演示工具
        List<FunctionTool> tools = Arrays.asList(
                new CalculatorTool(),
                new SearchTool(),
                new TimeTool(),
                new RandomNumberTool()
        );

        // 方式1: 使用构造函数创建 ReActAgent
        System.out.println("=== 方式1: 使用构造函数创建 ReActAgent ===");
        ReActAgent agent1 = new ReActAgent(chatModel, tools, 10, null, true, 0.7F, 2048, 0.9F);
        
        // 示例1: 简单计算任务
        System.out.println("--- 示例1: 简单计算任务 ---");
        String prompt1 = "What is 15 plus 25?";
        String result1 = agent1.run(prompt1);
        System.out.println("Prompt: " + prompt1);
        System.out.println("Result: " + result1);
        System.out.println();

        // 方式2: 使用配置类创建 ReActAgent
        System.out.println("=== 方式2: 使用配置类创建 ReActAgent ===");
        ReActAgent agent2 = new ReActConfig(chatModel)
                .tools(tools)
                .maxIterations(10)
                .enableLogging(true)
                .temperature(0.7F)
                .maxResponseTokens(2048)
                .topP(0.9F)
                .finishMarker("[DONE]")
                .build();

        // 示例2: 复杂任务 - 计算后搜索
        System.out.println("--- 示例2: 复杂任务 - 计算后搜索 ---");
        String prompt2 = "What is 15 plus 25? Then search for information about the result.";
        String result2 = agent2.run(prompt2);
        System.out.println("Prompt: " + prompt2);
        System.out.println("Result: " + result2);
        System.out.println();

        // 示例3: 时间相关任务
        System.out.println("=== 示例3: 时间相关任务 ===");
        String prompt3 = "What time is it now? Then generate a random number.";
        String result3 = agent2.run(prompt3);
        System.out.println("Prompt: " + prompt3);
        System.out.println("Result: " + result3);
        System.out.println();

        // 示例4: 多步骤任务
        System.out.println("=== 示例4: 多步骤任务 ===");
        String prompt4 = "What is the current year? Add 10 to it. Then multiply the result by 2.";
        String result4 = agent2.run(prompt4);
        System.out.println("Prompt: " + prompt4);
        System.out.println("Result: " + result4);
    }

    /**
     * 计算器工具
     */
    static class CalculatorTool implements FunctionTool {
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
            Number a = (Number) arguments.get("a");
            Number b = (Number) arguments.get("b");

            if (a == null || b == null) {
                return "Error: Missing required parameters 'a' and 'b'";
            }

            switch (operation) {
                case "add":
                    return String.valueOf(a.doubleValue() + b.doubleValue());
                case "subtract":
                    return String.valueOf(a.doubleValue() - b.doubleValue());
                case "multiply":
                    return String.valueOf(a.doubleValue() * b.doubleValue());
                case "divide":
                    if (b.doubleValue() != 0) {
                        return String.valueOf(a.doubleValue() / b.doubleValue());
                    } else {
                        return "Error: Division by zero";
                    }
                default:
                    return "Error: Unknown operation '" + operation + "'";
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
    }

    /**
     * 搜索工具
     */
    static class SearchTool implements FunctionTool {
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
            if (query == null) {
                return "Error: Missing required parameter 'query'";
            }
            
            // 模拟搜索结果 - 实际应用中这里会连接真实的搜索引擎
            return "Search result for: " + query + " - This is a mock search result. Found 5 results related to your query.";
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
    }

    /**
     * 时间工具
     */
    static class TimeTool implements FunctionTool {
        @Override
        public String name() {
            return "time";
        }

        @Override
        public String description() {
            return "Get the current time and date";
        }

        @Override
        public String handle(Map<String, Object> arguments) {
            return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }

        @Override
        public String inputSchema() {
            return "{\n" +
                    "  \"type\": \"object\",\n" +
                    "  \"properties\": {}\n" +
                    "}";
        }
    }

    /**
     * 随机数工具
     */
    static class RandomNumberTool implements FunctionTool {
        private static final Random random = new Random();

        @Override
        public String name() {
            return "random_number";
        }

        @Override
        public String description() {
            return "Generate a random number between min and max values";
        }

        @Override
        public String handle(Map<String, Object> arguments) {
            Number min = (Number) arguments.get("min");
            Number max = (Number) arguments.get("max");

            if (min == null) min = 1;
            if (max == null) max = 100;

            int minValue = min.intValue();
            int maxValue = max.intValue();

            if (minValue > maxValue) {
                return "Error: min value cannot be greater than max value";
            }

            int result = random.nextInt(maxValue - minValue + 1) + minValue;
            return String.valueOf(result);
        }

        @Override
        public String inputSchema() {
            return "{\n" +
                    "  \"type\": \"object\",\n" +
                    "  \"properties\": {\n" +
                    "    \"min\": { \"type\": \"number\", \"default\": 1 },\n" +
                    "    \"max\": { \"type\": \"number\", \"default\": 100 }\n" +
                    "  }\n" +
                    "}";
        }
    }
}