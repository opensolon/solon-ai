package features.ai.team.manual;

import demo.ai.llm.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
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
public class Issue_IDJQ95_react {
    @Test
    public void case1() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. Coder (ReAct) - A2A 必须能调用工具
        Agent coder = ReActAgent.of(chatModel)
                .name("Coder")
                .description("负责编写 HTML/JS 代码的开发专家")
                .systemPrompt(p -> p
                        .role("Coder 前端开发者")
                        .instruction("你是一个专业的代码助手。\n" +
                                "1. 收到任务后，请在回复正文中直接编写全量的 HTML/JS 代码。\n" +
                                "2. 代码完成后，再移交给 Reviewer 进行审核。"))
                .build();

        // 2. Reviewer (ReAct) - A2A 中 Reviewer 需要主动打回或通过
        Agent reviewer = ReActAgent.of(chatModel)
                .name("Reviewer")
                .description("负责代码安全和逻辑审查的审计专家")
                .systemPrompt(p -> p
                        .role("Reviewer 代码审查专家")
                        .instruction("任务：审查 Coder 提供的代码。\n" +
                                "1. 请检查 [Handover Context] 中提供的代码内容。\n" +
                                "2. 如果代码正确，请输出代码全量内容并声明完成。\n" +
                                "3. 如果没有看到代码或代码有错，请退回给 Coder 并说明原因。"))
                .build();

        // 3. TeamAgent 使用 A2A 协议
        TeamAgent devTeam = TeamAgent.of(chatModel)
                .name("DevTeam")
                .protocol(TeamProtocols.A2A) // 开启自主接力模式
                .agentAdd(coder, reviewer)
                .maxTurns(6)
                .build();

        // 4. 执行
        AgentSession agentSession = InMemoryAgentSession.of();
        AssistantMessage result = devTeam.call(
                Prompt.of("编写一个简单的 HTML 网页，代码不要超过20行（只是测试下）。写完后交给 Reviewer 审核。"),
                agentSession
        );

        System.out.println("======= 最终输出 =======");
        System.out.println(result.getContent());

        // 5. 轨迹解析
        TeamTrace trace = devTeam.getTrace(agentSession);
        List<String> order = trace.getRecords().stream()
                .map(s -> s.getSource())
                .filter(n -> !"supervisor".equalsIgnoreCase(n))
                .collect(Collectors.toList());

        System.out.println("\n执行顺序: " + String.join(" -> ", order));

        // 6. 最终断言
        Assertions.assertTrue(order.contains("Coder"), "Coder 应该参与工作");
        Assertions.assertTrue(order.contains("Reviewer"), "Reviewer 应该参与审核");
        // 核心断言：证明 Reviewer 确实拿到了代码并正确输出了
        Assertions.assertTrue(result.getContent().contains("<html>") || result.getContent().contains("<head>"), "最终结果应包含 HTML 代码");
    }
}