package demo.ai.react;

import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.annotation.Param;

public class ReActAgentDemo {
    public static void main(String[] args) throws Throwable {
        ChatModel chatModel = ChatModel.of("https://ai.gitee.com/v1/chat/completions")
                .apiKey("PE6JVMP7UQI81GY6AZ0J8WEWWLFHWHROG15XUP18")
                .model("Qwen3-32B")
                .build();

        ReActAgent agent = ReActAgent.builder(chatModel)
                .enableLogging(true)
                .temperature(0.1F) // 低温度保证推理逻辑严密
                .addTool(new MethodToolProvider(new WeatherTools()))
                .build();

        System.out.println("--- Agent 开始工作 ---");
        // 这个问题会触发：1.调用工具获取天气 2.根据天气推理穿衣建议
        String response = agent.run("北京天气怎么样？我该穿什么衣服？");

        System.out.println("--- 最终答复 ---");
        System.out.println(response);
    }

    public static class WeatherTools {
        @ToolMapping(name = "get_weather", description = "查询指定城市的天气状况")
        public String get_weather(@Param(description = "城市名称") String city) {
            if (city.contains("北京")) {
                return "北京现在晴朗，气温 10 摄氏度，风力 4 级。";
            } else {
                return city + "小雨，18 度。";
            }
        }
    }
}