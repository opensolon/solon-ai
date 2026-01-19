package features.ai.team.def;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.annotation.Param;

/**
 * 自动化管家决策测试
 * <p>验证目标：Supervisor（管理节点）能否协调 Searcher 获取天气，并由 Planner 根据天气反馈动态调整策略。</p>
 */
public class TeamAgentTravelTest {

    @Test
    public void testTravelTeam() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        String teamId = "auto_travel_agent";

        // 1. 组建团队：定义具有条件反射能力的子智能体
        TeamAgent travelTeam = TeamAgent.of(chatModel)
                .name(teamId)
                .agentAdd(ReActAgent.of(chatModel)
                        .name("searcher")
                        .description("天气查询专家")
                        .systemPrompt(p->p
                                .role("天气查询专家")
                                .instruction("必须基于 query 工具的 Observation 给出明确结论。"))
                        .toolAdd(new MethodToolProvider(new WeatherService()))
                        .build())
                .agentAdd(ReActAgent.of(chatModel)
                        .name("planner")
                        .description("行程规划专家")
                        .systemPrompt(p->p // 使用 builder 明确注入业务规则
                                .role("行程规划专家")
                                .instruction("核心禁令：必须优先阅读历史天气！如果下雨，禁止安排：浅草寺（户外）、晴空塔（观景）、隅田川。必须改为：博物馆、美术馆或商场。"))
                        .build())
                .maxTurns(8) // 增加迭代上限，确保团队有足够空间完成“搜索-规划-校验”循环
                .build();

        // 2. 使用 AgentSession 替代 FlowContext
        // Session 会自动管理底层的 FlowContext 快照和多 Agent 间的消息传递
        AgentSession session = InMemoryAgentSession.of("sn_travel_2026_001");

        // 3. 执行协作任务
        // 增加背景：引导 Agent 关注环境因素
        String userQuery = "我现在在东京，请帮我规划一天的行程。";
        String result = travelTeam.call(Prompt.of(userQuery), session).getContent();

        System.out.println("--- 团队协作方案 ---\n" + result);

        // 4. 协作轨迹与决策检测
        // 从 team 中提取当前 session 的轨迹信息
        TeamTrace trace = travelTeam.getTrace(session);
        Assertions.assertNotNull(trace, "未生成协作轨迹");

        System.out.println("--- 协作步骤摘要 ---");
        trace.getRecords().forEach(s -> System.out.println("[" + s.getSource() + "]: " + s.getContent()));

        // 核心逻辑断言：检测 Planner 是否针对 WeatherService 返回的“特大暴雨”做出了规避动作
        boolean isLogicCorrect = result.contains("室内") ||
                result.contains("博物馆") ||
                result.contains("商场") ||
                result.contains("美术馆");

        if(!isLogicCorrect) {
            System.err.println("决策失败：Planner 忽略了 Searcher 的暴雨警告，未能正确调整为室内行程！");
        }

        Assertions.assertTrue(isLogicCorrect, "Planner 未能基于历史天气数据动态调整行程方案");
    }

    /**
     * 模拟天气服务：返回极端天气以测试 Agent 的反应。
     */
    public static class WeatherService {
        @ToolMapping(description = "获取指定城市的实时天气预报")
        public String query(@Param(description = "城市名称，例如：东京") String city) {
            // 返回恶劣天气，强制 Planner 必须改变其预设的户外方案
            return "【气象警报】" + city + "目前遭遇特大暴雨，风力 8 级，所有户外景点（如公园、塔顶观景台）暂时关闭。";
        }
    }
}