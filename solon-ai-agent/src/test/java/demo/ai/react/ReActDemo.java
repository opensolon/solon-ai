package demo.ai.react;

import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.tool.FunctionTool;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * ReActAgent 演示类
 */
public class ReActDemo {

    public static void main(String[] args) throws Throwable {
        // 创建一个模拟的 ChatModel（实际使用时需要配置真实的 LLM）
        ChatModel chatModel = ChatModel.of("http://127.0.0.1:11434/api/chat")
                .provider("ollama")
                .model("qwen2.5:1.5b")
                .build();

        // 创建演示工具
        List<FunctionTool> tools = Arrays.asList(
                createCalculatorTool(),
                createSearchTool(),
                createTimeTool(),
                createWeatherTool()
        );

        // 演示基本功能
        demonstrateBasicFunctionality(chatModel, tools);

        // 演示自定义系统提示功能
        demonstrateCustomSystemPrompt(chatModel, tools);
    }

    /**
     * 演示基本功能
     */
    private static void demonstrateBasicFunctionality(ChatModel chatModel, List<FunctionTool> tools) throws Throwable {
        System.out.println("=== 基本功能演示 ===");

        // 创建 ReActAgent
        ReActAgent agent = new ReActAgent(chatModel, tools, 10);

        // 演示不同的使用场景
        demonstrateCalculator(agent);
        demonstrateMultiStep(agent);
        demonstrateTime(agent);
    }

    /**
     * 演示自定义系统提示功能
     */
    private static void demonstrateCustomSystemPrompt(ChatModel chatModel, List<FunctionTool> tools) throws Throwable {
        System.out.println("=== 自定义系统提示演示 ===");

        String customSystemPrompt = "你是一个专业的数学助手，专门帮助用户解决数学问题。" +
                "使用 ReAct 模式（思考-行动-观察）来解决问题。" +
                "格式要求：" +
                "- 思考 (Thought): <分析问题的思路>" +
                "- 行动 (Action): {\"name\": \"工具名\", \"arguments\": {\"参数\": \"值\"}}" +
                "- 观察 (Observation): <工具执行结果>" +
                "当完成计算时，以 [FINISH] 结束并给出最终答案。";

        ReActAgent mathAgent = new ReActAgent(chatModel, tools, 10, customSystemPrompt);

        String prompt = "计算 25 乘以 4，然后将结果除以 2";
        String result = mathAgent.run(prompt);
        System.out.println("Question: " + prompt);
        System.out.println("Result: " + result);
        System.out.println();
    }

    /**
     * 创建计算器工具
     */
    private static FunctionTool createCalculatorTool() {
        return new FunctionTool() {
            @Override
            public String name() {
                return "calculator";
            }

            @Override
            public String description() {
                return "A simple calculator for basic arithmetic operations (add, subtract, multiply, divide)";
            }

            @Override
            public String handle(Map<String, Object> arguments) {
                String operation = (String) arguments.get("operation");
                Double a = Double.valueOf(arguments.get("a").toString());
                Double b = Double.valueOf(arguments.get("b").toString());

                switch (operation.toLowerCase()) {
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
                        return "Error: Unknown operation " + operation;
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
    }

    /**
     * 创建搜索工具
     */
    private static FunctionTool createSearchTool() {
        return new FunctionTool() {
            @Override
            public String name() {
                return "search";
            }

            @Override
            public String title() {
                return this.name();
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
    }

    /**
     * 创建时间工具
     */
    private static FunctionTool createTimeTool() {
        return new FunctionTool() {
            @Override
            public String name() {
                return "get_current_time";
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
        };
    }

    /**
     * 创建天气工具
     */
    private static FunctionTool createWeatherTool() {
        return new FunctionTool() {
            @Override
            public String name() {
                return "get_weather";
            }

            @Override
            public String description() {
                return "Get weather information for a location";
            }

            @Override
            public String handle(Map<String, Object> arguments) {
                String location = (String) arguments.get("location");
                // 模拟天气数据
                return "Weather in " + location + ": Sunny, 22°C, Humidity 65%";
            }

            @Override
            public String inputSchema() {
                return "{\n" +
                        "  \"type\": \"object\",\n" +
                        "  \"properties\": {\n" +
                        "    \"location\": { \"type\": \"string\" }\n" +
                        "  },\n" +
                        "  \"required\": [\"location\"]\n" +
                        "}";
            }
        };
    }

    /**
     * 演示计算器功能
     */
    private static void demonstrateCalculator(ReActAgent agent) throws Throwable {
        System.out.println("=== 演示计算器功能 ===");
        String prompt = "What is 15 plus 25? Then multiply the result by 2.";
        String result = agent.run(prompt);
        System.out.println("Question: " + prompt);
        System.out.println("Result: " + result);
        System.out.println();
    }

    /**
     * 演示多步骤功能
     */
    private static void demonstrateMultiStep(ReActAgent agent) throws Throwable {
        System.out.println("=== 演示多步骤功能 ===");
        String prompt = "Calculate 100 divided by 4, then search for information about the result number.";
        String result = agent.run(prompt);
        System.out.println("Question: " + prompt);
        System.out.println("Result: " + result);
        System.out.println();
    }

    /**
     * 演示时间功能
     */
    private static void demonstrateTime(ReActAgent agent) throws Throwable {
        System.out.println("=== 演示时间功能 ===");
        String prompt = "What time is it now?";
        String result = agent.run(prompt);
        System.out.println("Question: " + prompt);
        System.out.println("Result: " + result);
        System.out.println();
    }
}