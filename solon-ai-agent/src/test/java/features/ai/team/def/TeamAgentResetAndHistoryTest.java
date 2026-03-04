package features.ai.team.def;

import demo.ai.llm.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamResponse;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.annotation.Param;

/**
 * TeamAgent 协作与重置测试
 * 场景：用户 -> 领队 -> (天气专家 & 美食专家)
 */
public class TeamAgentResetAndHistoryTest {

    @Test
    public void testTeamCollaborationFlow() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        AgentSession session = InMemoryAgentSession.of("team_session_001");

        // 1. 定义子 Agent A: 天气专家 (ReAct)
        // 合并 role 并采用新的 instruction 风格
        ReActAgent weatherAgent = ReActAgent.of(chatModel)
                .name("weather_expert")
                .role("天气专家")
                .instruction("负责查询各地的实时天气信息，提供准确的天气数据")
                .defaultToolAdd(new WeatherTools())
                .maxSteps(4)
                .build();

        // 2. 定义子 Agent B: 美食专家 (ReAct)
        // 合并 role 并采用新的 instruction 风格
        ReActAgent foodAgent = ReActAgent.of(chatModel)
                .name("food_expert")
                .role("美食专家")
                .instruction("负责根据天气和地点推荐当地特色美食")
                .defaultToolAdd(new FoodTools())
                .maxSteps(4)
                .build();


        int totalTurns = 0;
        int maxTurns = 4;

        // 3. 定义 TeamAgent: 领队
        TeamAgent teamAgent = TeamAgent.of(chatModel)
                .name("trip_leader")
                .role("你是旅行领队，负责协调天气专家和美食专家为用户提供旅行建议")
                .feedbackMode(true)
                .maxTurns(maxTurns)
                .agentAdd(weatherAgent)
                .agentAdd(foodAgent)
                .build();


        // --- 第一轮：用户介绍 ---
        System.out.println(">>> 轮次 1：建立身份记忆");
        // 改为 prompt().session().call() 风格
        TeamResponse r1 = teamAgent.prompt("你好，我是 Noear，一名喜欢吃辣的开发者。").session(session).call();

        totalTurns += r1.getTrace().getTurnCount();
        System.out.println("Leader: " + r1.getContent());

        // --- 第二轮：复杂协作任务（涉及子 Agent 间的重置） ---
        System.out.println("\n>>> 轮次 2：跨 Agent 协作（上海天气+美食）");
        // 改为 prompt().session().call() 风格
        TeamResponse r2 = teamAgent.prompt("我现在在上海，帮我查查天气并推荐点吃的，记得考虑我的口味。")
                .session(session)
                .call();

        totalTurns += r2.getTrace().getTurnCount();
        System.out.println("Leader: " + r2.getContent());

        // 验证业务逻辑
        Assertions.assertTrue(r2.getContent().contains("小雨") || r2.getContent().contains("18°C"), "天气信息缺失");
        Assertions.assertTrue(r2.getContent().contains("辣") || r2.getContent().contains("姜"), "口味偏好未传递");

        // --- 第三轮：验证子 Agent 的步数是否独立重置 ---
        System.out.println("\n>>> 轮次 3：再次协作（北京场景）");
        // 改为 prompt().session().call() 风格
        TeamResponse r3 = teamAgent.prompt("我现在去北京了，再帮我查查那边的天气和美食。")
                .session(session)
                .call();

        totalTurns += r3.getTrace().getTurnCount();
        System.out.println("Leader: " + r3.getContent());

        // 核心验证：如果子 Agent 的步数没重置，在 TeamAgent 多次分发任务后，子 Agent 的步数会累计超限
        Assertions.assertTrue(r3.getContent().contains("北京"), "城市切换失败");
        Assertions.assertTrue(r3.getContent().contains("烤鸭") || r3.getContent().contains("鸡丁"), "北京美食推荐缺失");

        // --- 第四轮：全局记忆考研 ---
        System.out.println("\n>>> 轮次 4：全局记忆考研");
        // 改为 prompt().session().call() 风格
        TeamResponse r4 = teamAgent.prompt("你还记得我是谁吗？我之前在上海的时候天气怎么样？").session(session).call();

        totalTurns += r4.getTrace().getTurnCount();
        System.out.println("Leader: " + r4.getContent());

        Assertions.assertTrue(r4.getContent().contains("Noear"), "团队领队忘记了用户名字");
        Assertions.assertTrue(r4.getContent().contains("上海") && r4.getContent().contains("雨"), "团队领队忘记了之前的协作历史");

        System.out.println("\n>>> 合计轮数: " + totalTurns);
        Assertions.assertTrue(totalTurns > maxTurns, "重置没有生效");

        System.out.println("\n>>> [TeamAgent 测试通过] 子 Agent 重置正常，全局 Session 记忆同步正常。");
    }

    // --- 工具类定义 ---

    public static class WeatherTools {
        @ToolMapping(description = "查询实时天气")
        public String query_weather(@Param(description = "城市名称") String city) {
            System.out.println("[WeatherTool] 正在查询: " + city);
            if (city.contains("上海")) return "18°C，小雨";
            if (city.contains("北京")) return "25°C，晴天";
            return "天气晴朗";
        }
    }

    public static class FoodTools {
        @ToolMapping(description = "推荐特色美食")
        public String recommend_food(@Param(description = "城市") String city,
                                     @Param(description = "天气") String weather,
                                     @Param(description = "用户口味偏好") String taste) {
            System.out.println("[FoodTool] 正在为 " + city + " 推荐食物，口味: " + taste);
            if (city.contains("上海")) return "由于天冷有" + weather + "，推荐热辣的姜汤面。";
            if (city.contains("北京")) return "由于是" + weather + "，推荐正宗北京烤鸭。";
            return "当地小吃。";
        }
    }
}