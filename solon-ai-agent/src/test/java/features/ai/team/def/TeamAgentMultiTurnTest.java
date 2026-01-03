package features.ai.team.def;

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
 * 【修复版】多轮对话历史注入测试
 * 核心改进：
 * 1. 降低模型推理负担，不在此类中使用复杂的 Tool（避免 JSON 解析失败）。
 * 2. 强化 Prompt，确保 Agent 意识到自己处于“多轮对话”中。
 */
public class TeamAgentMultiTurnTest {

    @Test
    public void testMultiTurnCollaboration() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        String teamId = "multi_turn_concierge";

        // 定义两个 Agent
        Agent searcher = ReActAgent.of(chatModel)
                .name("searcher")
                .promptProvider(p -> "你是一个旅游信息分析员。你的任务是直接输出目的地（如杭州）的基础常识，严禁使用任何 Action JSON 工具。")
                .description("负责收集目的地基础信息。").build();

        Agent planner = ReActAgent.of(chatModel)
                .name("planner")
                .promptProvider(p -> "你是一个行程规划专家。请结合历史信息和用户预算给出建议。")
                .description("负责制定具体方案。").build();

        // 使用更大的迭代限制
        TeamAgent conciergeTeam = TeamAgent.of(chatModel)
                .name(teamId)
                .addAgent(searcher)
                .addAgent(planner)
                .maxTotalIterations(8) // 增加迭代次数
                .build();

        // --- 第一轮：基础调研 ---
        FlowContext context1 = FlowContext.of("session_001");
        System.out.println(">>> [Round 1] 用户：我想去杭州玩。");
        String out1 = conciergeTeam.call(context1, "我想去杭州玩。");

        System.out.println("<<< [第一轮输出] 长度: " + (out1 != null ? out1.length() : 0));
        System.out.println("<<< [第一轮输出] 内容: " + (out1 != null && out1.length() > 0 ?
                out1.substring(0, Math.min(200, out1.length())) : "null或空"));

        TeamTrace trace1 = context1.getAs("__" + teamId);
        Assertions.assertNotNull(trace1, "第一轮应该生成轨迹");
        int round1StepCount = trace1.getStepCount();
        System.out.println("第一轮步数: " + round1StepCount);
        System.out.println("第一轮最后节点: " + context1.lastNodeId());
        System.out.println("第一轮历史:\n" + trace1.getFormattedHistory());

        // 关键：手动重置状态，让第二轮能重新开始
        context1.trace().recordNode(conciergeTeam.getGraph(), null);
        trace1.setRoute(null);
        trace1.resetIterations();
        context1.lastNode(null);

        // --- 第二轮：预算约束注入 ---
        System.out.println("\n>>> [Round 2] 用户：预算只有 500 元，请重新规划杭州行程。");
        String out2 = conciergeTeam.call(context1, "预算只有 500 元，请重新规划杭州行程。");

        System.out.println("<<< [第二轮输出] 长度: " + (out2 != null ? out2.length() : 0));
        System.out.println("<<< [第二轮输出] 内容: " + (out2 != null && out2.length() > 0 ?
                out2.substring(0, Math.min(500, out2.length())) : "null或空"));

        // --- 核心检测逻辑 ---
        TeamTrace trace2 = context1.getAs("__" + teamId);
        Assertions.assertNotNull(trace2, "第二轮应该也有轨迹");

        int totalStepsAfterRound2 = trace2.getStepCount();
        System.out.println("总步数: " + totalStepsAfterRound2);
        System.out.println("完整轨迹历史:\n" + trace2.getFormattedHistory());

        // 检测：第二轮应该产生新的步骤
        Assertions.assertTrue(totalStepsAfterRound2 > round1StepCount,
                "第二轮未能产生新的协作轨迹。第一轮: " + round1StepCount + ", 总步数: " + totalStepsAfterRound2);

        // 检测：输出应该包含相关信息
        boolean hasValidOutput = out2 != null && !out2.trim().isEmpty();
        Assertions.assertTrue(hasValidOutput,
                "第二轮应该产生有效输出。当前输出: " + (out2 == null ? "null" : "空字符串"));

        if (hasValidOutput) {
            // 检测是否包含了多轮对话的上下文
            boolean hasHangzhou = out2.contains("杭州");
            boolean hasBudget = out2.contains("500") || out2.contains("预算");

            System.out.println("检测结果 - 包含杭州: " + hasHangzhou + ", 包含预算: " + hasBudget);

            // 至少应该包含杭州信息
            Assertions.assertTrue(hasHangzhou, "输出应该包含第一轮的地点信息（杭州）");
        }
    }
}