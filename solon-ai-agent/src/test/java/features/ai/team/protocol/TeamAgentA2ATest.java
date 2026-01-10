/*
 * Copyright 2017-2026 noear.org and authors
 * ... (License remains unchanged)
 */
package features.ai.team.protocol;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActSystemPrompt;
import org.noear.solon.ai.agent.react.ReActSystemPromptCn;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamProtocols;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;

/**
 * A2A (Agent-to-Agent) 协作策略提示词优化测试
 */
public class TeamAgentA2ATest {

    @Test
    public void testA2ABasicLogic() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();



        // 1. 优化设计师提示词：明确“触发条件”与“交接协议”
        Agent designer = ReActAgent.of(chatModel)
                .name("designer")
                .description("UI/UX 设计师，负责视觉方案与交互逻辑。")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("你是一个富有创意的 UI/UX 设计专家")
                        .instruction("### 工作准则\n" +
                                "1. 优先提供视觉风格、配色方案及组件布局建议。\n" +
                                "2. **触发接力**：当需求明确要求代码实现（HTML/CSS）时，必须调用 `transfer_to` 工具移交给 `developer`。\n" +
                                "3. **交接备注**：在 memo 参数中简述你的设计重点，以便开发理解。")
                        .build())
                .build();

        // 2. 优化开发提示词：强化“终点意识”，防止无效循环
        Agent developer = ReActAgent.of(chatModel)
                .name("developer")
                .description("前端开发工程师，负责高质量代码实现。")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("你是一个精通现代 Web 技术的程序员")
                        .instruction("### 协作逻辑\n" +
                                "1. 接收 `designer` 的方案后，立即转化为响应式 HTML/CSS 代码。\n" +
                                "2. **禁止回传**：除非设计方案严重缺失关键信息，否则不要将任务转回设计师。\n" +
                                "3. **任务终结**：代码输出完成后，直接输出关键字 `FINISH` 宣告任务完成。")
                        .build())
                .build();

        TeamAgent team = TeamAgent.of(chatModel)
                .name("dev_squad")
                .protocol(TeamProtocols.A2A)
                .addAgent(designer, developer)
                .finishMarker("FINISH")
                .maxTotalIterations(5)
                .build();

        // --- 执行逻辑保持不变 ---
        AgentSession session = InMemoryAgentSession.of("session_01");
        String query = "请帮我设计一个深色模式的登录页面，并直接转交给开发写出 HTML 代码，完成后告诉我。";

        String result = team.call(Prompt.of(query), session).getContent();
        TeamTrace trace = team.getTrace(session);

        System.out.println("=== 协作足迹 ===\n" + trace.getFormattedHistory());

        Assertions.assertTrue(trace.getSteps().stream().map(TeamTrace.TeamStep::getAgentName).distinct().count() >= 2);
        Assertions.assertTrue(result.contains("<html>") || result.contains("css") || result.contains("代码"));
    }

    @Test
    public void testA2AMemoInjection() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 优化：利用 ReActSystemPromptCn 确保模型明白 memo 是为了上下文接力
        Agent agentA = ReActAgent.of(chatModel).name("agentA")
                .description("流程发起专家")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("任务初始化节点")
                        .instruction("执行初始化后，务必调用 `transfer_to` 将控制权交给 `agentB`，并将关键信息 'KEY_INFO_999' 放入 memo 中。")
                        .build())
                .build();

        Agent agentB = ReActAgent.of(chatModel).name("agentB")
                .description("流程处理专家")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("后端处理节点")
                        .instruction("你将收到来自上游的 memo 信息。请确认收到 'KEY_INFO_999' 并完成后续工作。")
                        .build())
                .build();

        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.A2A)
                .addAgent(agentA, agentB)
                .build();

        AgentSession session = InMemoryAgentSession.of("session_03");
        team.call(Prompt.of("开始流水线任务"), session);

        TeamTrace trace = team.getTrace(session);
        String history = trace.getFormattedHistory();

        System.out.println("History trace:\n" + history);
        Assertions.assertTrue(history.contains("KEY_INFO_999"), "Memo 信息应通过协作链条传递");
    }

    @Test
    public void testA2AHallucinationDefense() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 优化：即便测试幻觉，也给一个标准的提示词结构，让框架更容易捕捉其“异常行为”
        Agent agentA = ReActAgent.of(chatModel).name("agentA")
                .description("异常测试节点")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("测试受众")
                        .instruction("由于你现在处于异常测试状态，请故意尝试移交给不存在的专家 'superman'。")
                        .build())
                .build();

        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.A2A)
                .addAgent(agentA)
                .build();

        AgentSession session = InMemoryAgentSession.of("session_04");
        Assertions.assertDoesNotThrow(() -> team.call(Prompt.of("寻找超人协助"), session));

        TeamTrace trace = team.getTrace(session);
        Assertions.assertEquals(Agent.ID_END, trace.getRoute(), "当路由目标非法时，A2A 协议应安全路由至 END");
    }
}