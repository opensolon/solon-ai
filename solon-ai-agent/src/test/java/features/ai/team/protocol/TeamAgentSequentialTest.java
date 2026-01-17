package features.ai.team.protocol;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActSystemPrompt;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.simple.SimpleAgent;
import org.noear.solon.ai.agent.simple.SimpleSystemPrompt;
import org.noear.solon.ai.agent.team.*;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.MethodToolProvider;

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
        List<String> executedSources = trace.getRecords().stream()
                .map(TeamTrace.TeamRecord::getSource)
                .distinct()
                .collect(Collectors.toList());

        Assertions.assertEquals(3, executedSources.size(), "流水线逻辑环节数不匹配，实际路径: " + executedSources);
        Assertions.assertEquals("step1_extractor", executedSources.get(0));
        Assertions.assertEquals("step2_converter", executedSources.get(1));
        Assertions.assertEquals("step3_polisher", executedSources.get(2));

        // 检测点 2: 验证首尾产出质量
        // 提取第一个环节的产出
        String content1 = trace.getRecords().get(0).getContent();
        Assertions.assertTrue(content1.contains("{") && content1.contains("}"), "Step1 未能产出 JSON");

        // 提取最后一个环节（可能是重试后的）的产出
        String finalContent = trace.getRecords().get(trace.getRecordCount() - 1).getContent();
        Assertions.assertTrue(finalContent.contains("class") || finalContent.contains("def") || finalContent.contains("login"),
                "最终环节未能产出代码产物");

        // 检测点 3: 验证 FinalAnswer 正确性
        String finalAnswer = trace.getFinalAnswer();
        String resultContent = result.getContent();
        String lastAgentOutput = trace.getRecords().get(2).getContent();

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
        List<String> rigitSources = trace.getRecords().stream()
                .map(TeamTrace.TeamRecord::getSource)
                .distinct()
                .collect(Collectors.toList());

        Assertions.assertEquals("Agent_A", rigitSources.get(0), "顺序协议失效：未能拦截用户诱导并强制执行 A");
        Assertions.assertEquals("Agent_B", rigitSources.get(1), "顺序流转失效：A 执行后未能流转至 B");

        // 检测点 2：内容真实性检测
        String contentA = trace.getRecords().stream()
                .filter(s -> "Agent_A".equals(s.getSource()))
                .findFirst().get().getContent();
        Assertions.assertTrue(contentA.contains("[A 已处理]"), "Agent_A 产出内容不正确");

        String finalResult = trace.getRecords().get(trace.getRecordCount() - 1).getContent();
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
        List<String> order = trace.getRecords().stream()
                .map(TeamTrace.TeamRecord::getSource).distinct().collect(Collectors.toList());
        Assertions.assertEquals(Arrays.asList("Analyzer", "Changelog", "Translator", "Formatter"), order);

        // 验证数据穿透能力：最初输入的 "1.1.0" 必须穿透 4 层 Agent 到达最终结果
        Assertions.assertTrue(result.contains("1.1.0"), "关键版本信息在流水线传递中丢失");
    }

    public static class MarketTools {
        @ToolMapping(description = "获取行业平均市盈率")
        public double getIndustryAveragePE(String sector) {
            return 25.0; // 模拟返回行业均值
        }
    }

    @Test
    @DisplayName("生产级 Sequential：金融研报长链路数据穿透测试")
    public void testSequentialFinancialProductionPipeline() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 环节一：原始数据抓取（产生核心变量：ticker, pe_ratio）
        Agent miner = ReActAgent.of(chatModel).name("DataMiner")
                .toolAdd(new MethodToolProvider(new MarketTools()))
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("财务数据挖掘专家")
                        .instruction("你的任务是提取关键财务指标。\n" +
                                "1. 识别股票代码（Ticker）和市盈率（PE）。\n" +
                                "2. 必须调用 get_industry_average_pe 获取均值。\n" +
                                "3. 输出必须包含以下结构：\n" +
                                "   - [DATA_START]\n" +
                                "   - Ticker: {ticker}\n" +
                                "   - PE: {pe}\n" +
                                "   - AvgPE: {avg_pe}\n" +
                                "   - [DATA_END]")
                        .build()).build();

        // 2. 环节二：风险建模（必须保留 Ticker 这一关键锚点）
        Agent analyzer = SimpleAgent.of(chatModel).name("RiskAnalyzer")
                .systemPrompt(SimpleSystemPrompt.builder()
                        .role("风控建模师")
                        .instruction("你负责计算风险等级。\n" +
                                "1. 从上文 [DATA_START] 区域提取 Ticker, PE, AvgPE。\n" +
                                "2. 计算逻辑：若 PE > AvgPE * 1.5，风险分值为 8；若 PE > AvgPE，风险分值为 6；否则为 3。\n" +
                                "3. 必须输出格式：Ticker: {ticker}, RiskScore: {score}")
                        .build()).build();

        // 3. 环节三：投资建议（根据风险分值做决策）
        Agent strategist = SimpleAgent.of(chatModel).name("Strategist")
                .systemPrompt(SimpleSystemPrompt.builder()
                        .role("投资策略师")
                        .instruction("根据 RiskScore 给出最终动作。\n" +
                                "- Score >= 7: 建议【观望】并提示估值过高。\n" +
                                "- Score < 7: 建议【买入】。\n" +
                                "输出必须保留 Ticker。")
                        .build()).build();

        // 4. 环节四：合规审查（最终格式化，确保输出安全）
        Agent censor = SimpleAgent.of(chatModel).name("ComplianceCensor")
                .systemPrompt(SimpleSystemPrompt.builder()
                        .role("合规合规审计员")
                        .instruction("生成最终 HTML 报告。\n" +
                                "1. 必须使用 <div> 和 <table> 标签。\n" +
                                "2. 数据脱敏：将 Ticker 的最后 3 位替换为 ***（如 SOLON_***）。\n" +
                                "3. 确保包含‘风险提示：市场有风险，投资需谨慎’。")
                        .build()).build();

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

        // 1. 验证“传声筒”效应：从 DataMiner 的历史产出中寻找原始 Ticker
        boolean originalTickerCaptured = trace.getRecords().stream()
                .filter(s -> "DataMiner".equals(s.getSource()))
                .anyMatch(s -> s.getContent().contains("SOLON_TECH"));
        Assertions.assertTrue(originalTickerCaptured, "DataMiner 未能正确识别原始 Ticker");

        // 2. 验证“逻辑闭环”：观察 Strategist 是否根据 45 倍 PE（高风险）给出了正确的“观望”建议
        // 注意：这取决于 LLM 对 45 倍 PE 的风险认知，生产测试中通常配合特定的 Prompt 指引
        Assertions.assertTrue(result.contains("观望") || result.contains("Wait"), "投资逻辑与输入数据不符");

        // 3. 验证“格式规范”：HTML 标签是否存在
        Assertions.assertTrue(result.contains("<html") || result.contains("</div>"), "最终环节未能完成格式化职责");

        // 4. 验证数据脱敏（合规性）
        Assertions.assertTrue(result.contains("SOLON_***"), "合规环节未能对 Ticker 进行脱敏");

        // 5. 验证风险评分逻辑（数据穿透质量）
        // 因为 45 > 25 * 1.5，所以 RiskScore 应该是 8，建议应该是观望
        Assertions.assertTrue(result.contains("估值过高") || result.contains("观望"), "策略逻辑未基于量化分值执行");

        // 6. 验证免责声明（业务合规）
        Assertions.assertTrue(result.contains("投资需谨慎"), "缺少法定风险提示语");
    }

    @Test
    @DisplayName("生产级 Sequential：模拟链条中断后的状态保持")
    public void testSequentialResumption() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        Agent step1 = SimpleAgent.of(chatModel).name("Step1")
                .systemPrompt(SimpleSystemPrompt.builder()
                        .instruction("输出：Alpha").build()).build();

        Agent step2 = SimpleAgent.of(chatModel).name("Step2")
                .systemPrompt(SimpleSystemPrompt.builder()
                        .instruction("基于上游，输出：Beta").build()).build();

        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.SEQUENTIAL)
                .agentAdd(step1, step2)
                .build();

        // 第一次调用：只让它跑第一步（模拟环境）
        AgentSession session = InMemoryAgentSession.of("resumption_session");
        team.prompt("开始")
                .session(session)
                .options(o->o.maxTurns(1))
                .call();

        // 获取当前轨迹，确认 Step1 已完成
        TeamTrace traceFirst = team.getTrace(session);
        Assertions.assertTrue(traceFirst.getRecords().stream().anyMatch(s -> "Step1".equals(s.getSource())));

        // 第二次调用：验证系统是否能识别 Session 状态继续推进，而不是从 Step1 重头开始
        // 注意：这取决于 Solon AI 的 Session 内部 offset 逻辑
        String finalResult = team.prompt("继续").session(session).call().getContent();
        Assertions.assertTrue(finalResult.contains("Beta"), "未能从中断处恢复并完成 Step2");
    }

    @Test
    @DisplayName("生产级 Sequential：验证上下文变量的外部注入")
    public void testSequentialContextInjection() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 提取器：确保输出结构化，方便下游定位
        Agent analyzer = SimpleAgent.of(chatModel).name("Analyzer")
                .systemPrompt(SimpleSystemPrompt.builder()
                        .role("数据分析员")
                        .instruction("请从输入中提取纯数字金额。格式：[AMOUNT: 数值]")
                        .build()).build();

        // 2. 计算器：显式指明参考变量来源
        Agent calculator = SimpleAgent.of(chatModel).name("Calculator")
                .systemPrompt(SimpleSystemPrompt.builder()
                        .role("计费引擎")
                        .instruction("1. 识别上文中的 [AMOUNT] 标签。\n" +
                                "2. 读取上下文预置变量 #{DISCOUNT}（若未提供，默认为 1.0）。\n" +
                                "3. 最终报价 = AMOUNT * #{DISCOUNT}。请直接输出最终报价数值。")
                        .build()).build();

        TeamAgent team = TeamAgent.of(chatModel)
                .name("Injection_Test_Team")
                .protocol(TeamProtocols.SEQUENTIAL)
                .agentAdd(analyzer, calculator)
                .build();

        AgentSession session = InMemoryAgentSession.of("injection_session");
        // 模拟外部注入：DISCOUNT = 0.8
        session.getSnapshot().put("DISCOUNT", "0.8");

        // 运行流水线
        String result = team.call(Prompt.of("商品价格 1000 元"), session).getContent();

        // --- 深度断言 ---
        System.out.println("最终结算结果: " + result);

        // 1. 业务逻辑断言
        Assertions.assertTrue(result.contains("800"), "注入变量 0.8 未生效，结果未达到预期 800");

        // 2. 链路追踪断言：验证 Analyzer 是否正常工作
        TeamTrace trace = team.getTrace(session);
        boolean amountExtracted = trace.getRecords().stream()
                .anyMatch(s -> s.getContent().contains("1000"));
        Assertions.assertTrue(amountExtracted, "Analyzer 节点未能在历史中留下 1000 元的提取记录");
    }

    @Test
    @DisplayName("生产级 Sequential：处理中间环节的无效/空输出")
    public void testSequentialEmptyStepHandling() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 模拟一个可能由于敏感词过滤导致输出为空的 Agent
        Agent muteAgent = SimpleAgent.of(chatModel).name("MuteAgent")
                .systemPrompt(SimpleSystemPrompt.builder().instruction("不要说话，保持沉默，输出空字符串。").build()).build();

        Agent recoveryAgent = SimpleAgent.of(chatModel).name("RecoveryAgent")
                .systemPrompt(SimpleSystemPrompt.builder().instruction("如果上游没说话，请说：[恢复执行]。").build()).build();

        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.SEQUENTIAL)
                .agentAdd(muteAgent, recoveryAgent)
                .build();

        AgentSession session = InMemoryAgentSession.of("empty_session");
        String result = team.call(Prompt.of("Hello"), session).getContent();

        // 验证链条是否依然走到了最后
        Assertions.assertTrue(result.contains("恢复执行"), "中间环节空输出导致链条意外熔断");
    }
}