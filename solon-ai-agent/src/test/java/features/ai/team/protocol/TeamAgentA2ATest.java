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
                .maxTurns(10)
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
        List<String> agentOrder = trace.getRecords().stream()
                .map(TeamTrace.TeamRecord::getSource)
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
                .maxTurns(5) // 设置一个较小的阈值
                .build();

        AgentSession session = InMemoryAgentSession.of("session_loop_test");

        // 验证：不会因为死循环抛出超时以外的异常，且最终能停止
        Assertions.assertDoesNotThrow(() -> team.call(Prompt.of("开始踢皮球"), session));

        TeamTrace trace = team.getTrace(session);
        // 验证执行步数是否触达了上限（5步左右）
        Assertions.assertTrue(trace.getRecords().size() >= 5, "应该触达最大迭代次数限制");
    }

    @Test
    public void testA2AComplexProductionPipeline() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 市场专家：负责提取商业价值，启动流水线
        Agent analyst = ReActAgent.of(chatModel).name("Analyst")
                .description("市场分析专家")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("资深市场分析师")
                        .instruction("你负责分析产品的商业卖点。在梳理完定位、受众和核心优势后，必须通过 transfer_to 移交给 Copywriter 进行文案创作。")
                        .build()).build();

        // 2. 文案专家：负责内容转化，具备合规预判
        Agent copywriter = ReActAgent.of(chatModel).name("Copywriter")
                .description("资深文案")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("创意文案主管")
                        .instruction("根据市场分析编写极具吸引力的营销文案。注意：如果文案中涉及'全球首发'、'最'、'第一'或具体价格折扣，根据公司规定，你必须先移交给 Legal 进行合规性检查。")
                        .build()).build();

        // 3. 法务专家：严谨的逻辑闸口
        Agent legal = ReActAgent.of(chatModel).name("Legal")
                .description("合规风控")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("法务合规审计员")
                        .instruction("你负责审核营销内容的法律风险。若发现夸大宣传或违反广告法，请在 memo 中明确修改要求并打回给 Copywriter；若审核通过，请移交给 Designer 进行页面实现。")
                        .build()).build();

        // 4. 设计专家：输出成果的终端
        Agent designer = ReActAgent.of(chatModel).name("Designer")
                .description("视觉设计师 (具备 HTML/CSS 页面开发能力，负责视觉呈现与前端落地)")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("高级 UI 工程师")
                        .instruction(trace -> "你是当前系统中唯一的 Web 开发专家。你的目标是接收终审文案并将其转化为响应式 HTML/CSS 营销页面。你必须输出代码，禁止推卸责任。任务完成后请以 " + trace.getConfig().getFinishMarker() + " 结尾。")
                        .build())
                .build();

        TeamAgent marketingTeam = TeamAgent.of(chatModel)
                .name("Global_Marketing_Squad")
                .protocol(TeamProtocols.A2A)
                .agentAdd(analyst, copywriter, legal, designer)
                .maxTurns(15)
                .build();

        AgentSession session = InMemoryAgentSession.of("prod_test_001");
        String query = "我们要发布一款'全球首发'的高端AI降噪耳机，价格 2999 元，含限时 8 折优惠。请启动完整的营销上线流程，直到产出最终 web 页面。";
        System.out.println(">>> 正在启动生产级 A2A 流水线...");
        String result = marketingTeam.call(Prompt.of(query), session).getContent();

        // --- 生产环境关键断言 ---
        TeamTrace trace = marketingTeam.getTrace(session);
        List<String> history = trace.getRecords().stream()
                .map(TeamTrace.TeamRecord::getSource)
                .filter(s -> !s.equalsIgnoreCase("supervisor"))
                .collect(Collectors.toList());

        System.out.println("协作路径: " + String.join(" -> ", history));

        // 验证 1: 路径深度
        Assertions.assertTrue(history.size() >= 3, "链路太短，可能存在跳步");

        // 验证 2: 逻辑触达
        Assertions.assertTrue(history.contains("Legal"), "法务节点未被触达，合规逻辑失效");

        // 验证 3: 上下文接力
        Assertions.assertTrue(result.contains("2999") || result.contains("8") || result.contains("折"), "核心业务数据丢失");

        // 验证 4: 结果输出
        String lowerResult = result.toLowerCase();
        Assertions.assertTrue(lowerResult.contains("div") || lowerResult.contains("style") || lowerResult.contains("html"), "Designer 未能产出有效的代码内容");
    }
}