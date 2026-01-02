package features.ai.team;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
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

        // 1. 组建团队
        TeamAgent travelTeam = TeamAgent.builder(chatModel)
                .name(teamId)
                .addAgent(ReActAgent.builder(chatModel).name("searcher")
                        .description("负责查询实时天气。必须基于 query 工具的 Observation 给出结论。")
                        .addTool(new MethodToolProvider(new WeatherService()))
                        .build())
                .addAgent(ReActAgent.builder(chatModel).name("planner")
                        .description("行程规划专家。要求：必须优先阅读协作历史中的天气信息！如果历史显示下雨，严禁安排户外步行，必须安排室内场馆（如博物馆、室内商场）。")
                        .build())
                .maxTotalIterations(5) // 限制迭代次数防止死循环
                .build();

        FlowContext context = FlowContext.of("sn_travel_888");

        // 增加背景：强调当前时间，促使 Agent 关注实时性
        String result = travelTeam.call(context, "我现在在东京，请帮我规划一天的行程。");

        System.out.println("--- 最终方案 ---\n" + result);

        // 2. 单测检测
        TeamTrace trace = context.getAs("__" + teamId);
        Assertions.assertNotNull(trace, "未生成协作轨迹");

        // 核心逻辑检测
        // 修复点：只要 Planner 的最终输出中包含了“室内”或 searcher 提到的“博物馆”，即视为逻辑通过
        boolean hasIndoorAdvise = result.contains("室内") || result.contains("博物馆") || result.contains("商场");

        // 打印调试信息
        if(!hasIndoorAdvise) {
            System.err.println("测试失败：Planner 忽略了暴雨警告，依然安排了户外活动！");
        }

        Assertions.assertTrue(hasIndoorAdvise, "Planner 未能根据大雨天气调整为室内行程");
    }

    public static class WeatherService {
        @ToolMapping(description = "获取指定城市的实时天气")
        public String query(@Param(description = "城市") String city) {
            // 返回极其恶劣的天气，强迫 Planner 必须做出反应
            return "【警告】" + city + "当前气象：特大暴雨并伴有强风，所有户外景区已关闭，不建议任何户外步行。";
        }
    }
}