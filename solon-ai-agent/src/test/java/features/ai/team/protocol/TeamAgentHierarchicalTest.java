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

        Agent searcher = ReActAgent.of(chatModel).name("searcher").role("数据提供方").instruction("你只负责提供一个随机数字。严禁将数字翻倍。").build();
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


    // 3. 自愈/补位：验证主管在专家“拒载”时的调度
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

    // 4. 消息中继：验证主管在 HIERARCHICAL 协议下跨 Agent 传递上下文的能力
    @Test
    @DisplayName("层级协议：验证主管的消息中继能力")
    public void testSupervisorMessageRelay() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 专家 A：生成随机密钥
        Agent secretGenerator = ReActAgent.of(chatModel).name("Generator")
                .role("密钥生成员")
                .instruction("随机生成一个 4 位数字作为临时密钥，并明确告知主管。" + LIMIT)
                .build();

        // 专家 B：对密钥进行处理
        Agent formatter = ReActAgent.of(chatModel).name("Formatter")
                .role("格式化工具")
                .instruction("接收主管给出的数字密钥，将其用方括号包围（如 [1234]）。" + LIMIT)
                .build();

        // 使用默认的 HIERARCHICAL 协议
        TeamAgent team = TeamAgent.of(chatModel).name("RelayTeam")
                .agentAdd(secretGenerator, formatter)
                .role("协调员")
                .instruction("流程：先让 Generator 生成密钥，然后必须将该密钥告知 Formatter 进行处理。")
                .build();

        AgentSession session = InMemoryAgentSession.of("s5");
        String result = team.prompt(Prompt.of("生成并格式化一个密钥")).session(session).call().getContent();

        TeamTrace trace = team.getTrace(session);
        System.out.println("中继路径: " + trace.getFormattedHistory());
        System.out.println("最终结果: " + result);

        // 验证 1：两个专家都参与了
        boolean usedBoth = trace.getRecords().stream().anyMatch(r -> "Generator".equals(r.getSource()))
                && trace.getRecords().stream().anyMatch(r -> "Formatter".equals(r.getSource()));
        Assertions.assertTrue(usedBoth, "主管应完成消息中继指派");

        // 验证 2：结果符合 Formatter 的处理逻辑（证明主管成功传递了 A 的输出给 B）
        Assertions.assertTrue(result.matches(".*\\[\\d{4}\\].*"), "结果应包含方括号包围的 4 位数字");
    }

    // 5. 边界测试：验证最大迭代轮次拦截，防止死循环
    @Test
    @DisplayName("边界测试：验证 maxTurns 强制拦截")
    public void testMaxTurnsLimit() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 两个互相推诿的 Agent
        Agent a = ReActAgent.of(chatModel).name("AgentA")
                .role("推诿者")
                .instruction("无论主管说什么，都回复：'我不确定，请询问 AgentB'。").build();
        Agent b = ReActAgent.of(chatModel).name("AgentB")
                .role("推诿者")
                .instruction("无论主管说什么，都回复：'我不确定，请询问 AgentA'。").build();

        // 限制 maxTurns 为 3 轮
        int maxTurns = 3;
        TeamAgent team = TeamAgent.of(chatModel).agentAdd(a, b).maxTurns(maxTurns).build();
        AgentSession session = InMemoryAgentSession.of("s6");

        // 执行任务
        team.prompt(Prompt.of("谁是世界上最聪明的人？")).session(session).call();

        TeamTrace trace = team.getTrace(session);
        System.out.println("迭代总轮次: " + trace.getTurnCount());

        // 验证：实际轮次不能超过 maxTurns
        Assertions.assertTrue(trace.getTurnCount() <= maxTurns, "TeamAgent 必须在达到 maxTurns 时强制停止");
    }
}