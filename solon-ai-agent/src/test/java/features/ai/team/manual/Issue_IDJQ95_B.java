package features.ai.team.manual;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.simple.SimpleAgent;
import org.noear.solon.ai.agent.simple.SimpleSystemPrompt;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamProtocols;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.test.SolonTest;

/**
 * 改造为 NONE 协议的工作流模式
 * 1. 彻底移除 ReAct 引擎干扰，全部使用 SimpleAgent
 * 2. 使用 graphAdjuster 手动编排 [Coder] -> [Reviewer] 链路
 */
@SolonTest
public class Issue_IDJQ95_B {

    @Test
    public void case1() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. Coder (Simple) - 纯净输出，不带 ReAct 杂质
        Agent coder = SimpleAgent.of(chatModel)
                .name("Coder")
                .systemPrompt(SimpleSystemPrompt.builder()
                        .role("前端开发者")
                        .instruction("任务：编写完整的 HTML/JS 代码。直接输出代码内容，不要包裹 ```。")
                        .build())
                .build();

        // 2. Reviewer (Simple) - 纯净审查
        Agent reviewer = SimpleAgent.of(chatModel)
                .name("Reviewer")
                .systemPrompt(SimpleSystemPrompt.builder()
                        .role("代码审查专家")
                        .instruction("审查上文中的代码。若包含 <!DOCTYPE html>，回复：【审查通过】。")
                        .build())
                .build();

        // 3. TeamAgent 使用 NONE 协议 + 手动绘图
        TeamAgent devTeam = TeamAgent.of(chatModel)
                .name("DevTeam")
                .protocol(TeamProtocols.NONE) // 禁用自动协议
                .agentAdd(coder, reviewer)
                .graphAdjuster(spec -> {
                    // 定义工作流逻辑：START -> Coder -> Reviewer -> END
                    spec.addStart(Agent.ID_START).linkAdd(coder.name());
                    spec.addActivity(coder).linkAdd(reviewer.name());
                    spec.addActivity(reviewer).linkAdd(Agent.ID_END);
                    spec.addEnd(Agent.ID_END);
                })
                .build();

        // 4. 执行
        AgentSession agentSession = InMemoryAgentSession.of();
        AssistantMessage result = devTeam.call(
                Prompt.of("写一个英文拼写 HTML 游戏。"),
                agentSession
        );

        System.out.println("======= 最终输出 =======");
        System.out.println(result.getContent());

        // 5. 轨迹解析
        TeamTrace trace = devTeam.getTrace(agentSession);
        System.out.println("\n--- NONE 协议工作流轨迹 ---");
        trace.getSteps().forEach(s -> {
            System.out.printf("[%s]: %s\n", s.getSource(),
                    s.getContent().substring(0, Math.min(50, s.getContent().length())));
        });

        // 6. 最终断言
        // 由于是顺序流，最终输出 result 通常是最后一个节点（Reviewer）的内容
        Assertions.assertTrue(trace.getSteps().stream().anyMatch(s -> s.getSource().equals("Coder")));
        Assertions.assertTrue(trace.getSteps().stream().anyMatch(s -> s.getSource().equals("Reviewer")));

        Assertions.assertTrue(result.getContent().contains("<html lang=\"en\">"));
    }
}