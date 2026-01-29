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
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 优化版 Supervisor 测试：验证指派逻辑与多轮博弈
 */
public class TeamAgentHierarchicalTest {

    private static final String LIMIT = " (Constraint: Reply < 10 words)";

    // 1. 基础决策：验证主管能否根据 Description 选对人
    @Test
    public void testSupervisorDecisionLogic() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        Agent collector = ReActAgent.of(chatModel).name("collector").role("数据采集员").instruction("收集原始水果名称").build();
        Agent analyzer = ReActAgent.of(chatModel).name("analyzer").role("色彩分析员").instruction("分析水果颜色").build();

        TeamAgent team = TeamAgent.of(chatModel).agentAdd(collector, analyzer).build();
        AgentSession session = InMemoryAgentSession.of("s1");

        // 改为链式调用
        String result = team.prompt(Prompt.of("香蕉是什么颜色的？")).session(session).call().getContent();

        TeamTrace trace = team.getTrace(session);
        System.out.println("Path: " + trace.getFormattedHistory());

        // 验证：主管至少指派了 analyzer 或 collector
        Assertions.assertTrue(trace.getRecordCount() > 0);
        Assertions.assertFalse(result.isEmpty());
    }

    // 2. 链式决策：验证 [A执行 -> 主管感知 -> 指派B] 的能力
    @Test
    public void testSupervisorChainDecision() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        Agent searcher = ReActAgent.of(chatModel).name("searcher").role("数据提供方").instruction("提供随机数字").build();
        Agent mapper = ReActAgent.of(chatModel).name("mapper").role("逻辑运算方").instruction("将数字翻倍").build();

        TeamAgent team = TeamAgent.of(chatModel).agentAdd(searcher, mapper).maxTurns(5).build();
        AgentSession session = InMemoryAgentSession.of("s2");

        // 改为链式调用
        team.prompt(Prompt.of("找一个数字并翻倍")).session(session).call();

        TeamTrace trace = team.getTrace(session);
        List<String> workers = trace.getRecords().stream()
                .map(r -> r.getSource()).filter(n -> !"supervisor".equals(n))
                .distinct().collect(Collectors.toList());

        // 验证：是否两个专家都参与了
        Assertions.assertTrue(workers.size() >= 2, "主管应先后指派 searcher 和 mapper");
    }

    // 3. 生产级博弈：验证 [开发 -> 审计 -> 打回重写] 的闭环
    @Test
    @DisplayName("博弈测试：代码审计与打回流")
    public void testFinalAnswerAndGame() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 开发者：第一次故意犯错（写 secret），审计后改正
        Agent coder = ReActAgent.of(chatModel).name("Coder")
                .role("开发工程师")
                .instruction("写一个登录函数。初次编写请硬编码 'key=123'；若被审计打回，则改为从 env 读取。" + LIMIT)
                .build();

        // 审计员：发现硬编码就打回
        Agent reviewer = ReActAgent.of(chatModel).name("Reviewer")
                .role("代码审计员")
                .instruction("检查硬编码。发现 '123' 则输出 'REJECT'；否则输出 'PASS'。" + LIMIT)
                .build();

        // 主管团队：使用 role 和 instruction 替代 systemPrompt
        TeamAgent team = TeamAgent.of(chatModel).name("DevTeam")
                .agentAdd(coder, reviewer).maxTurns(10)
                .role("项目主管")
                .instruction("协作流程：Coder -> Reviewer。若 Reviewer 返回 REJECT，必须再次指派 Coder 重写。\n完成时，只需要输出 Coder 的最终结果")
                .build();

        AgentSession session = InMemoryAgentSession.of("s3");
        // 改为链式调用
        String result = team.prompt(Prompt.of("实现安全登录")).session(session).call().getContent();

        TeamTrace trace = team.getTrace(session);
        long coderTurns = trace.getRecords().stream().filter(r -> "Coder".equals(r.getSource())).count();

        System.out.println("Coder 执行次数: " + coderTurns);
        System.out.println("最终结果: " + result);

        // 断言 1：发生了打回，Coder 至少执行了 2 次
        Assertions.assertTrue(coderTurns >= 2, "主管应在审计失败后重新指派开发");
        // 断言 2：最终结果已修复（不含硬编码 123）
        Assertions.assertFalse(result.contains("123"), "最终产物仍包含敏感信息");
    }

    // 4. 自愈/补位：验证主管在专家“拒载”时的调度
    @Test
    public void testSupervisorSelfHealing() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        Agent strategist = ReActAgent.of(chatModel).name("strategist").role("架构师").instruction("只给思路，不写代码").build();
        Agent implementer = ReActAgent.of(chatModel).name("implementer").role("程序员").instruction("只管写代码").build();

        TeamAgent team = TeamAgent.of(chatModel).agentAdd(strategist, implementer).build();
        AgentSession session = InMemoryAgentSession.of("s4");

        // 改为链式调用
        String result = team.prompt(Prompt.of("写个冒泡排序")).session(session).call().getContent();

        System.out.println("=====最终输出=====");
        System.out.println(result);

        TeamTrace trace = team.getTrace(session);
        boolean usedBoth = trace.getRecords().stream().anyMatch(r -> "strategist".equals(r.getSource()))
                && trace.getRecords().stream().anyMatch(r -> "implementer".equals(r.getSource()));

        Assertions.assertTrue(usedBoth, "主管应调度战略家思考并由程序员落地");
        Assertions.assertTrue(result.toLowerCase().contains("sort"));
    }
}