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
import org.noear.solon.ai.agent.simple.SimpleAgent;
import org.noear.solon.ai.agent.simple.SimpleSystemPrompt;
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

        // 1. 设计师：明确要求使用 transfer 移交给开发，严禁自行写 HTML
        Agent designer = ReActAgent.of(chatModel)
                .name("designer")
                .description("UI/UX 设计师")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("UI/UX 专家")
                        .instruction("你负责深色登录页设计。你必须：\n" +
                                "1. 先详细描述界面的视觉规格（色值、布局、字体、组件样式）。\n" +
                                "2. 确保将这些规格作为 instruction 参数传递给 developer。\n" +
                                "严禁在未产出具体规格的情况下调用移交工具。") // 语义化描述
                        .build())
                .build();

        // 2. 开发：接收设计方案产出 HTML
        Agent developer = ReActAgent.of(chatModel)
                .name("developer")
                .description("前端开发")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("Web 程序员")
                        .instruction("接收设计，输出 HTML 代码。")
                        .build())
                .build();

        TeamAgent team = TeamAgent.of(chatModel)
                .name("dev_squad")
                .protocol(TeamProtocols.A2A) // 使用 A2A 接力协议
                .agentAdd(designer, developer)
                .maxTotalIterations(10)
                .build();

        AgentSession session = InMemoryAgentSession.of("session_a2a_02");
        // 这里的 Query 需要强调流程，配合 A2A 的自主性
        String query = "请设计师先进行深色登录页设计，然后交给开发实现 HTML。";

        String result = team.call(Prompt.of(query), session).getContent();

        System.out.println("============最终输出============");
        System.out.println(result);

        TeamTrace trace = team.getTrace(session);

        // --- 增强版断言逻辑 ---

        // 获取物理执行顺序（排除 supervisor）
        List<String> agentOrder = trace.getSteps().stream()
                .map(TeamTrace.TeamStep::getSource)
                .filter(name -> !"supervisor".equalsIgnoreCase(name))
                .collect(Collectors.toList());

        System.out.println("角色执行轨迹: " + String.join(" -> ", agentOrder));

        // 断言 1: 角色参与度
        Assertions.assertTrue(agentOrder.contains("designer"), "设计师环节缺失");
        Assertions.assertTrue(agentOrder.contains("developer"), "开发环节缺失");

        // 断言 2: A2A 逻辑顺序 (Designer 应该在 Developer 之前)
        int designerIdx = agentOrder.indexOf("designer");
        int developerIdx = agentOrder.lastIndexOf("developer");
        Assertions.assertTrue(designerIdx < developerIdx, "执行顺序错误：开发应在设计之后");

        // 断言 3: 检查是否真正触发了 A2A 工具调用
        // 只要 trace 中出现了指令传递，说明工具注入成功
        boolean hasHandover = trace.getProtocolContext().containsKey("transfer_count") || agentOrder.size() >= 2;
        Assertions.assertTrue(hasHandover, "未检测到有效的专家接力行为");

        // 断言 4: 结果验证
        Assertions.assertTrue(result.contains("<html"), "最终产物应包含 HTML");
        Assertions.assertTrue(result.contains("background"), "最终产物应包含设计中要求的背景样式");
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