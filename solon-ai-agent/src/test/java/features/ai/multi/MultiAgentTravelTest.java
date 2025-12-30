package features.ai.multi;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.multi.AgentRouterTask;
import org.noear.solon.ai.agent.multi.MultiAgent;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.annotation.Param;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Graph;

public class MultiAgentTravelTest {

    @Test
    public void testTravelTeam() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 定义子 Agent
        // searcher: 拥有天气查询工具，负责获取客观事实
        Agent searcher = ReActAgent.builder(chatModel)
                .nameAs("searcher")
                .systemPromptProvider(config -> "你是一个旅游信息搜索员。请根据用户目的地查询天气，并如实告知。")
                .addTool(new MethodToolProvider(new WeatherTool()))
                .build();

        // planner: 负责根据事实编写具体的行程建议
        Agent planner = ReActAgent.builder(chatModel)
                .nameAs("planner")
                .systemPromptProvider(config -> "你是一个资深旅行规划师。请根据搜索员提供的信息，为用户定制行程。如果下雨，请多安排室内景点。")
                .build();

        // 2. 定义图结构 (逻辑严密)
        Graph travelGraph = Graph.create("travel_planning_team", spec -> {
            spec.addStart("start").linkAdd("router");

            // 路由决策：由 Supervisor 决定下一步
            spec.addExclusive("router")
                    .task(new AgentRouterTask(chatModel, "searcher", "planner"))
                    .linkAdd("searcher", l -> l.when(ctx -> "searcher".equals(ctx.get("next_agent"))))
                    .linkAdd("planner", l -> l.when(ctx -> "planner".equals(ctx.get("next_agent"))))
                    .linkAdd("end");

            // 具体的 Agent 节点 (利用 Agent.run 自动同步 history)
            spec.addActivity("searcher").task(searcher).linkAdd("router");
            spec.addActivity("planner").task(planner).linkAdd("router");

            spec.addEnd("end");
        });

        // 3. 运行 MultiAgent
        MultiAgent travelAgency = new MultiAgent(travelGraph);

        // 模拟一个下雨天的场景
        String result = travelAgency.ask(FlowContext.of("travel_1"), "我想去东京玩一天，请帮我规划");

        System.out.println("--- 最终旅行建议 ---");
        System.out.println(result);
    }

    public static class WeatherTool {
        @ToolMapping(description = "查询指定城市的实时天气情况")
        public String getWeather(@Param(description = "城市名称，如：北京, 东京") String city) {
            // 模拟外部 API 调用
            if (city.contains("东京")) {
                return "东京天气：大雨，气温 15°C，不建议室外活动。";
            } else {
                return city + "天气：晴朗，气温 22°C，适合出游。";
            }
        }
    }
}