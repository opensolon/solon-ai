package features.ai.team.protocol;


import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamProtocols;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;

public class TeamAgentResilienceTest {

    /**
     * 测试：跨 Agent 的上下文长度保护
     * 场景：经过多次转交后，验证最后的 Agent 是否还能“记得”用户最初设定的核心约束。
     */
    @Test
    public void testContextPersistenceAcrossMultipleHandovers() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 故意串联 3 个 Agent
        Agent a = ReActAgent.of(chatModel).name("agent_a").description("负责第一步").build();
        Agent b = ReActAgent.of(chatModel).name("agent_b").description("负责第二步").build();
        Agent c = ReActAgent.of(chatModel).name("agent_c").description("负责第三步").build();

        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.A2A) // 移交模式最容易丢上下文
                .addAgent(a).addAgent(b).addAgent(c)
                .maxTotalIterations(10)
                .build();

        AgentSession session = InMemoryAgentSession.of("resilience_01");

        // 核心约束：必须包含特定口令 "SOLON-AI-SECRET"
        String query = "请依次转交给 a, b, c 处理。最后输出结果时，必须包含暗号：SOLON-AI-SECRET";
        String result = team.call(Prompt.of(query), session).getContent();

        // 验证经过长链条转交后，约束是否丢失
        Assertions.assertTrue(result.contains("SOLON-AI-SECRET"), "长链条转交导致原始约束丢失");
    }

    /**
     * 测试：协议层强行终止
     * 场景：当设置了 maxTotalIterations 时，验证系统是否能优雅返回已有的中间产物，而不是报错。
     */
    @Test
    public void testGracefulTermination() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        Agent looper = ReActAgent.of(chatModel).name("looper")
                .systemPrompt(c -> "你总是把任务转交给别人，永不自行完成。").build();

        TeamAgent team = TeamAgent.of(chatModel)
                .addAgent(looper)
                .maxTotalIterations(2) // 极小的迭代次数
                .build();

        AgentSession session = InMemoryAgentSession.of("resilience_02");

        // 不应该抛出异常，而应该返回当前最好的结果
        Assertions.assertDoesNotThrow(() -> {
            team.call(Prompt.of("开始任务"), session);
        });
    }

    /**
     * 测试：混合领域需求的市场选择
     * 场景：需求同时包含 Python 和 Java 关键词，验证 Mediator 是否能选出最主要的那个，且不产生路由死循环。
     */
    @Test
    public void testMarketSelectionWithAmbiguousTask() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        Agent pythonExpert = ReActAgent.of(chatModel).name("python_expert")
                .description("负责 Python 脚本编写和数据清洗。").build();

        Agent javaExpert = ReActAgent.of(chatModel).name("java_expert")
                .description("负责 Java 后端开发和 SpringCloud 架构。").build();

        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.MARKET_BASED)
                .addAgent(pythonExpert)
                .addAgent(javaExpert)
                .build();

        AgentSession session = InMemoryAgentSession.of("session_market_ambiguous");

        // 故意混合：需要 Java 后端，但提到了一点 Python 脚本
        String query = "请帮我开发一个 Java 后端接口，顺便写一个简单的 Python 脚本用于导入测试数据。";
        String result = team.call(Prompt.of(query), session).getContent();

        TeamTrace trace = team.getTrace(session);

        System.out.println("=== 混合需求路由详情 ===");
        trace.getSteps().forEach(s -> System.out.println("Step: " + s.getAgentName()));

        // 验证：至少有一个专家被选中执行了任务
        Assertions.assertTrue(trace.getStepCount() > 0);
        Assertions.assertFalse(result.isEmpty());
    }

    /**
     * 测试：无匹配能力的优雅处理
     * 场景：人才市场中没有能处理该任务的专家（如厨师任务发给了程序员团队）。
     */
    @Test
    public void testMarketWithNoMatchingExpert() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        Agent coder = ReActAgent.of(chatModel).name("coder")
                .description("只懂编程和代码实现。").build();

        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.MARKET_BASED)
                .addAgent(coder)
                .build();

        AgentSession session = InMemoryAgentSession.of("session_market_none");

        // 发送一个完全不相关的需求
        String query = "如何做一顿正宗的北京烤鸭？";
        String result = team.call(Prompt.of(query), session).getContent();

        System.out.println("=== 无匹配任务结果 ===\n" + result);

        TeamTrace trace = team.getTrace(session);

        // 验证：即使没有匹配，Mediator 也应该给出回复（通常是告知无法处理），而不是崩溃
        Assertions.assertTrue(trace.getStepCount() >= 1);
    }
}