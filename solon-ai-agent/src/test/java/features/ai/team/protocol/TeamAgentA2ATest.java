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

    @Test
    public void testA2ALoopAndMaxIteration() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 两个 Agent 互相推卸责任
        Agent agentA = ReActAgent.of(chatModel).name("agentA")
                .systemPrompt(ReActSystemPrompt.builder()
                        .instruction("无论收到什么，都请转交给 agentB。").build()).build();

        Agent agentB = ReActAgent.of(chatModel).name("agentB")
                .systemPrompt(ReActSystemPrompt.builder()
                        .instruction("无论收到什么，都请转交给 agentA。").build()).build();

        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.A2A)
                .agentAdd(agentA, agentB)
                .maxTotalIterations(5) // 设置一个较小的阈值
                .build();

        AgentSession session = InMemoryAgentSession.of("session_loop_test");

        // 验证：不会因为死循环抛出超时以外的异常，且最终能停止
        Assertions.assertDoesNotThrow(() -> team.call(Prompt.of("开始踢皮球"), session));

        TeamTrace trace = team.getTrace(session);
        // 验证执行步数是否触达了上限（5步左右）
        Assertions.assertTrue(trace.getSteps().size() >= 5, "应该触达最大迭代次数限制");
    }

    @Test
    public void testA2AComplexProductionPipeline() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 市场专家：初始化任务，定义基调
        Agent analyst = ReActAgent.of(chatModel).name("Analyst")
                .description("市场分析专家")
                .systemPrompt(ReActSystemPrompt.builder()
                        .instruction("你负责分析产品卖点。分析完成后，必须通过 transfer_to 移交给 Copywriter。")
                        .build()).build();

        // 2. 文案专家：负责执行，具备分支决策能力
        Agent copywriter = ReActAgent.of(chatModel).name("Copywriter")
                .description("资深文案")
                .systemPrompt(ReActSystemPrompt.builder()
                        .instruction("根据市场策略写文案。如果文案中包含'限时折扣'或'全球首发'，必须先移交给 Legal 进行合规性检查。")
                        .build()).build();

        // 3. 法务专家：逻辑闸口
        Agent legal = ReActAgent.of(chatModel).name("Legal")
                .description("合规风控")
                .systemPrompt(ReActSystemPrompt.builder()
                        .instruction("审核文案。若有夸大宣传，在 memo 中写明原因并打回给 Copywriter；若通过，移交给 Designer。")
                        .build()).build();

        // 4. 设计专家：流水线末端
        Agent designer = ReActAgent.of(chatModel).name("Designer")
                .description("视觉设计师")
                .systemPrompt(ReActSystemPrompt.builder()
                        .instruction("接收文案，输出响应式 HTML/CSS 营销页面。完成后直接返回最终成果。")
                        .build()).build();

        TeamAgent marketingTeam = TeamAgent.of(chatModel)
                .name("Global_Marketing_Squad")
                .protocol(TeamProtocols.A2A) // 核心：专家自主接力
                .agentAdd(analyst, copywriter, legal, designer)
                .maxTotalIterations(15) // 长链路，迭代上限提高
                .build();

        AgentSession session = InMemoryAgentSession.of("prod_test_001");
        String query = "我们要发布一款'全球首发'的高端AI降噪耳机，价格 2999 元，含限时 8 折优惠。请开始流程。";

        System.out.println(">>> 正在启动生产级 A2A 流水线...");
        String result = marketingTeam.call(Prompt.of(query), session).getContent();

        // --- 生产环境关键断言 ---
        TeamTrace trace = marketingTeam.getTrace(session);
        List<String> history = trace.getSteps().stream()
                .map(TeamTrace.TeamStep::getSource)
                .filter(s -> !s.equalsIgnoreCase("supervisor"))
                .collect(Collectors.toList());

        System.out.println("协作路径: " + String.join(" -> ", history));

        // 验证 1: 路径深度。复杂的 A2A 至少应经过 3-4 个环节
        Assertions.assertTrue(history.size() >= 3, "链路太短，可能存在跳步");

        // 验证 2: 逻辑触达。因为 query 提到了“全球首发”和“折扣”，必须经过 Legal
        Assertions.assertTrue(history.contains("Legal"), "法务节点未被触达，合规逻辑失效");

        // 验证 3: 上下文接力。最终产出应保留 Analyst 的定位和 Copywriter 的内容
        Assertions.assertTrue(result.contains("2999") || result.contains("8折"), "核心业务数据在传递中丢失");

        // 验证 4: 结果输出。Designer 必须产出了代码
        Assertions.assertTrue(result.toLowerCase().contains("div") || result.toLowerCase().contains("style"), "美工未输出 HTML 成果");
    }
}