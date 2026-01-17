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

public class TeamAgentResilienceTest {

    /**
     * 测试：跨 Agent 的上下文长度保护
     * 优化：注入“元指令持久化”意识，确保长链条下暗号不丢失。
     */
    @Test
    public void testContextPersistenceAcrossMultipleHandovers() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();



        // 统一优化提示词风格
        ReActSystemPrompt relayPrompt = ReActSystemPrompt.builder()
                .role("协作流水线节点")
                .instruction("### 核心原则\n" +
                        "1. 处理你职责范围内的任务。\n" +
                        "2. **上下文检查**：必须扫描并保留用户原始需求中的所有全局约束（如暗号、格式要求）。\n" +
                        "3. **交接规范**：完成本步后，使用 transfer_to 移交给下一位专家，并在 memo 中强调未完成的全局约束。")
                .build();

        Agent a = ReActAgent.of(chatModel).name("agent_a").description("负责初步分析").systemPrompt(relayPrompt).build();
        Agent b = ReActAgent.of(chatModel).name("agent_b").description("负责逻辑加工").systemPrompt(relayPrompt).build();
        Agent c = ReActAgent.of(chatModel).name("agent_c").description("负责最终汇总").systemPrompt(relayPrompt).build();

        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.A2A)
                .agentAdd(a, b, c)
                .maxTotalIterations(10)
                .build();

        AgentSession session = InMemoryAgentSession.of("resilience_01");
        String query = "请依次转交给 a, b, c 处理。最后输出结果时，必须包含暗号：SOLON-AI-SECRET";
        String result = team.call(Prompt.of(query), session).getContent();

        Assertions.assertTrue(result.contains("SOLON-AI-SECRET"), "长链条转交导致原始约束丢失");
    }

    /**
     * 测试：混合领域需求的市场选择
     * 优化：强化“主次矛盾”识别，确保 Market 协议能精准选出最核心的专家。
     */
    @Test
    public void testMarketSelectionWithAmbiguousTask() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        Agent pythonExpert = ReActAgent.of(chatModel).name("python_expert")
                .description("负责 Python 脚本编写、数据清洗及自动化脚本工具开发。")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("Python 全栈专家")
                        .instruction("专注于 Python 相关的实现。如果任务涉及跨语言协作，请明确你完成的部分。")
                        .build())
                .build();

        Agent javaExpert = ReActAgent.of(chatModel).name("java_expert")
                .description("负责 Java 后端工程、SpringCloud 微服务架构及企业级应用开发。")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("Java 架构专家")
                        .instruction("专注于 Java 系统设计与编码。对于辅助性的脚本需求，可建议通过 Python 工具完成。")
                        .build())
                .build();

        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.MARKET_BASED)
                .agentAdd(pythonExpert, javaExpert)
                .build();

        AgentSession session = InMemoryAgentSession.of("session_market_ambiguous");
        String query = "请帮我开发一个 Java 后端接口，顺便写一个简单的 Python 脚本用于导入测试数据。";
        String result = team.call(Prompt.of(query), session).getContent();

        TeamTrace trace = team.getTrace(session);
        Assertions.assertTrue(trace.getRecordCount() > 0);
        Assertions.assertFalse(result.isEmpty());
    }

    /**
     * 测试：无匹配能力的优雅处理
     * 优化：引导 Agent 在无法处理时，给出带有“专业边界”的礼貌拒绝，而非产生幻觉。
     */
    @Test
    public void testMarketWithNoMatchingExpert() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        Agent coder = ReActAgent.of(chatModel).name("coder")
                .description("只懂编程、算法和代码实现。")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("软件开发工程师")
                        .instruction("你只处理与代码、架构、技术文档相关的请求。对于非技术类请求（如烹饪、艺术），请明确回复：'抱歉，这超出了我的专业领域'。")
                        .build())
                .build();

        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.MARKET_BASED)
                .agentAdd(coder)
                .build();

        AgentSession session = InMemoryAgentSession.of("session_market_none");
        String query = "如何做一顿正宗的北京烤鸭？";
        String result = team.call(Prompt.of(query), session).getContent();

        System.out.println("=== 优雅拒绝回复 ===\n" + result);

        TeamTrace trace = team.getTrace(session);
        Assertions.assertTrue(trace.getRecordCount() >= 1);
    }

    /**
     * 协议层终止测试保持逻辑
     */
    @Test
    public void testGracefulTermination() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        Agent looper = ReActAgent.of(chatModel).name("looper")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("任务中转节点")
                        .instruction("你只负责将任务移交给他人，永不结束。")
                        .build())
                .build();

        TeamAgent team = TeamAgent.of(chatModel)
                .agentAdd(looper)
                .maxTotalIterations(2)
                .build();

        AgentSession session = InMemoryAgentSession.of("resilience_02");
        Assertions.assertDoesNotThrow(() -> team.call(Prompt.of("开始任务"), session));
    }

    /**
     * 测试：混合协议嵌套（分层架构）
     * 场景：Supervisor 调度一个由 Sequential 协议驱动的子团队。
     * 验证：主团队的决策能否透传至子团队的线性流水线，并最终安全返回。
     */
    @Test
    public void testNestedProtocolHierarchicalWithSequential() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 定义子团队的成员（线性流水线）
        Agent developer = ReActAgent.of(chatModel).name("sub_developer")
                .description("负责编写核心代码。").build();
        Agent tester = ReActAgent.of(chatModel).name("sub_tester")
                .description("负责代码测试，必须输出测试报告。").build();

        // 2. 构建子团队：使用 SEQUENTIAL 协议
        TeamAgent deliverySubTeam = TeamAgent.of(chatModel)
                .name("delivery_group")
                .protocol(TeamProtocols.SEQUENTIAL) // 内部是刚性流水线
                .agentAdd(developer, tester)
                .build();

        // 3. 构建主团队：使用默认的 SUPERVISOR 协议
        Agent manager = ReActAgent.of(chatModel).name("manager")
                .description("项目经理，负责任务分发。").build();

        TeamAgent enterpriseTeam = TeamAgent.of(chatModel)
                .name("enterprise_center")
                .agentAdd(manager)
                .agentAdd(deliverySubTeam) // 子团队作为成员加入
                .build();

        AgentSession session = InMemoryAgentSession.of("session_nested_01");
        String query = "请研发组帮我实现一个用户登录功能，并给出测试结果。";

        String result = enterpriseTeam.call(Prompt.of(query), session).getContent();

        // 验证 Trace 链路：Manager -> (Sub_Developer -> Sub_Tester)
        TeamTrace trace = enterpriseTeam.getTrace(session);
        System.out.println("嵌套执行轨迹：\n" + trace.getFormattedHistory());

        Assertions.assertTrue(result.contains("测试"), "最终输出应包含子团队最后一个节点的产出");
    }

    /**
     * 测试：动态状态共享与博弈（模拟 Blackboard 行为）
     * 场景：多个专家对同一个“故障看板”进行信息增量补充。
     * 验证：后续 Agent 是否能基于前序 Agent 留在“看板（Context）”中的结构化信息进行决策。
     */
    @Test
    public void testBlackboardCollaborativeState() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 模拟黑板协议的“增量更新”意识
        ReActSystemPrompt expertPrompt = ReActSystemPrompt.builder()
                .instruction("你正在参与一个【黑板协议】协作。请阅读上下文中的[当前看板状态]，在你的回复中更新该状态，不要覆盖他人信息。")
                .build();

        Agent dbExpert = ReActAgent.of(chatModel).name("db_expert")
                .description("数据库诊断专家")
                .systemPrompt(expertPrompt).build();

        Agent netExpert = ReActAgent.of(chatModel).name("net_expert")
                .description("网络链路专家")
                .systemPrompt(expertPrompt).build();

        TeamAgent emergencyTeam = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.BLACKBOARD) // 开启黑板协议
                .agentAdd(dbExpert, netExpert)
                .maxTotalIterations(5)
                .build();

        AgentSession session = InMemoryAgentSession.of("session_blackboard_prod");
        String query = "系统响应变慢，请两位专家在看板上汇总排查进度。要求最终输出一个包含[DB状态]和[网络状态]的汇总列表。";

        String result = emergencyTeam.call(Prompt.of(query), session).getContent();

        // 验证是否实现了“信息聚合”而非“信息覆盖”
        Assertions.assertTrue(result.contains("DB") || result.contains("数据库"), "缺失数据库诊断");
        Assertions.assertTrue(result.contains("网络") || result.contains("Network"), "缺失网络诊断");
    }

    /**
     * 测试：基于 Market 协议的成本敏感调度
     * 场景：任务包含“简单总结”和“深度审计”。
     * 验证：Market 协议能否根据 Agent 的 description（元数据），将简单任务分给轻量 Agent，复杂任务分给重量 Agent。
     */
    @Test
    public void testCostEffectiveMarketRouting() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        Agent lightWorker = ReActAgent.of(chatModel).name("cheap_summarizer")
                .description("【低成本节点】擅长快速、简短的文本总结。").build();

        Agent heavyWorker = ReActAgent.of(chatModel).name("expensive_auditor")
                .description("【高成本节点】擅长深度的逻辑审计、合规性检查及复杂法律文档分析。").build();

        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.MARKET_BASED)
                .agentAdd(lightWorker, heavyWorker)
                .build();

        // 测试点 1：简单任务
        AgentSession s1 = InMemoryAgentSession.of("m1");
        team.call(Prompt.of("把这段话精简到 20 字以内：AI 技术正在深刻改变人类的生产力。"), s1);
        Assertions.assertEquals("cheap_summarizer", team.getTrace(s1).getRecords().get(0).getSource(), "简单任务未能路由到低成本节点");

        // 测试点 2：复杂任务
        AgentSession s2 = InMemoryAgentSession.of("m2");
        team.call(Prompt.of("请审计这份合同中的潜在法律风险，并对比 2026 年最新的跨境贸易法案。"), s2);
        Assertions.assertEquals("expensive_auditor", team.getTrace(s2).getRecords().get(0).getSource(), "复杂任务未能路由到专业高成本节点");
    }
}