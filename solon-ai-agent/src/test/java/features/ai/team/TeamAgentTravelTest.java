package features.ai.team;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
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

/**
 * 自动化管家决策测试
 * 验证：Supervisor 能否根据 Tool 反馈的“异常天气”精准指派 Planner 调整行程。
 */
public class TeamAgentTravelTest {

    @Test
    public void testTravelTeam() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        String teamId = "auto_travel_agent";

        // 1. 定义具备能力的 Agent
        Agent searcher = ReActAgent.builder(chatModel).name("searcher")
                .description("负责查询实时天气，必须调用工具获取真实数据")
                .addTool(new MethodToolProvider(new WeatherService())).build();

        Agent planner = ReActAgent.builder(chatModel).name("planner")
                .description("负责根据环境数据制定行程。如果是雨天，请务必推荐室内场馆。").build();

        // 2. 组建全自动团队
        TeamAgent travelTeam = TeamAgent.builder(chatModel)
                .name(teamId).addAgent(searcher).addAgent(planner).build();

        FlowContext context = FlowContext.of("sn_travel_888");
        String result = travelTeam.ask(context, "我想去东京玩一天，请帮我规划");

        // 3. 打印协作足迹
        System.out.println("--- 最终方案 ---\n" + result);
        TeamTrace trace = context.getAs("__" + teamId);
        if (trace != null) {
            System.out.println("\n[协作流水线]");
            trace.getSteps().forEach(s -> System.out.printf(" - %s (%dms)\n", s.getAgentName(), s.getDuration()));
        }

        // 4. 单测检测
        Assertions.assertNotNull(trace);
        // 检测逻辑：由于模拟工具返回大雨，Planner 必须产出室内方案
        boolean logicOk = result.contains("室内") || result.contains("博物馆") || result.contains("商场");
        Assertions.assertTrue(logicOk, "Supervisor 或 Planner 未能根据大雨天气调整为室内行程");
    }

    /**
     * 模拟天气服务工具
     */
    public static class WeatherService {
        @ToolMapping(description = "获取指定城市的实时天气")
        public String query(@Param(description = "城市") String city) {
            return city + "当前气象：特大暴雨，不建议户外活动。";
        }
    }
}