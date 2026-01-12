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
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamProtocols;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A2A (Agent-to-Agent) 协作策略提示词优化测试
 */
public class TeamAgentA2ATest {

    @Test
    public void testA2ABasicLogic() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 设计师：强化 instruction，要求必须产出具体规格
        Agent designer = ReActAgent.of(chatModel)
                .name("designer")
                .description("UI/UX 设计师")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("UI/UX 专家")
                        .instruction("1. 必须输出包含背景色、主色调、圆角值的规格。\n" +
                                "2. 任务完成后使用 transfer_to 移交给 developer。")
                        .build())
                .build();

        // 2. 开发：强化输出标识
        Agent developer = ReActAgent.of(chatModel)
                .name("developer")
                .description("前端开发")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("Web 程序员")
                        .instruction("接收设计方案后输出 HTML。完成后输出关键字 FINISH。")
                        .build())
                .build();

        TeamAgent team = TeamAgent.of(chatModel)
                .name("dev_squad")
                .protocol(TeamProtocols.A2A)
                .agentAdd(designer, developer)
                .finishMarker("FINISH")
                .maxTotalIterations(5)
                .build();

        AgentSession session = InMemoryAgentSession.of("session_02");
        String query = "设计深色登录页，由开发实现 HTML，完成后告知。";

        String result = team.call(Prompt.of(query), session).getContent();
        TeamTrace trace = team.getTrace(session);

        // --- 增强版断言逻辑 ---

        // 1. 验证角色参与度：必须 designer 先，developer 后
        List<String> agentOrder = trace.getSteps().stream()
                .map(TeamTrace.TeamStep::getSource)
                .filter(name -> !"supervisor".equalsIgnoreCase(name)) // 排除主管自身的思考
                .collect(Collectors.toList());

        System.out.println("角色执行顺序: " + agentOrder);

        Assertions.assertTrue(agentOrder.contains("designer"), "设计师必须参与");
        Assertions.assertTrue(agentOrder.contains("developer"), "开发者必须参与");

        // 2. 验证协作深度：防止 Supervisor 强制干预（如果 Supervisor 亲自写代码，说明 Agent 协作失败了）
        // 检查最后一步输出 code 的 Agent 是否为 developer
        long developerSteps = trace.getSteps().stream()
                .filter(s -> "developer".equals(s.getSource()))
                .count();
        Assertions.assertTrue(developerSteps > 0, "Developer 应该产出代码，而不是由 Supervisor 兜底");

        // 3. 验证 JSON 解析质量：检查是否有 Observation 包含 Error
        boolean hasJsonError = trace.getSteps().stream()
                .anyMatch(s -> s.getContent().contains("Error parsing Action JSON"));
        Assertions.assertFalse(hasJsonError, "协作过程中不应出现 JSON 解析错误 (Unclosed string)");

        // 4. 验证最终产出的实质内容
        Assertions.assertTrue(result.contains("<html") && result.contains("background-color"), "最终结果必须包含 HTML 标签和深色样式定义");
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
                .agentAdd(agentA, agentB)
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
                .agentAdd(agentA)
                .build();

        AgentSession session = InMemoryAgentSession.of("session_04");
        Assertions.assertDoesNotThrow(() -> team.call(Prompt.of("寻找超人协助"), session));

        TeamTrace trace = team.getTrace(session);
        Assertions.assertEquals(Agent.ID_END, trace.getRoute(), "当路由目标非法时，A2A 协议应安全路由至 END");
    }
}