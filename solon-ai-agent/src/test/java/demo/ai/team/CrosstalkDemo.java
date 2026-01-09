package demo.ai.team;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamProtocols;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;

/**
 * 相声团队快速测试
 * <p>
 * 场景：使用 SWARM 协议演示“逗哏”与“捧哏”之间的去中心化对话接力。
 * 验证：AgentSession 在多步协作中自动记录和传递团队轨迹（TeamTrace）。
 * </p>
 */
public class CrosstalkDemo {
    @Test
    public void testQuickCrosstalk() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 构建相声团队
        TeamAgent crosstalkTeam = TeamAgent.of(chatModel)
                .name("crosstalk")
                .protocol(TeamProtocols.SWARM) // 使用去中心化的 Swarm 接力模式
                .addAgent(ReActAgent.of(chatModel)
                        .name("aaa")
                        .description("阿飞")
                        .systemPrompt(c -> "你是一个智能体名字叫“阿飞”，演逗哏。将跟另一个叫“阿紫”的智能体，表演相声。每句话不要超过50个字")
                        .build())
                .addAgent(ReActAgent.of(chatModel)
                        .name("bbb")
                        .description("阿紫")
                        .systemPrompt(c -> "你是一个智能体名字叫“阿紫”，演捧哏。将跟另一个叫“阿飞”的智能体，表演相声。每句话不要超过50个字")
                        .build())
                .maxTotalIterations(5)  // 限制对话轮数
                .build();

        // 2. 使用 AgentSession 替换 FlowContext
        AgentSession session = InMemoryAgentSession.of("crosstalk_session_001");

        // 3. 发起调用，开始表演
        String input = "让阿飞和阿紫吵个架";
        String result = crosstalkTeam.call(Prompt.of(input), session).getContent();

        System.out.println("=== 快速测试（SWARM 模式） ===");

        // 4. 获取并打印完整协作轨迹
        // 在 3.8.x 中，推荐通过 team.getTrace(session) 统一获取
        TeamTrace trace = crosstalkTeam.getTrace(session);

        if (trace != null) {
            System.out.println("\n=== 完整表演记录 (总步数: " + trace.getStepCount() + ") ===");
            trace.getSteps().forEach(step -> {
                System.out.printf("\n[%s]：\n", step.getAgentName());
                System.out.println(step.getContent());
                System.out.println("----------------------------");
            });
        }

        System.out.println("\n=== 最终结果 ===");
        System.out.println(result);
    }
}