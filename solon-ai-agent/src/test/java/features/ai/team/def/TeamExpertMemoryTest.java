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
 * 验证专家 Agent 是否能共享并理解 Session 里的长期记忆
 */
public class TeamExpertMemoryTest {

    @Test
    public void testExpertMemorySharing() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        // 关键：使用同一个 Session
        AgentSession session = InMemoryAgentSession.of("memory_share_001");

        // 1. 定义美食专家：采用新的 role(x).instruction(y) 风格，合并重复的 role
        ReActAgent foodAgent = ReActAgent.of(chatModel)
                .name("food_expert")
                .role("美食专家")
                .instruction("作为美食推荐专家，直接调用工具根据用户需求推荐美食。")
                .defaultToolAdd(new SecureFoodTools())
                .build();

        // 2. 定义领队
        TeamAgent teamAgent = TeamAgent.of(chatModel)
                .name("manager")
                .agentAdd(foodAgent)
                .build();

        // --- 第一步：埋入长期记忆 ---
        System.out.println(">>> 轮次 1：埋入偏好（海鲜过敏）");
        // 改为 prompt(prompt).session(session).call() 风格
        teamAgent.prompt("你好，我叫 Noear。我对海鲜严重过敏，请务必记住这点。")
                .session(session)
                .call();

        // --- 第二步：专家调用（不重复提及过敏） ---
        System.out.println("\n>>> 轮次 2：验证专家是否记得过敏");
        // 这里故意不提过敏，看专家是否会推荐包含海鲜的东西
        TeamResponse resp = teamAgent.prompt("我现在在青岛，请专家推荐几个当地特色菜。")
                .session(session)
                .call();

        String content = resp.getContent();
        System.out.println("Manager: " + content);

        // 核心验证：
        // 1. 专家必须被指派
        Assertions.assertTrue(resp.getTrace().getRecords().stream().anyMatch(r -> r.getSource().equals("food_expert")), "美食专家未参与");
        // 2. 最终结果不应包含海鲜（因为记忆里有过敏）
        Assertions.assertFalse(content.contains("虾") || content.contains("螃蟹") || content.contains("蛤蜊"),
                "专家忘记了用户的海鲜过敏记忆！");
        // 3. 专家应该提到了安全替代品
        Assertions.assertTrue(content.contains("排骨") || content.contains("烧肉") || content.contains("过敏"),
                "专家应考虑到过敏因素推荐肉类");

        System.out.println("\n>>> [测试通过] 专家成功读取了 Session 中的用户偏好历史。");
    }

    public static class SecureFoodTools {
        @ToolMapping(description = "获取城市特色美食列表")
        public String get_city_foods(@Param(description = "城市") String city) {
            System.out.println("[Tool] 获取 " + city + " 的原始菜单...");
            if (city.contains("青岛")) {
                return "1. 辣炒蛤蜊(海鲜), 2. 油爆大虾(海鲜), 3. 青岛锅贴(猪肉), 4. 酱猪蹄";
            }
            return "普通快餐";
        }

        @ToolMapping(description = "安全检查并下单")
        public String order_food(@Param(description = "菜名") String name, @Param(description = "备注") String note) {
            return "已为您下单: " + name + " (备注: " + note + ")";
        }
    }
}