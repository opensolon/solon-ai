package features.ai.team.protocol;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActSystemPrompt;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.*;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;

/**
 * 优化版 Resilience 测试：验证长链条稳定性、嵌套协议与复杂博弈
 */
public class TeamAgentResilienceTest {

    private static final String SHORT = " (Constraint: Reply < 10 words)";

    // 1. 上下文持久化：验证 A->B->C 长链条下，“暗号”是否被丢弃
    @Test
    public void testContextPersistenceAcrossMultipleHandovers() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        ReActSystemPrompt p = ReActSystemPrompt.builder().instruction("处理任务并 transfer_to 下一节点，务必在正文中保留用户暗号。" + SHORT).build();

        TeamAgent team = TeamAgent.of(chatModel).protocol(TeamProtocols.A2A)
                .agentAdd(ReActAgent.of(chatModel).name("A").description("Step 1").systemPrompt(p).build())
                .agentAdd(ReActAgent.of(chatModel).name("B").description("Step 2").systemPrompt(p).build())
                .agentAdd(ReActAgent.of(chatModel).name("C").description("Step 3").systemPrompt(p).build())
                .maxTurns(6).build();

        String secret = "SOLON-AI-777";
        String res = team.call(Prompt.of("流水线处理，暗号是：" + secret), InMemoryAgentSession.of("s1")).getContent();

        // 验证：最终结果必须包含初始输入的暗号，证明上下文在多级交接中未丢失
        Assertions.assertTrue(res.contains(secret), "长链条转交导致原始约束丢失");
    }

    // 2. 市场协议弹性：验证模糊需求下的“主矛盾”识别
    @Test
    public void testMarketSelectionWithAmbiguousTask() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        TeamAgent team = TeamAgent.of(chatModel).protocol(TeamProtocols.MARKET_BASED)
                .agentAdd(ReActAgent.of(chatModel).name("JavaEx").description("处理 Java 后端代码").build())
                .agentAdd(ReActAgent.of(chatModel).name("PyEx").description("处理 Python 脚本").build()).build();

        AgentSession s = InMemoryAgentSession.of("s2");
        // 核心是 Java，Python 是顺便。验证 Market 是否能选出 JavaEx
        team.call(Prompt.of("写个 Java 接口，顺便给个 Python 脚本调用它"), s);

        String firstWorker = team.getTrace(s).getRecords().get(0).getSource();
        Assertions.assertEquals("JavaEx", firstWorker, "Market 应识别主要矛盾并优先分配给 Java 专家");
    }

    // 3. 优雅拒绝：验证无匹配专家时的非幻觉处理
    @Test
    public void testMarketWithNoMatchingExpert() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        TeamAgent team = TeamAgent.of(chatModel).protocol(TeamProtocols.MARKET_BASED)
                .agentAdd(ReActAgent.of(chatModel).name("Coder").description("只懂代码")
                        .systemPrompt(ReActSystemPrompt.builder().instruction("非技术请求回复'NO'。" + SHORT).build()).build()).build();

        String res = team.call(Prompt.of("怎么做烤鸭？"), InMemoryAgentSession.of("s3")).getContent();
        Assertions.assertTrue(res.contains("NO") || res.contains("专业"), "当无匹配专家时，应触发优雅拒绝而非胡编乱造");
    }

    // 4. 协议嵌套：验证 Hierarchical 嵌套 Sequential 的分层架构
    @Test
    @DisplayName("嵌套协议：主管调度流水线子团队")
    public void testNestedProtocol() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        // 子团队：流水线
        TeamAgent subTeam = TeamAgent.of(chatModel).name("SubTeam").protocol(TeamProtocols.SEQUENTIAL)
                .agentAdd(ReActAgent.of(chatModel).name("Dev").description("写代码").build())
                .agentAdd(ReActAgent.of(chatModel).name("Tester").description("负责输出测试结果")
                        .systemPrompt(ReActSystemPrompt.builder()
                                .instruction("你只需输出：'测试结论: PASS'。不要输出其他解释。")
                                .build())
                        .build()).build();

        // 主团队：主管模式
        TeamAgent mainTeam = TeamAgent.of(chatModel).name("MainTeam")
                .agentAdd(ReActAgent.of(chatModel).name("Manager").description("分配任务").build())
                .agentAdd(subTeam).build();

        AgentSession session = InMemoryAgentSession.of("s4");
        String result = mainTeam.call(Prompt.of("让研发组完成开发和测试"), session).getContent();
        TeamTrace trace = mainTeam.getTrace(session);

        System.out.println("=====最终结果=====");
        System.out.println(result);
        System.out.println("=====trace=====");
        System.out.println(ONode.serialize(trace));

        Assertions.assertTrue(result.contains("PASS") || result.contains("完成"), "主团队应能透传指令至子团队并获取最终流水线结果");
    }

    // 5. 黑板协议：验证信息增量聚合（不覆盖）
    @Test
    @DisplayName("黑板协议：信息聚合验证")
    public void testBlackboardCollaborativeState() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        ReActSystemPrompt p = ReActSystemPrompt.builder().instruction("在[看板]追加你的诊断信息，不要删除已有内容。" + SHORT).build();

        TeamAgent team = TeamAgent.of(chatModel).protocol(TeamProtocols.BLACKBOARD)
                .agentAdd(ReActAgent.of(chatModel).name("DB").description("查数据库").systemPrompt(p).build())
                .agentAdd(ReActAgent.of(chatModel).name("Net").description("查网络").systemPrompt(p).build())
                .maxTurns(4).build();

        AgentSession session = InMemoryAgentSession.of("s5");
        String result = team.call(Prompt.of("汇总系统变慢的原因"), session).getContent();
        TeamTrace trace = team.getTrace(session);

        System.out.println("=====最终结果=====");
        System.out.println(result);
        System.out.println("=====trace=====");
        System.out.println(ONode.serialize(trace));


        // 验证：最终回复应包含两个维度的信息，证明是聚合而非覆盖
        Assertions.assertTrue(
                result.contains("数据库") || result.contains("DB"),
                "应包含数据库维度诊断"
        );
        Assertions.assertTrue(
                result.contains("网络") || result.contains("Net"),
                "应包含网络维度诊断"
        );
    }

    // 6. 成本敏感路由：验证任务轻重分发
    @Test
    public void testCostEffectiveMarketRouting() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        TeamAgent team = TeamAgent.of(chatModel).protocol(TeamProtocols.MARKET_BASED)
                .agentAdd(ReActAgent.of(chatModel).name("Cheap").description("处理简单总结").build())
                .agentAdd(ReActAgent.of(chatModel).name("Heavy").description("处理深度审计").build()).build();

        AgentSession s1 = InMemoryAgentSession.of("m1");
        team.call(Prompt.of("总结这句话"), s1);
        Assertions.assertEquals("Cheap", team.getTrace(s1).getRecords().get(0).getSource());

        AgentSession s2 = InMemoryAgentSession.of("m2");
        team.call(Prompt.of("深度审计法律合同并对比 2026 法规"), s2);
        Assertions.assertEquals("Heavy", team.getTrace(s2).getRecords().get(0).getSource());
    }
}