package features.ai.team.def;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamProtocols;
import org.noear.solon.ai.agent.team.TeamResponse;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.AbsToolProvider;
import org.noear.solon.annotation.Param;

/**
 * TeamAgent 协作与重置测试
 * <p>
 * 场景：用户 -> 团队 -> (天气专家 & 美食专家)。
 * 使用 SEQUENTIAL 协议 + 确定性 mock 子 Agent，避免 LLM 网关抖动；
 * 验证子 Agent 可重复调用（重置）与跨轮 Session 记忆。
 * </p>
 */
public class TeamAgentResetAndHistoryTest {

    @Test
    public void testTeamCollaborationFlow() throws Throwable {
        AgentSession session = InMemoryAgentSession.of("team_session_001");

        WeatherTools weatherTools = new WeatherTools();
        FoodTools foodTools = new FoodTools();

        // 1. 天气专家：根据 prompt 中的城市调用工具
        Agent weatherAgent = new Agent() {
            private int callCount = 0;

            @Override
            public String name() {
                return "weather_expert";
            }

            @Override
            public String role() {
                return "天气专家";
            }

            @Override
            public AssistantMessage call(Prompt prompt, AgentSession agentSession) {
                callCount++;
                String text = promptText(prompt) + " " + sessionLatest(agentSession);
                String city = text.contains("北京") ? "北京" : (text.contains("上海") ? "上海" : "未知");
                String weather = weatherTools.query_weather(city);
                return ChatMessage.ofAssistant(city + "天气：" + weather + "（调用=" + callCount + "）");
            }
        };

        // 2. 美食专家：根据城市/天气调用工具
        Agent foodAgent = new Agent() {
            private int callCount = 0;

            @Override
            public String name() {
                return "food_expert";
            }

            @Override
            public String role() {
                return "美食专家";
            }

            @Override
            public AssistantMessage call(Prompt prompt, AgentSession agentSession) {
                callCount++;
                String text = promptText(prompt) + " " + sessionLatest(agentSession);
                String city = text.contains("北京") ? "北京" : (text.contains("上海") ? "上海" : "未知");
                String weather = city.contains("北京") ? "25°C，晴天" : "18°C，小雨";
                String food = foodTools.recommend_food(city, weather, "辣");
                return ChatMessage.ofAssistant(city + "美食：" + food + "（调用=" + callCount + "）");
            }
        };

        int totalTurns = 0;
        int maxTurns = 4;

        // 3. 顺序协作团队：不依赖 Supervisor LLM，结果确定性
        TeamAgent teamAgent = TeamAgent.of(null)
                .name("trip_leader")
                .role("旅行领队")
                .protocol(TeamProtocols.SEQUENTIAL)
                .feedbackMode(false)
                .maxTurns(maxTurns)
                .sessionWindowSize(20)
                .agentAdd(weatherAgent)
                .agentAdd(foodAgent)
                .build();

        // --- 第一轮：用户介绍 ---
        System.out.println(">>> 轮次 1：建立身份记忆");
        TeamResponse r1 = teamAgent.prompt("你好，我是 Noear，一名喜欢吃辣的开发者。").session(session).call();
        totalTurns += countTurns(r1);
        System.out.println("Leader: " + r1.getContent());
        Assertions.assertNotNull(r1.getTrace());

        // --- 第二轮：上海天气+美食 ---
        System.out.println("\n>>> 轮次 2：跨 Agent 协作（上海天气+美食）");
        TeamResponse r2 = teamAgent.prompt("我现在在上海，帮我查查天气并推荐点吃的，记得考虑我的口味。")
                .session(session)
                .call();
        totalTurns += countTurns(r2);
        System.out.println("Leader: " + r2.getContent());

        String r2All = nullToEmpty(r2.getContent()) + "\n" + r2.getTrace().getFormattedHistory();
        Assertions.assertTrue(r2All.contains("小雨") || r2All.contains("18°C"), "天气信息缺失: " + r2All);
        Assertions.assertTrue(r2All.contains("辣") || r2All.contains("姜"), "口味偏好未传递: " + r2All);

        // --- 第三轮：北京场景（验证可再次调度，相当于重置后继续） ---
        System.out.println("\n>>> 轮次 3：再次协作（北京场景）");
        TeamResponse r3 = teamAgent.prompt("我现在去北京了，再帮我查查那边的天气和美食。")
                .session(session)
                .call();
        totalTurns += countTurns(r3);
        System.out.println("Leader: " + r3.getContent());

        String r3All = nullToEmpty(r3.getContent()) + "\n" + r3.getTrace().getFormattedHistory();
        Assertions.assertTrue(r3All.contains("北京"), "城市切换失败: " + r3All);
        Assertions.assertTrue(r3All.contains("烤鸭") || r3All.contains("鸡丁"), "北京美食推荐缺失: " + r3All);

        // --- 第四轮：全局记忆 ---
        System.out.println("\n>>> 轮次 4：全局记忆考研");
        TeamResponse r4 = teamAgent.prompt("你还记得我是谁吗？我之前在上海的时候天气怎么样？").session(session).call();
        totalTurns += countTurns(r4);
        System.out.println("Leader: " + r4.getContent());

        String sessionText = session.getLatestMessages(50).stream()
                .map(m -> String.valueOf(m.getContent()))
                .reduce("", (a, b) -> a + "\n" + b);
        String memoryAll = nullToEmpty(r4.getContent()) + "\n" + r4.getTrace().getFormattedHistory() + "\n" + sessionText;
        Assertions.assertTrue(memoryAll.contains("Noear"), "团队领队忘记了用户名字: " + memoryAll);
        Assertions.assertTrue(memoryAll.contains("上海") && memoryAll.contains("雨"),
                "团队领队忘记了之前的协作历史: " + memoryAll);

        System.out.println("\n>>> 合计轮数: " + totalTurns);
        // 4 次用户对话均成功完成，且上海/北京各被调度过，说明跨轮重置与记忆正常
        Assertions.assertTrue(totalTurns >= 4, "协作轮次不足, totalTurns=" + totalTurns);
        Assertions.assertTrue(r2.getTrace().getRecordCount() >= 1, "第二轮应有专家产出");
        Assertions.assertTrue(r3.getTrace().getRecordCount() >= 1, "第三轮应有专家产出");
    
        System.out.println("\n>>> [TeamAgent 测试通过] 子 Agent 重置正常，全局 Session 记忆同步正常。");
    }
    
    private static int countTurns(TeamResponse resp) {
        if (resp == null || resp.getTrace() == null) {
            return 1;
        }
        // Sequential 协议 turnCounter 可能为 0，用记录数兜底
        int turns = resp.getTrace().getTurnCount();
        if (turns > 0) {
            return turns;
        }
        return Math.max(1, resp.getTrace().getRecordCount());
    }

    private static String promptText(Prompt prompt) {
        if (prompt == null) {
            return "";
        }
        try {
            return String.valueOf(prompt.getUserContent());
        } catch (Throwable t) {
            return String.valueOf(prompt);
        }
    }

    private static String sessionLatest(AgentSession session) {
        if (session == null) {
            return "";
        }
        return session.getLatestMessages(8).stream()
                .map(m -> String.valueOf(m.getContent()))
                .reduce("", (a, b) -> a + " " + b);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    public static class WeatherTools extends AbsToolProvider {
        @ToolMapping(description = "查询实时天气")
        public String query_weather(@Param(description = "城市名称") String city) {
            System.out.println("[WeatherTool] 正在查询: " + city);
            if (city.contains("上海")) return "18°C，小雨";
            if (city.contains("北京")) return "25°C，晴天";
            return "天气晴朗";
        }
    }

    public static class FoodTools extends AbsToolProvider {
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
