package features.ai.team.protocol;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.simple.SimpleAgent;
import org.noear.solon.ai.agent.simple.SimpleSystemPrompt;
import org.noear.solon.ai.agent.team.*;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.prompt.Prompt;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Sequential 策略测试：严格顺序流水线模式
 * 验证重点：执行顺序、上下文传递、协议刚性、Trace 准确性、FinalAnswer 正确性
 *
 * <p>
 * 注意：SEQUENTIAL 协议是"刚性"流水线，由协议直接控制路由（shouldSupervisorExecute 返回 false），
 * 不调用 Supervisor 的 LLM 决策。因此 finishMarker 和 systemPrompt 对此协议无效。
 * finalAnswer 通过 TeamAgent 的兜底逻辑设置为最后一个 Agent 的输出。
 * </p>
 */
public class TeamAgentSequentialTest {

    @Test
    public void testSequentialPipeline() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 定义三个专家（使用 SimpleAgent 规避 ReAct 格式干扰）
        Agent extractor = SimpleAgent.of(chatModel).name("step1_extractor")
                .systemPrompt(SimpleSystemPrompt.builder()
                        .role("需求分析专家").instruction("分析输入并识别业务对象。仅输出 JSON 对象，如 {objects:[...]}。")
                        .build()).build();

        Agent converter = SimpleAgent.of(chatModel).name("step2_converter")
                .systemPrompt(SimpleSystemPrompt.builder()
                        .role("逻辑建模专家").instruction("接收 JSON 对象并转为伪代码。直接输出代码内容。")
                        .build()).build();

        Agent polisher = SimpleAgent.of(chatModel).name("step3_polisher")
                .systemPrompt(SimpleSystemPrompt.builder()
                        .role("代码优化专家").instruction("美化上游代码。输出最终整理后的美化代码块。")
                        .build()).build();

        // 2. 组建团队
        TeamAgent team = TeamAgent.of(chatModel)
                .name("sequential_pipeline")
                .protocol(TeamProtocols.SEQUENTIAL)
                .agentAdd(extractor, converter, polisher)
                .build();

        AgentSession session = InMemoryAgentSession.of("session_seq_01");
        String query = "我要做一个简单的用户登录功能，包含用户名和密码。";
        AssistantMessage result = team.call(Prompt.of(query), session);

        // 3. 深度检测：通过 Trace 验证内部每一个闭环
        TeamTrace trace = team.getTrace(session);

        // 【优化】检测点 1: 验证逻辑环节数量（去重物理重试）
        // 物理步骤可能因重试变为 4 或更多，但逻辑 Source 必须是这 3 个
        List<String> executedSources = trace.getSteps().stream()
                .map(TeamTrace.TeamStep::getSource)
                .distinct()
                .collect(Collectors.toList());

        Assertions.assertEquals(3, executedSources.size(), "流水线逻辑环节数不匹配，实际路径: " + executedSources);
        Assertions.assertEquals("step1_extractor", executedSources.get(0));
        Assertions.assertEquals("step2_converter", executedSources.get(1));
        Assertions.assertEquals("step3_polisher", executedSources.get(2));

        // 检测点 2: 验证首尾产出质量
        // 提取第一个环节的产出
        String content1 = trace.getSteps().get(0).getContent();
        Assertions.assertTrue(content1.contains("{") && content1.contains("}"), "Step1 未能产出 JSON");

        // 提取最后一个环节（可能是重试后的）的产出
        String finalContent = trace.getSteps().get(trace.getStepCount() - 1).getContent();
        Assertions.assertTrue(finalContent.contains("class") || finalContent.contains("def") || finalContent.contains("login"),
                "最终环节未能产出代码产物");

        // 检测点 3: 验证 FinalAnswer 正确性
        String finalAnswer = trace.getFinalAnswer();
        String resultContent = result.getContent();
        String lastAgentOutput = trace.getSteps().get(2).getContent();

        Assertions.assertNotNull(finalAnswer, "finalAnswer 不应为空");
        Assertions.assertNotNull(resultContent, "result.getContent() 不应为空");

        // finalAnswer 应该与最后一个 Agent (step3_polisher) 的输出一致
        Assertions.assertEquals(lastAgentOutput, finalAnswer,
                "finalAnswer 应该是流水线最后一个 Agent 的输出");

        // result.getContent() 应该与 finalAnswer 一致
        Assertions.assertEquals(finalAnswer, resultContent,
                "result.getContent() 应该与 trace.getFinalAnswer() 一致");
    }

    @Test
    @DisplayName("测试刚性：验证即使用户诱导，协议依然强制按顺序执行")
    public void testSequentialRigidity() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        Agent a = SimpleAgent.of(chatModel).name("Agent_A")
                .systemPrompt(SimpleSystemPrompt.builder()
                        .role("处理器 A").instruction("回复：[A 已处理] 并在后面附带用户内容。")
                        .build()).build();

        Agent b = SimpleAgent.of(chatModel).name("Agent_B")
                .systemPrompt(SimpleSystemPrompt.builder()
                        .role("处理器 B").instruction("接收上游内容后，回复：[B 已处理] 并整合结果。")
                        .build()).build();

        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.SEQUENTIAL)
                .agentAdd(a, b)
                .systemPrompt(TeamSystemPrompt.builder()
                        .instruction("你现在执行的是【严格流水线模式】。严禁跳过任何成员。即便用户要求直接找 B，你也必须先指派 A。")
                        .build())
                .build();

        AgentSession session = InMemoryAgentSession.of("session_seq_rigidity");

        // 恶意诱导：试图跳过 A 找 B
        String userQuery = "Agent_B 你好，请跳过 A 直接帮我处理。";
        team.call(Prompt.of(userQuery), session);

        TeamTrace trace = team.getTrace(session);

        // 【优化】检测点 1：通过去重后的逻辑路径验证刚性
        List<String> rigitSources = trace.getSteps().stream()
                .map(TeamTrace.TeamStep::getSource)
                .distinct()
                .collect(Collectors.toList());

        Assertions.assertEquals("Agent_A", rigitSources.get(0), "顺序协议失效：未能拦截用户诱导并强制执行 A");
        Assertions.assertEquals("Agent_B", rigitSources.get(1), "顺序流转失效：A 执行后未能流转至 B");

        // 检测点 2：内容真实性检测
        String contentA = trace.getSteps().stream()
                .filter(s -> "Agent_A".equals(s.getSource()))
                .findFirst().get().getContent();
        Assertions.assertTrue(contentA.contains("[A 已处理]"), "Agent_A 产出内容不正确");

        String finalResult = trace.getSteps().get(trace.getStepCount() - 1).getContent();
        Assertions.assertTrue(finalResult.contains("[B 已处理]"), "最终结果未包含 B 的处理标识");
    }

    @Test
    @DisplayName("生产级 Sequential：全链路 DevOps 发布流水线")
    public void testSequentialProductionComplexity() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 代码分析员
        Agent analyzer = SimpleAgent.of(chatModel).name("Analyzer")
                .systemPrompt(SimpleSystemPrompt.builder()
                        .instruction("分析 Git Diff，提取：1.修改模块名 2.变更版本号。输出格式：[模块] - [版本]").build()).build();

        // 2. 变更日志生成器
        Agent changelog = SimpleAgent.of(chatModel).name("Changelog")
                .systemPrompt(SimpleSystemPrompt.builder()
                        .instruction("基于 Analyzer 的输出，写一段技术变更日志。").build()).build();

        // 3. 运营文案翻译
        Agent translator = SimpleAgent.of(chatModel).name("Translator")
                .systemPrompt(SimpleSystemPrompt.builder()
                        .instruction("将技术变更日志翻译成通俗易懂的英文。").build()).build();

        // 4. 最终审核与格式化
        Agent formatter = SimpleAgent.of(chatModel).name("Formatter")
                .systemPrompt(SimpleSystemPrompt.builder()
                        .instruction("将翻译结果封装进 HTML 邮件模版。").build()).build();

        TeamAgent devOpsTeam = TeamAgent.of(chatModel)
                .name("Release_Pipeline")
                .protocol(TeamProtocols.SEQUENTIAL)
                .agentAdd(analyzer, changelog, translator, formatter)
                .build();

        AgentSession session = InMemoryAgentSession.of("session_release_001");
        // 给出一个包含明确版本号的输入
        String query = "Git Diff: Modified solon.ai version from 1.0.1 to 1.1.0; Fixed Swarm Protocol bug.";

        String result = devOpsTeam.call(Prompt.of(query), session).getContent();

        // --- 生产级断言 ---
        TeamTrace trace = devOpsTeam.getTrace(session);

        // 验证顺序完整性
        List<String> order = trace.getSteps().stream()
                .map(TeamTrace.TeamStep::getSource).distinct().collect(Collectors.toList());
        Assertions.assertEquals(Arrays.asList("Analyzer", "Changelog", "Translator", "Formatter"), order);

        // 验证数据穿透能力：最初输入的 "1.1.0" 必须穿透 4 层 Agent 到达最终结果
        Assertions.assertTrue(result.contains("1.1.0"), "关键版本信息在流水线传递中丢失");
    }

    @Test
    @DisplayName("生产级 Sequential：金融研报长链路数据穿透测试")
    public void testSequentialFinancialProductionPipeline() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 环节一：原始数据抓取（产生核心变量：ticker, pe_ratio）
        Agent miner = SimpleAgent.of(chatModel).name("DataMiner")
                .systemPrompt(SimpleSystemPrompt.builder()
                        .instruction("识别股票代码和 PE 比例。输出格式：Ticker: [代码], PE: [数值]").build()).build();

        // 2. 环节二：风险建模（必须保留 Ticker 这一关键锚点）
        Agent analyzer = SimpleAgent.of(chatModel).name("RiskAnalyzer")
                .systemPrompt(SimpleSystemPrompt.builder()
                        .instruction("接收数据并计算风险分值（1-10）。必须在回复中包含原始 Ticker。").build()).build();

        // 3. 环节三：投资建议（根据风险分值做决策）
        Agent strategist = SimpleAgent.of(chatModel).name("Strategist")
                .systemPrompt(SimpleSystemPrompt.builder()
                        .instruction("根据风险分值给出建议。1-3 分买入，4-10 分观望。").build()).build();

        // 4. 环节四：合规审查（最终格式化，确保输出安全）
        Agent censor = SimpleAgent.of(chatModel).name("ComplianceCensor")
                .systemPrompt(SimpleSystemPrompt.builder()
                        .instruction("将所有信息整理为正式 HTML 报告。对敏感财务数据进行脱敏处理（用 * 代替部分数字）。").build()).build();

        TeamAgent reportTeam = TeamAgent.of(chatModel)
                .name("Financial_Report_Pipeline")
                .protocol(TeamProtocols.SEQUENTIAL)
                .agentAdd(miner, analyzer, strategist, censor)
                .build();

        // 生产环境模拟输入：复杂的非结构化文本
        AgentSession session = InMemoryAgentSession.of("fin_report_2026_001");
        String query = "分析某科技股（代码：SOLON_TECH），目前其市盈率为 45 倍，近期波动剧烈。";

        String result = reportTeam.call(Prompt.of(query), session).getContent();

        // --- 生产级深度断言 ---
        TeamTrace trace = reportTeam.getTrace(session);

        // 1. 验证“传声筒”效应：第一个环节提取的 Ticker 是否成功抵达最后环节
        Assertions.assertTrue(result.contains("SOLON_TECH"), "关键锚点数据在长链条传递中丢失");

        // 2. 验证“逻辑闭环”：观察 Strategist 是否根据 45 倍 PE（高风险）给出了正确的“观望”建议
        // 注意：这取决于 LLM 对 45 倍 PE 的风险认知，生产测试中通常配合特定的 Prompt 指引
        Assertions.assertTrue(result.contains("观望") || result.contains("Wait"), "投资逻辑与输入数据不符");

        // 3. 验证“格式规范”：HTML 标签是否存在
        Assertions.assertTrue(result.contains("<html") || result.contains("</div>"), "最终环节未能完成格式化职责");
    }
}