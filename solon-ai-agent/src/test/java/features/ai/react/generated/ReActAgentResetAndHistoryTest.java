package features.ai.react.generated;

import demo.ai.llm.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActResponse;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.annotation.Param;

/**
 * ReActAgent 真实应用场景测试：生活助理
 * 验证：多轮对话下的记忆保持 + 步数重置
 */
public class ReActAgentResetAndHistoryTest {

    @Test
    public void testLifeAssistantFlow() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 构建 Agent：限制 maxSteps 越小越好
        int totalSteps = 0;
        int maxSteps = 5;
        ReActAgent agent = ReActAgent.of(chatModel)
                .defaultToolAdd(new LifeTools())
                .modelOptions(o -> o.temperature(0.0))
                .maxSteps(maxSteps)
                .build();

        AgentSession session = InMemoryAgentSession.of("user_session_007");

        // --- 第一轮：建立背景记忆 ---
        System.out.println(">>> 轮次 1：建立记忆");
        ReActResponse r1 = agent.prompt("你好，我是来自上海的开发者 Noear。").session(session).call();
        totalSteps += r1.getTrace().getStepCount();
        System.out.println("AI: " + r1.getContent());
        // 此轮通常是 1 步 (Reason -> End)

        // --- 第二轮：根据记忆调用工具（天气） ---
        System.out.println("\n>>> 轮次 2：工具调用（根据记忆查上海天气）");
        ReActResponse r2 = agent.prompt("帮我查查我所在地现在的天气。").session(session).call();
        totalSteps += r2.getTrace().getStepCount();
        System.out.println("AI: " + r2.getContent());
        System.out.println("本轮消耗步数: " + r2.getTrace().getStepCount());

        // 修复后的断言：匹配上海工具返回的真实数据
        Assertions.assertTrue(r2.getContent().contains("18°C") || r2.getContent().contains("小雨"), "天气结果应包含18°C或小雨");
        Assertions.assertTrue(r2.getTrace().getStepCount() < maxSteps, "Step 应该被重置，不应超过限制");

        // --- 第三轮：改变上下文（去北京） ---
        System.out.println("\n>>> 轮次 3：变更场景（北京美食）");
        ReActResponse r3 = agent.prompt("我现在去北京出差了，根据那边的天气帮我推荐美食。").session(session).call();
        totalSteps += r3.getTrace().getStepCount();
        System.out.println("AI: " + r3.getContent());

        System.out.println("本轮消耗步数: " + r3.getTrace().getStepCount());
        // 如果没有 reset，此时 s3 会累加之前的步骤导致 > 6
        Assertions.assertTrue(r3.getTrace().getStepCount() < maxSteps, "Reset 机制失效，步数已累加！");
        Assertions.assertTrue(r3.getContent().contains("烤鸭") || r3.getContent().contains("炸酱面"), "美食推荐结果错误");

        // --- 第四轮：深度记忆考研 ---
        System.out.println("\n>>> 轮次 4：跨轮次记忆检索");
        ReActResponse r4 = agent.prompt("你还记得我是谁吗？我老家是哪里的？我刚才让你在北京推荐了什么？").session(session).call();
        totalSteps += r4.getTrace().getStepCount();
        System.out.println("AI: " + r4.getContent());

        // 验证 Agent 是否能同时定位 R1(上海) 和 R3(北京/美食)
        Assertions.assertTrue(r4.getContent().contains("Noear"), "忘记了名字");
        Assertions.assertTrue(r4.getContent().contains("上海"), "忘记了老家");
        Assertions.assertTrue(r4.getContent().contains("美食") || r4.getContent().contains("烤鸭"), "忘记了之前的任务");

        System.out.println("\n>>> 合计步数: " + totalSteps);
        Assertions.assertTrue(totalSteps > maxSteps, "重置没有生效");
        System.out.println("\n>>> [测试通过] 完美验证：重置确保生命力，记忆确保连续性。");
    }

    public static class LifeTools {
        @ToolMapping(description = "根据城市名查询实时天气")
        public String get_weather(@Param(description = "城市名称") String city) {
            if (city.contains("上海")) {
                return "{\"city\":\"上海\", \"temp\":\"18°C\", \"desc\":\"小雨\"}";
            } else if (city.contains("北京")) {
                return "{\"city\":\"北京\", \"temp\":\"25°C\", \"desc\":\"晴朗\"}";
            }
            return "{\"city\":\"未知\"}";
        }

        @ToolMapping(description = "根据城市名和天气推荐美食")
        public String get_food_recommend(@Param(description = "城市名称") String city,
                                         @Param(description = "天气情况") String weather) {
            if (city.contains("北京")) {
                return "北京现在" + weather + "，推荐您吃北京烤鸭。";
            }
            return "当地特色小吃。";
        }
    }
}