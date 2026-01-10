package features.ai.team.protocol;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActSystemPrompt;
import org.noear.solon.ai.agent.react.ReActSystemPromptCn; // 引入中文增强模板
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamProtocols;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;

import java.util.stream.Collectors;

/**
 * Blackboard 策略测试：基于共享状态的补位协作
 */
public class TeamAgentBlackboardTest {

    @Test
    public void testBlackboardLogic() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 优化：使用 ReActSystemPromptCn 赋予 DB 设计师更明确的“黑板贡献”职责
        Agent databaseDesigner = ReActAgent.of(chatModel)
                .name("db_designer")
                .description("负责数据库表结构设计，输出 SQL 代码。")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("你是一个资深数据库架构师")
                        .instruction("### 职责\n" +
                                "1. 仅负责数据库表结构设计（SQL 代码）。\n" +
                                "2. 检查黑板，若尚无表结构，请基于需求设计 2 张核心表。\n" +
                                "3. 直接输出 SQL 代码块，不要多余解释。")
                        .build())
                .build();

        // 2. 优化：赋予 API 设计师“黑板观察者”职责，确保其基于已有 SQL 设计接口
        Agent apiDesigner = ReActAgent.of(chatModel)
                .name("api_designer")
                .description("负责 RESTful API 接口协议设计。")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("你是一个 API 接口专家")
                        .instruction("### 职责\n" +
                                "1. 仅负责 RESTful API 接口协议设计。\n" +
                                "2. **必须参考黑板上已有的数据库表结构**来设计 2 个核心接口。\n" +
                                "3. 确保接口字段与数据库字段一致。")
                        .build())
                .build();

        // 组建团队
        TeamAgent team = TeamAgent.of(chatModel)
                .name("blackboard_team")
                .protocol(TeamProtocols.BLACKBOARD)
                .addAgent(databaseDesigner)
                .addAgent(apiDesigner)
                .maxTotalIterations(5)
                .build();

        //
        System.out.println("--- Blackboard Team Graph ---\n" + team.getGraph().toYaml());

        AgentSession session = InMemoryAgentSession.of("session_blackboard_01");
        String query = "请为我的电商系统设计用户模块的数据库和配套接口。";
        String result = team.call(Prompt.of(query), session).getContent();

        System.out.println("=== 执行结果 ===\n" + result);

        TeamTrace trace = team.getTrace(session);
        Assertions.assertNotNull(trace);
        Assertions.assertTrue(trace.getStepCount() > 0);
    }

    @Test
    public void testBlackboardWithReviewerLoop() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 优化：赋予审核者“循环控制器”的提示词
        Agent reviewer = ReActAgent.of(chatModel)
                .name("reviewer")
                .description("负责审核设计规范。")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("你是一个严谨的架构审核员")
                        .instruction("### 审核规则\n" +
                                "1. 检查黑板上的 SQL。\n" +
                                "2. **必须包含 'created_at' 字段**，否则指出错误并要求 db_designer 修正。\n" +
                                "3. 若审核通过，请仅回复关键字 'FINISH'。")
                        .build())
                .build();

        Agent dbDesigner = ReActAgent.of(chatModel)
                .name("db_designer")
                .description("数据库设计专家")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("数据库开发者")
                        .instruction("根据黑板上审核员给出的反馈修正 SQL 代码。")
                        .build())
                .build();

        TeamAgent team = TeamAgent.of(chatModel)
                .name("loop_test_team")
                .protocol(TeamProtocols.BLACKBOARD)
                .addAgent(dbDesigner)
                .addAgent(reviewer)
                .maxTotalIterations(6)
                .build();

        AgentSession session = InMemoryAgentSession.of("session_loop_01");
        String result = team.call(Prompt.of("设计一张用户表，必须满足审核员的要求"), session).getContent();

        TeamTrace trace = team.getTrace(session);
        long dbDesignCount = trace.getSteps().stream()
                .filter(s -> s.getAgentName().equals("db_designer")).count();

        Assertions.assertTrue(dbDesignCount >= 1);
    }

    @Test
    public void testBlackboardDataConsistency() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        String randomTableName = "table_" + System.currentTimeMillis();

        // 优化：利用 Markdown 格式强化 Agent 对特定动态数据的记忆
        Agent dbDesigner = ReActAgent.of(chatModel)
                .name("db_designer")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("数据库开发者")
                        .instruction("### 强制约束\n" +
                                "- 请务必创建表，且表名**必须**使用: `" + randomTableName + "`")
                        .build())
                .build();

        Agent apiDesigner = ReActAgent.of(chatModel)
                .name("api_designer")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("API 开发者")
                        .instruction("### 依赖要求\n" +
                                "- 检查黑板上存在的表名。\n" +
                                "- **严禁自创表名**，必须基于黑板上的表名生成接口。")
                        .build())
                .build();

        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.BLACKBOARD)
                .addAgent(dbDesigner)
                .addAgent(apiDesigner)
                .build();

        AgentSession session = InMemoryAgentSession.of("consistency_test");
        String result = team.call(Prompt.of("请完成数据库和接口设计"), session).getContent();

        Assertions.assertTrue(result.contains(randomTableName));
    }

    @Test
    public void testLoopPreventionInBlackboard() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 优化：明确 Agent 的补位边界，防止互相推诿
        Agent a = ReActAgent.of(chatModel).name("agent_a")
                .systemPrompt(ReActSystemPrompt.builder().role("专家 A").instruction("你认为此任务该由专家 B 完成。").build()).build();
        Agent b = ReActAgent.of(chatModel).name("agent_b")
                .systemPrompt(ReActSystemPrompt.builder().role("专家 B").instruction("你认为此任务该由专家 A 完成。").build()).build();

        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.BLACKBOARD)
                .addAgent(a).addAgent(b)
                .maxTotalIterations(4)
                .build();

        AgentSession session = InMemoryAgentSession.of("loop_test");
        team.call(Prompt.of("开始工作"), session);

        TeamTrace trace = team.getTrace(session);
        Assertions.assertTrue(trace.getStepCount() <= 4);
    }
}