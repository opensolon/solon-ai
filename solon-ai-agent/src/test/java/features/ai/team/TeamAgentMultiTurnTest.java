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

        // 定义两个 Agent，重点在于 description 的互补性
        Agent searcher = ReActAgent.builder(chatModel).name("searcher")
                .description("负责收集目的地基础信息（如天气、景点名称）。").build();

        Agent planner = ReActAgent.builder(chatModel).name("planner")
                .description("负责制定具体方案。必须基于 searcher 提供的历史信息和用户的最新预算要求进行规划。")
                .build();

        TeamAgent conciergeTeam = TeamAgent.builder(chatModel)
                .name(teamId)
                .addAgent(searcher)
                .addAgent(planner)
                .maxTotalIterations(3) // 严格限制单轮迭代，防止死循环
                .build();

        // --- 第一轮：基础调研 ---
        FlowContext context = FlowContext.of("session_001");
        System.out.println(">>> [Round 1] 用户：我想去杭州玩。");
        String out1 = conciergeTeam.call(context, "我想去杭州玩。");
        System.out.println("<<< [助手]：" + out1);

        TeamTrace trace1 = context.getAs("__" + teamId);
        Assertions.assertNotNull(trace1);
        int round1StepCount = trace1.getStepCount();

        // --- 第二轮：预算约束注入 ---
        // 注意：这里必须沿用同一个 context 对象，它承载了 KEY_HISTORY 和上一次的 TeamTrace
        System.out.println("\n>>> [Round 2] 用户：预算只有 500 元，请重新规划。");
        String out2 = conciergeTeam.call(context, "预算只有 500 元，请重新规划。");
        System.out.println("<<< [助手]：" + out2);

        // --- 核心检测逻辑 ---
        TeamTrace trace2 = context.getAs("__" + teamId);

        // 1. 检测步骤连贯性：第二轮的步骤数应该在第一轮的基础上增加
        System.out.println("第一轮步数: " + round1StepCount + ", 总步数: " + trace2.getStepCount());
        Assertions.assertTrue(trace2.getStepCount() > round1StepCount, "第二轮未能产生新的协作轨迹");

        // 2. 检测历史注入：最终回复必须包含第一轮的地点（杭州）和第二轮的约束（500/预算）
        boolean hasMemory = out2.contains("杭州") && (out2.contains("500") || out2.contains("预算"));

        if (!hasMemory) {
            System.err.println("错误：Agent 丢失了第一轮的地点信息或忽略了第二轮的预算约束！");
            System.err.println("当前上下文历史记录内容: " + context.get(Agent.KEY_HISTORY));
        }

        Assertions.assertTrue(hasMemory, "多轮对话上下文注入失败，Agent 出现了失忆");
    }
}