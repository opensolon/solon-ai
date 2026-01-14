package features.ai.team.manual;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActSystemPrompt;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamSystemPrompt;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.prompt.Prompt;

/**
 *
 * @author noear 2026/1/14 created
 *
 */
public class Issue_IDJQ95 {
    @Test
    public void case1() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 2. 定义【开发者】Agent
        Agent coder = ReActAgent.of(chatModel)
                .name("Coder")
                .description("前端开发专家，负责编写 HTML/CSS/JS 代码，并根据审查反馈进行修改。")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("你是一位经验丰富的网页开发者，精通 HTML、JS、CSS、TailwindCSS、现代前端技术")
                        .instruction(
                                "# 任务\n" +
                                        "你需要帮助我生成一个完整的前端页面代码（简单实现，不超过500行代码），包含 HTML、CSS、JavaScript 三部分。\n" +
                                        "如果收到审查反馈，请根据反馈修改代码。\n\n" +
                                        "# 输出要求\n" +
                                        "直接输出完整的 HTML 代码，不需要用 ```html 包裹。\n" +
                                        "如果是修改，先简要说明修改点。"
                        )
                        .build())
                .build();

        // 3. 定义【审核员】Agent
        Agent reviewer = ReActAgent.of(chatModel)
                .name("Reviewer")
                .description("代码审查专家，负责检查代码质量，输出审查结果和修改建议。")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("你是一名严格的代码审查专家")
                        .instruction(
                                "# 任务\n" +
                                        "审查前端代码的质量、规范性、功能完整性。\n\n" +
                                        "# 输出格式\n" +
                                        "- 如果代码有问题：以【需要修改】开头，列出具体问题和修改建议\n" +
                                        "- 如果代码没问题：输出【审查通过】代码质量良好，可以交付。\n\n" +
                                        "# 审查要点\n" +
                                        "1. HTML 结构是否语义化\n" +
                                        "2. CSS 样式是否合理\n" +
                                        "3. JS 逻辑是否正确\n" +
                                        "4. 是否满足原始需求"
                        )
                        .build())
                .build();

        // 4. 组建【开发小组】Team - 关键配置
        TeamAgent devTeam = TeamAgent.of(chatModel)
                .name("DevTeam")
                .agentAdd(coder, reviewer)
                .maxTotalIterations(8)
                .finishMarker("[FINISH]")
                .systemPrompt(TeamSystemPrompt.builder()
                        .role("团队指挥")
                        .instruction("如果Reviewer审查通过，输出 [FINISH] 并在其后完整转发 Coder 的 HTML 代码。")
                        .build())
                .build();

        // 5. 执行任务
        System.out.println(">>> 任务开始...");
        AgentSession agentSession = InMemoryAgentSession.of();


        AssistantMessage result = devTeam.call(Prompt.of("帮我写一个英文闯关小游戏，包含单词拼写关卡"), agentSession);

        System.out.println("\n--- 最终输出结果 ---");
        System.out.println(result.getContent());

        // 6. 查看执行轨迹
        System.out.println("\n--- 执行轨迹 ---");
        TeamTrace trace = devTeam.getTrace(agentSession);
        if (trace != null) {
            for (TeamTrace.TeamStep step : trace.getSteps()) {
                String content = step.getContent();
                int len = Math.min(150, content.length());
                System.out.println("[" + step.getSource() + "] " + content.substring(0, len) + "...\n");
            }
        }
    }
}
