package features.ai.team.protocol;

import demo.ai.llm.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
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
import java.util.List;
import java.util.stream.Collectors;

/**
 * 优化版 Market 策略测试：低成本验证“语义路由”与“技能过滤”
 */
public class TeamAgentMarketTest {

    private static final String SHORT_LIMIT = " Constraint: Reply < 15 words.";

    private void logTrace(String title, TeamTrace trace) {
        System.out.println(">> [" + title + "] Selected: " + trace.getLastAgentName());
        System.out.println(">> Path: " + trace.getRecords().stream()
                .map(r -> r.getSource()).collect(Collectors.joining(" -> ")));
    }

    // 1. 基础语义匹配：验证 Python vs Java 路由
    @Test
    @DisplayName("语义匹配：区分编程语言专家")
    public void testMarketSelectionLogic() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        Agent py = ReActAgent.of(chatModel).name("py_coder").description("Python 脚本专家").build();
        Agent jv = ReActAgent.of(chatModel).name("jv_coder").description("Java 并发架构专家").build();

        TeamAgent team = TeamAgent.of(chatModel).protocol(TeamProtocols.MARKET_BASED).agentAdd(py, jv).build();
        AgentSession session = InMemoryAgentSession.of("m1");

        String result = team.call(Prompt.of("请帮我写一段 Python 代码，合并两个数据字典 a={'x':1} 和 b={'y':2}"), session).getContent();

        System.out.println("=====最终输出=====");
        System.out.println(result);

        logTrace("Python Task", team.getTrace(session));
        Assertions.assertEquals("py_coder", team.getTrace(session).getLastAgentName());
    }

    // 2. 跨界路由与兜底：验证跨端 vs 硬件
    @Test
    @DisplayName("跨界路由：全栈专家匹配与无人接单兜底")
    public void testMarketProductionComplexity() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        Agent ios = ReActAgent.of(chatModel).name("ios").description("Swift 原生开发").build();
        Agent flutter = ReActAgent.of(chatModel).name("flutter").description("跨端开发专家").build();

        TeamAgent team = TeamAgent.of(chatModel).protocol(TeamProtocols.MARKET_BASED).agentAdd(ios, flutter).build();

        // 场景 A：匹配跨端
        AgentSession s1 = InMemoryAgentSession.of("m2_a");
        String result = team.call(Prompt.of("低成本开发双端预览版"), s1).getContent();

        System.out.println("=====最终输出=====");
        System.out.println(result);

        List<String> history = team.getTrace(s1).getRecords().stream()
                .map(TeamTrace.TeamRecord::getSource)
                .collect(Collectors.toList());

        // 只要 flutter 参与过，说明路由匹配正确
        Assertions.assertTrue(history.contains("flutter"), "市场模式应优先匹配 Flutter 专家");

        // 场景 B：无人接单（STM32 硬件）
        AgentSession s2 = InMemoryAgentSession.of("m2_b");
        String res = team.call(Prompt.of("写一个 STM32 芯片驱动"), s2).getContent();
        System.out.println("无人接单回复: " + res);
        Assertions.assertFalse(res.isEmpty());
    }

    // 3. 硬技能过滤：验证 Profile 优先级高于 Description
    @Test
    @DisplayName("硬技能过滤：区分 JDK 版本")
    public void testMarketWithHardSkillConstraints() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 描述相似，技能标签不同
        Agent old = ReActAgent.of(chatModel).name("legacy").description("Java 开发者")
                .profile(p -> p.capabilityAdd("JDK8")).build();
        Agent modern = ReActAgent.of(chatModel).name("modern").description("Java 开发者")
                .profile(p -> p.capabilityAdd("JDK21")).build();

        TeamAgent team = TeamAgent.of(chatModel).protocol(TeamProtocols.MARKET_BASED).agentAdd(old, modern).build();
        AgentSession s = InMemoryAgentSession.of("m3");

        team.call(Prompt.of("使用 JDK21 虚拟线程"), s);

        logTrace("Skill Check", team.getTrace(s));
        Assertions.assertEquals("modern", team.getTrace(s).getLastAgentName());
    }

    // 4. 多阶段协作：验证 Mediator 的连续指派能力
    @Test
    @DisplayName("多阶段指派：设计 -> 编码")
    public void testMarketMultiStepProcurement() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        Agent designer = ReActAgent.of(chatModel).name("designer").description("UI 设计师")
                .systemPrompt(p->p.instruction("描述登录框设计。"+SHORT_LIMIT)).build();
        Agent coder = ReActAgent.of(chatModel).name("coder").description("前端开发员")
                .systemPrompt(p->p.instruction("根据设计出 HTML。"+SHORT_LIMIT)).build();

        TeamAgent team = TeamAgent.of(chatModel).protocol(TeamProtocols.MARKET_BASED).agentAdd(designer, coder).maxTurns(5).build();
        AgentSession s = InMemoryAgentSession.of("m4");

        team.call(Prompt.of("先设计登录框，然后写出 HTML"), s);

        List<String> executors = team.getTrace(s).getRecords().stream()
                .filter(r -> r.isAgent()).map(r -> r.getSource()).distinct().collect(Collectors.toList());

        System.out.println("市场协作路径: " + executors);
        Assertions.assertTrue(executors.contains("designer") && executors.contains("coder"));
    }
}