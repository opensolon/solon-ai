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
                .addAgent(a, b, c)
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
                .addAgent(pythonExpert, javaExpert)
                .build();

        AgentSession session = InMemoryAgentSession.of("session_market_ambiguous");
        String query = "请帮我开发一个 Java 后端接口，顺便写一个简单的 Python 脚本用于导入测试数据。";
        String result = team.call(Prompt.of(query), session).getContent();

        TeamTrace trace = team.getTrace(session);
        Assertions.assertTrue(trace.getStepCount() > 0);
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
                .addAgent(coder)
                .build();

        AgentSession session = InMemoryAgentSession.of("session_market_none");
        String query = "如何做一顿正宗的北京烤鸭？";
        String result = team.call(Prompt.of(query), session).getContent();

        System.out.println("=== 优雅拒绝回复 ===\n" + result);

        TeamTrace trace = team.getTrace(session);
        Assertions.assertTrue(trace.getStepCount() >= 1);
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
                .addAgent(looper)
                .maxTotalIterations(2)
                .build();

        AgentSession session = InMemoryAgentSession.of("resilience_02");
        Assertions.assertDoesNotThrow(() -> team.call(Prompt.of("开始任务"), session));
    }
}