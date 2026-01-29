package features.ai.team.protocol;

import demo.ai.llm.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamProtocols;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.annotation.Param;

/**
 * 优化版 Blackboard 协议测试：
 * 重点验证：状态涌现、冲突覆盖、死循环防御
 */
public class TeamAgentBlackboardTest {

    private static final String LIMIT = " (Constraint: Reply < 10 words)";

    // 极简黑板工具：仅用于 KV 操作，极大节省 Token
    public static class BoardTools {
        @ToolMapping(description = "写入黑板数据")
        public String write(@Param("k") String k, @Param("v") String v) {
            return String.format("{\"%s\":\"%s\"}", k, v);
        }
    }

    // 1. 验证“状态触发”：去中心化接力
    @Test
    @DisplayName("黑板核心：基于数据状态的自发协作")
    public void testBlackboardAutonomousFlow() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        MethodToolProvider tools = new MethodToolProvider(new BoardTools());

        // 后端：观察到没 api 就写 api
        Agent backend = ReActAgent.of(chatModel).name("Backend").defaultToolAdd(tools)
                .feedbackMode(false)
                .role("后端开发者")
                .instruction("检查看板。若无 key 为 'api' 的数据，必须调用 write(k='api', v='ok')。不要做多余的规划。" + LIMIT)
                .build();
        // 前端：观察到有 api 才写 ui
        Agent frontend = ReActAgent.of(chatModel).name("Frontend").defaultToolAdd(tools)
                .feedbackMode(false)
                .role("前端开发者")
                .instruction("若看板有'api'，调用 write(k='ui', v='done') 并回复 FINISH。" + LIMIT)
                .build();

        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.BLACKBOARD)
                .feedbackMode(false)
                .agentAdd(backend, frontend).maxTurns(5).build();

        AgentSession session = InMemoryAgentSession.of("s1");
        // 改为 .prompt().session().call() 风格
        team.prompt(Prompt.of("开始构建系统")).session(session).call();

        String board = team.getTrace(session).getProtocolDashboardSnapshot();
        System.out.println("Final Board: " + board);

        Assertions.assertTrue(board.contains("ok") && board.contains("done"), "黑板应聚合前后端两份数据");
    }

    // 2. 验证“冲突解决”：资深专家对状态的修正覆盖
    @Test
    @DisplayName("黑板冲突：高权限节点覆盖低权限节点状态")
    public void testBlackboardStateOverride() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        MethodToolProvider tools = new MethodToolProvider(new BoardTools());

        // 初级工：设定一个低分
        Agent junior = ReActAgent.of(chatModel).name("Junior").defaultToolAdd(tools)
                .feedbackMode(false)
                .role("初级评测员")
                .instruction("将'score'设为'60'。" + LIMIT).build();

        // 资深工：看到低分就修正为高分
        Agent senior = ReActAgent.of(chatModel).name("Senior").defaultToolAdd(tools)
                .feedbackMode(false)
                .role("资深专家")
                .instruction("作为资深专家，负责对评分进行最终核准和修正（尤其是当分数为60时）。若看板'score'为'60'，将其覆盖写为'99'。" + LIMIT).build();

        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.BLACKBOARD)
                .instruction("任务必须经过 Junior 评分和 Senior 核准两个阶段。只有当 Senior 完成核准后才能结束。") // 强制流程约束
                .feedbackMode(false)
                .agentAdd(junior, senior).maxTurns(4).build();

        AgentSession session = InMemoryAgentSession.of("s2");
        // 改为 .prompt().session().call() 风格
        String result = team.prompt(Prompt.of("请进行最终评分")).session(session).call().getContent();
        TeamTrace trace = team.getTrace(session);

        System.out.println("=====最终结果=====");
        System.out.println(result);
        System.out.println("=====trace=====");
        System.out.println(ONode.serialize(trace));

        String board = trace.getProtocolDashboardSnapshot();
        System.out.println("Board Status: " + board);

        Assertions.assertTrue(board.contains("score") && board.contains("99"), "资深专家应能成功覆盖黑板状态");
    }

    // 3. 验证“博弈与门禁”：不达标则不准退出
    @Test
    @DisplayName("黑板门禁：基于数据指标的博弈循环")
    public void testBlackboardGatekeeping() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        MethodToolProvider tools = new MethodToolProvider(new BoardTools());

        // 编写者：反复尝试
        Agent writer = ReActAgent.of(chatModel).name("Writer").defaultToolAdd(tools)
                .feedbackMode(false)
                .role("内容编写者")
                .instruction("写一个含'A'的词并写入'data'。" + LIMIT).build();

        // 审计者：不含'A'就报错，含'A'才通过
        Agent auditor = ReActAgent.of(chatModel).name("Auditor").defaultToolAdd(tools)
                .feedbackMode(false)
                .role("合规审计员")
                .instruction("若'data'不含'A'，写'status'为'FAIL'；若含'A'，写'status'为'PASS'并回复 FINISH。" + LIMIT).build();

        TeamAgent team = TeamAgent.of(chatModel).protocol(TeamProtocols.BLACKBOARD)
                .feedbackMode(false)
                .agentAdd(writer, auditor)
                .maxTurns(6)
                .build();

        AgentSession session = InMemoryAgentSession.of("s3");
        // 改为 .prompt().session().call() 风格
        String result = team.prompt(Prompt.of("提交合规文档")).session(session).call().getContent();
        TeamTrace trace = team.getTrace(session);

        System.out.println("=====最终结果=====");
        System.out.println(result);
        System.out.println("=====trace=====");
        System.out.println(ONode.serialize(trace));

        String board = trace.getProtocolDashboardSnapshot();
        System.out.println("Audit Result: " + board);

        Assertions.assertTrue(board.contains("PASS"), "黑板应记录最终通过的状态");
    }

    // 4. 验证“死循环预防”：框架强行截断
    @Test
    @DisplayName("黑板防御：防止 Agent 互相覆盖导致的逻辑死循环")
    public void testBlackboardLoopPrevention() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        MethodToolProvider tools = new MethodToolProvider(new BoardTools());

        Agent a = ReActAgent.of(chatModel).name("A").defaultToolAdd(tools)
                .role("竞争者A")
                .instruction("将'x'设为'1'。" + LIMIT).build();
        Agent b = ReActAgent.of(chatModel).name("B").defaultToolAdd(tools)
                .role("竞争者B")
                .instruction("将'x'设为'2'。" + LIMIT).build();

        TeamAgent team = TeamAgent.of(chatModel).protocol(TeamProtocols.BLACKBOARD)
                .agentAdd(a, b).maxTurns(3).build();

        AgentSession session = InMemoryAgentSession.of("s4");
        // 改为 .prompt().session().call() 风格
        Assertions.assertDoesNotThrow(() -> team.prompt(Prompt.of("开始竞争")).session(session).call());

        TeamTrace trace = team.getTrace(session);
        Assertions.assertTrue(trace.getRecordCount() <= 4, "框架应在 maxTurns 到达时强制停止博弈");
    }

    // 5. 生产级博弈：验证 [开发 -> 审计 -> 打回重写] 的闭环
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
                .protocol(TeamProtocols.BLACKBOARD)
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
}