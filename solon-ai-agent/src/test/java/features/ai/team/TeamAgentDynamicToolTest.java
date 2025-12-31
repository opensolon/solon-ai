package features.ai.team;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.flow.FlowContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 【业务场景测试】：基于权限的动态工具加载
 * 场景：不同等级的用户，其 AI 助手具备的“超能力”（工具）不同。
 */
public class TeamAgentDynamicToolTest {

    @Test
    public void testDynamicToolsForVIP() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 模拟业务环境：判断当前用户是否为 VIP
        boolean isVip = true;

        // 2. 根据权限构建差异化的工具包
        List<FunctionTool> tools = new ArrayList<>();
        tools.addAll(new MethodToolProvider(new BasicTravelTool()).getTools()); // 基础工具
        if (isVip) {
            tools.addAll(new MethodToolProvider(new VipPrivilegeTool()).getTools()); // 动态注入 VIP 专供工具
        }

        // 3. 构建 Agent
        Agent searcher = ReActAgent.builder(chatModel)
                .name("searcher")
                .description("差旅搜索专家。请根据你的工具权限为用户提供信息。")
                .addTool(tools) // 动态注入工具列表
                .build();

        TeamAgent vipTeam = TeamAgent.builder(chatModel).addAgent(searcher).build();

        // 4. 执行测试
        FlowContext context = FlowContext.of("user_vip_001");
        String result = vipTeam.ask(context, "我是尊贵的 VIP，请查一下我在上海机场能用哪个私密休息室？");

        // 5. 单测检测
        System.out.println(">>> [AI 回复]：\n" + result);

        if (isVip) {
            Assertions.assertTrue(result.contains("黑金"), "VIP 用户应能查询到专属休息室信息");
        } else {
            Assertions.assertTrue(result.contains("抱歉") || result.contains("没有权限"), "普通用户不应获取敏感信息");
        }
    }

    // 基础业务工具
    public static class BasicTravelTool {
        @ToolMapping(description = "查询机场公共信息")
        public String getPublicInfo() { return "虹桥机场 T2 航站楼正常运行。"; }
    }

    // VIP 敏感数据工具
    public static class VipPrivilegeTool {
        @ToolMapping(description = "查询 VIP 专属私密休息室信息")
        public String getVipLounge() { return "上海虹桥：V1 尊享黑金休息室，提供定制餐饮。"; }
    }
}