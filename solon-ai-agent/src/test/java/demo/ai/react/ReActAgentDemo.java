package demo.ai.react;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActRecord;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.annotation.Param;
import org.noear.solon.flow.FlowContext;

public class ReActAgentDemo {
    public static void main(String[] args) throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        ReActAgent agent = ReActAgent.builder(chatModel)
                .enableLogging(true)
                .temperature(0.1F) // 低温度保证推理逻辑严密
                .addTool(new MethodToolProvider(new WeatherTools()))
                .build();

        System.out.println("--- Agent 开始工作 ---");
        // 这个问题会触发：1.调用工具获取天气 2.根据天气推理穿衣建议
        FlowContext context = FlowContext.of("demo1");
        String response = agent.ask(context, "北京天气怎么样？我该穿什么衣服？");

        System.out.println("--- 最终答复 ---");
        System.out.println(response);

        System.out.println("--- 持久化 ---");


        //可持久化（序列化验证）
        String json = context.toJson();

        System.out.println(json);
        ReActRecord state1 = context.getAs("__" + agent.name());

        FlowContext context2 = FlowContext.fromJson(json);
        ReActRecord state2 = context2.getAs("__" + agent.name());

        Assertions.assertEquals(
                state1.getHistory().size(),
                state2.getHistory().size(), "序列化失败");

        Assertions.assertEquals(
                context.lastNodeId(),
                context2.lastNodeId(), "序列化失败");

        Assertions.assertEquals(
                state1.getIteration().get(),
                state2.getIteration().get(), "序列化失败");

        String json2 = context2.toJson();
        Assertions.assertEquals(json, json2);
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