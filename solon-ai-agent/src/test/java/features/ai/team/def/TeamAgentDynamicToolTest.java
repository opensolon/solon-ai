package features.ai.team.def;

import demo.ai.llm.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.MethodToolProvider;

/**
 * 【业务场景测试】：基于权限的动态工具加载
 * <p>场景描述：根据用户身份（如 VIP 或 普通用户）动态注入不同的工具包。
 * 验证智能体在拥有不同“超能力”的情况下，对敏感信息的处理能力。</p>
 */
public class TeamAgentDynamicToolTest {

    /**
     * 测试 VIP 用户场景下的动态工具调用
     * <p>验证目标：当 isVip 为 true 时，智能体应能感知并调用权限范围内的专供工具。</p>
     */
    @Test
    public void testDynamicToolsForVIP() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 模拟业务环境：确定当前用户的权限等级
        boolean isVip = true;

        // 2. 构建子 Agent (searcher)
        // 使用 then 钩子函数，根据当前业务状态动态配置工具
        Agent searcher = ReActAgent.of(chatModel)
                .name("searcher")
                .description("差旅搜索专家。请根据你的工具权限为用户提供信息。")
                .then(slf -> {
                    // 注入基础工具（所有用户可用）
                    slf.defaultToolAdd(new MethodToolProvider(new BasicTravelTool()));

                    // 权限校验：如果是 VIP，动态注入敏感数据工具
                    if (isVip) {
                        slf.defaultToolAdd(new MethodToolProvider(new VipPrivilegeTool()));
                    }
                })
                .build();

        // 3. 构建团队智能体 (Team)
        TeamAgent vipTeam = TeamAgent.of(chatModel)
                .agentAdd(searcher)
                .build();

        // 4. 使用 AgentSession 替代 FlowContext
        // InMemoryAgentSession 会自动初始化底层的 FlowContext 快照
        AgentSession session = InMemoryAgentSession.of("user_vip_001");

        // 5. 定义任务指令
        String question = "我是尊贵的 VIP，请查一下我在上海机场能用哪个私密休息室？";

        // 6. 执行测试调用
        // 遵循 3.8.x 推荐的 call(Prompt, AgentSession) 契约
        String result = vipTeam.call(Prompt.of(question), session).getContent();

        // 输出回复结果用于调试
        System.out.println(">>> [AI 回复]：\n" + result);

        // 7. 业务逻辑断言
        if (isVip) {
            // VIP 权限下，结果应包含来自 VipPrivilegeTool 的特有信息
            Assertions.assertTrue(result.contains("黑金"), "VIP 用户应能查询到专属休息室信息");
        } else {
            // 非 VIP 权限下，智能体不应能通过工具获取到敏感数据
            Assertions.assertTrue(result.contains("抱歉") || result.contains("没有权限") || !result.contains("黑金"),
                    "普通用户不应获取敏感信息");
        }
    }

    // --- 业务工具包定义 ---

    /**
     * 基础业务工具：提供公共数据
     */
    public static class BasicTravelTool {
        @ToolMapping(description = "查询机场公共信息")
        public String getPublicInfo() {
            return "虹桥机场 T2 航站楼正常运行。";
        }
    }

    /**
     * VIP 权限专用工具：涉及敏感或增值数据
     */
    public static class VipPrivilegeTool {
        @ToolMapping(description = "查询 VIP 专属私密休息室信息")
        public String getVipLounge() {
            return "上海虹桥：V1 尊享黑金休息室，提供定制餐饮。";
        }
    }
}