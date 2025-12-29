package demo.ai.react;

import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActConfig;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.annotation.Param;

public class ReActAgentDemo {
    public static void main(String[] args) throws Throwable {
        // 1. 构建基础模型
        ChatModel chatModel = ChatModel.of("http://127.0.0.1:11434/api/chat")
                .provider("ollama")
                .model("qwen3:4b")
                .build();

        // 2. 构建 Agent 配置与工具
        ReActConfig config = new ReActConfig(chatModel)
                .enableLogging(true)
                .temperature(0.1F);

        // 模拟天气工具
        config.addTool(new MethodToolProvider(new Tools()));

        ReActAgent agent = config.create();

        // 3. 运行
        System.out.println("--- Agent 开始工作 ---");
        String response = agent.run("北京天气怎么样？我该穿什么衣服？");
        System.out.println("--- 最终答复 ---");
        System.out.println(response);
    }

    public static class Tools {
        @ToolMapping(name = "get_weather", title = "查询天气", description = "查询指定城市的天气")
        public String get_weather(@Param(description = "城市名称", defaultValue = "未知") String city) {
            if (city.contains("北京")) {
                return "晴朗，25度";
            } else {
                return "小雨，18度";
            }
        }
    }
}