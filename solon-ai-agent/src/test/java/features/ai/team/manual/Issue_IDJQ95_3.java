package features.ai.team.manual;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActSystemPrompt;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamProtocols;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.test.SolonTest;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A2A 协议优化版单测
 * 1. 移除硬编码工具提示，利用描述（Description）实现语义接力
 * 2. 移除禁止调用函数的限制，允许 A2A 自动注入 transfer 工具
 */
@SolonTest
public class Issue_IDJQ95_3 {
    @Test
    public void case1() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. Coder (ReAct) - A2A 必须能调用工具
        Agent coder = ReActAgent.of(chatModel)
                .name("Coder")
                .description("负责编写 HTML/JS 代码的开发专家") // 重要：让 Reviewer 知道有问题找谁
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("前端开发者")
                        .instruction("任务：编写完整的 HTML/JS 代码。\n" +
                                "协作：写完后请交给 Reviewer 审查代码质量。直接输出代码，不要包裹 Markdown 块。")
                        .build())
                .build();

        // 2. Reviewer (ReAct) - A2A 中 Reviewer 需要主动打回或通过
        Agent reviewer = ReActAgent.of(chatModel)
                .name("Reviewer")
                .description("负责代码安全和逻辑审查的审计专家")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("代码审查专家")
                        .instruction("你只需要做一件事：\n" +
                                "1. 请从上下文（Context）中提取 [Coder] 编写的代码。\n" +
                                "2. 如果代码 OK，请原样输出代码，并加上‘审查通过’。\n" +
                                "3. 如果没看到代码，请说‘代码缺失’。")
                        .build())
                .build();

        // 3. TeamAgent 使用 A2A 协议
        TeamAgent devTeam = TeamAgent.of(chatModel)
                .name("DevTeam")
                .protocol(TeamProtocols.HIERARCHICAL) // 开启自主接力模式
                .agentAdd(coder, reviewer)
                .maxTotalIterations(6)
                .build();

        // 4. 执行
        AgentSession agentSession = InMemoryAgentSession.of();
        // 初始 Query 明确第一个动作
        AssistantMessage result = devTeam.call(
                Prompt.of("请 Coder 编写一个英文拼写 HTML 游戏，然后交给 Reviewer 审核。"),
                agentSession
        );

        System.out.println("=======最终输出=======");
        System.out.println(result.getContent());

        // 5. 轨迹解析
        TeamTrace trace = devTeam.getTrace(agentSession);
        System.out.println("\n--- 协作轨迹 ---");
        List<String> order = trace.getRecords().stream()
                .map(s -> s.getSource())
                .filter(n -> !"supervisor".equalsIgnoreCase(n))
                .collect(Collectors.toList());

        System.out.println("执行顺序: " + String.join(" -> ", order));

        // 6. 最终断言
        Assertions.assertTrue(order.contains("Coder"), "Coder 应该参与工作");
        Assertions.assertTrue(order.contains("Reviewer"), "Reviewer 应该参与审核");
        Assertions.assertTrue(result.getContent().contains("<!DOCTYPE html>"));
    }
}