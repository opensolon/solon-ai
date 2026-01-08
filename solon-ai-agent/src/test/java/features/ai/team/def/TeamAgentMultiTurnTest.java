package features.ai.team.def;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;

/**
 * TeamAgent 多轮对话与历史上下文注入测试
 * <p>场景：用户与团队进行多轮交互。第一轮确定地点，第二轮注入预算。
 * 验证 TeamAgent 是否能通过 AgentSession 保持记忆，并在后续回复中结合前文信息。</p>
 */
public class TeamAgentMultiTurnTest {

    @Test
    public void testMultiTurnCollaboration() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        String teamId = "multi_turn_concierge";

        // 1. 定义具备明确分工的 Agent
        Agent searcher = ReActAgent.of(chatModel)
                .name("searcher")
                .promptProvider(p -> "你是一个旅游信息分析员。你的任务是提供目的地（如杭州）的基础常识。请直接输出文本，不要调用工具。")
                .description("负责收集目的地基础常识。")
                .build();

        Agent planner = ReActAgent.of(chatModel)
                .name("planner")
                .promptProvider(p -> "你是一个行程规划专家。请务必结合历史信息（如目的地）和用户新提出的预算约束给出建议。")
                .description("负责制定具体行程方案。")
                .build();

        // 2. 构建团队
        TeamAgent conciergeTeam = TeamAgent.of(chatModel)
                .name(teamId)
                .addAgent(searcher)
                .addAgent(planner)
                .maxTotalIterations(8)
                .build();

        // 3. 创建统一的 AgentSession（这是保持多轮记忆的关键）
        // AgentSession 会在内部维护历史消息队列和 FlowContext 快照
        AgentSession session = InMemoryAgentSession.of("session_travel_001");

        // --- 第一轮：基础调研 ---
        System.out.println(">>> [Round 1] 用户：我想去杭州玩。");
        String out1 = conciergeTeam.call(Prompt.of("我想去杭州玩。"), session).getContent();

        System.out.println("<<< [第一轮回复]: " + (out1 != null ? out1.substring(0, Math.min(100, out1.length())) : "null") + "...");

        // 获取第一轮轨迹，用于断言
        TeamTrace trace1 = conciergeTeam.getTrace(session);
        Assertions.assertNotNull(trace1, "第一轮执行应产生轨迹");
        int round1StepCount = trace1.getStepCount();
        System.out.println("第一轮协作步数: " + round1StepCount);

        // --- 第二轮：预算约束注入 ---
        // 注意：在 3.8.x 中，只需使用同一个 session 再次 call，
        // 框架会自动将前一轮的推理摘要或消息历史注入到新的 Prompt 中。
        System.out.println("\n>>> [Round 2] 用户：预算只有 500 元，请重新规划杭州行程。");
        String out2 = conciergeTeam.call(Prompt.of("预算只有 500 元，请重新规划杭州行程。"), session).getContent();

        System.out.println("<<< [第二轮回复]: " + out2);

        // --- 核心检测逻辑 ---
        TeamTrace trace2 = conciergeTeam.getTrace(session);
        Assertions.assertNotNull(trace2, "第二轮执行应更新轨迹");

        int totalSteps = trace2.getStepCount();
        System.out.println("协作总步数: " + totalSteps);
        System.out.println("完整协作历史摘要:\n" + trace2.getFormattedHistory());

        // 验证 1：轨迹是在增长的
        Assertions.assertTrue(totalSteps > round1StepCount, "第二轮应在原有协作基础上增加新的推理步骤");

        // 验证 2：有效性检测
        Assertions.assertNotNull(out2, "第二轮输出不应为空");

        // 验证 3：记忆检测
        // 第二轮用户只说了“500元”，如果没有第一轮的记忆，AI 将不知道去哪里
        boolean hasLocationMemory = out2.contains("杭州");
        boolean hasBudgetAwareness = out2.contains("500") || out2.contains("预算");

        System.out.println("记忆检测 - 目的地(杭州): " + hasLocationMemory + ", 约束(预算): " + hasBudgetAwareness);

        Assertions.assertTrue(hasLocationMemory, "AI 应该记得第一轮提到的目的地：杭州");
        Assertions.assertTrue(hasBudgetAwareness, "AI 应该响应第二轮提出的预算约束：500元");
    }
}