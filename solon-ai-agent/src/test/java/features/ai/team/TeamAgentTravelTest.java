package features.ai.team;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.annotation.Param;
import org.noear.solon.flow.FlowContext;

public class TeamAgentTravelTest {

    @Test
    public void testTravelTeam() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        String teamName = "travel_agency";

        // 1. 定义子 Agent
        Agent searcher = ReActAgent.builder(chatModel)
                .name("searcher")
                .description("旅游信息搜索员")
                .promptProvider(config -> "你是一个旅游信息搜索员。请查询目的地天气。")
                .addTool(new MethodToolProvider(new WeatherTool()))
                .build();

        Agent planner = ReActAgent.builder(chatModel)
                .name("planner")
                .description("旅游规划师")
                .promptProvider(config -> "你是一个资深旅行规划师。根据天气信息定制行程，下雨请安排室内。")
                .build();


        // 3. 构建团队智能体
        TeamAgent travelAgency = TeamAgent.builder(chatModel)
                .name(teamName)
                .addAgent(searcher)
                .addAgent(planner)
                .build();

        FlowContext context = FlowContext.of("travel_101");

        String result = travelAgency.ask(context, "我想去东京玩一天，请帮我规划");

        System.out.println("--- 最终旅行建议 ---");
        System.out.println(result);

        // 4. 打印协作轨迹 (演示 TeamTrace 的用途)
        TeamTrace trace = context.getAs("__" + teamName);
        if (trace != null) {
            System.out.println("\n--- 团队协作足迹 ---");
            trace.getSteps().forEach(step ->
                    System.out.println("[" + step.getAgentName() + "] 耗时:" + step.getDuration() + "ms"));
        }
    }

    public static class WeatherTool {
        @ToolMapping(description = "查询指定城市的实时天气情况")
        public String getWeather(@Param(description = "城市名称") String city) {
            return city.contains("东京") ? "大雨" : "晴天";
        }
    }
}