package features.ai.team;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.flow.FlowContext;

/**
 * 【业务场景测试】：多轮对话与上下文历史注入
 * 验证：TeamAgent 在多轮交互中，能否通过 context 保持“协作记忆”，并在用户追加要求后做出逻辑连贯的响应。
 */
public class TeamAgentMultiTurnTest {

    @Test
    public void testMultiTurnCollaboration() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        String teamId = "concierge_team";

        // 1. 定义团队：搜索专家 + 行程规划师
        TeamAgent conciergeTeam = TeamAgent.builder(chatModel)
                .name(teamId)
                .addAgent(ReActAgent.builder(chatModel).name("searcher").description("负责收集目的地信息").build())
                .addAgent(ReActAgent.builder(chatModel).name("planner").description("负责定制具体行程建议").build())
                .build();

        // --- 第一轮：模糊需求 ---
        FlowContext context = FlowContext.of("session_multi_turn_001");
        System.out.println(">>> [第一轮] 用户：我想去杭州玩。");
        String out1 = conciergeTeam.ask(context, "我想去杭州玩。");
        System.out.println("<<< [助手]：" + out1);

        // 检测：第一轮结束后，轨迹中应该已经存在执行记录
        TeamTrace trace1 = context.getAs("__" + teamId);
        Assertions.assertTrue(trace1.getStepCount() >= 1, "第一轮应至少触发了搜索或规划");

        // --- 第二轮：追加约束（历史注入发生在这里） ---
        // 此时 context 中已保留了第一轮的 KEY_HISTORY 和 TeamTrace
        System.out.println("\n>>> [第二轮] 用户：我的预算只有 500 元，请重新规划。");
        String out2 = conciergeTeam.ask(context, "我的预算只有 500 元，请重新规划。");
        System.out.println("<<< [助手]：" + out2);

        // 2. 单测检测逻辑
        TeamTrace finalTrace = context.getAs("__" + teamId);

        // 检测：总步数增长。第二轮应该是基于第一轮的 history 继续增加 steps
        Assertions.assertTrue(finalTrace.getStepCount() > trace1.getStepCount(), "第二轮执行应产生新的协作记录");

        // 检测：语义连贯性。输出应同时包含“杭州”和“500”这两个跨轮次的关键要素
        boolean contextPreserved = out2.contains("杭州") && (out2.contains("500") || out2.contains("预算"));
        Assertions.assertTrue(contextPreserved, "Agent 丢失了多轮对话的上下文记忆");

        // 检测：迭代次数累加
        Integer iters = context.getAs(Agent.KEY_ITERATIONS);
        Assertions.assertTrue(iters > 1, "迭代次数应随轮次和决策流转正常累加");

        System.out.println("\n>>> [系统消息]：多轮对话记忆验证通过。");
    }
}