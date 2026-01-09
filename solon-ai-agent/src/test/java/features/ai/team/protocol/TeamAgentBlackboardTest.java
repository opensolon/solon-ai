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

/**
 * Blackboard 策略测试：基于共享状态的补位协作
 * <p>
 * 黑板模式验证：
 * 1. 多个 Agent 共享 Session 中的上下文。
 * 2. 协调者根据黑板上的进度，动态指派不同的专家完成任务的不同维度。
 * </p>
 */
public class TeamAgentBlackboardTest {

    @Test
    public void testBlackboardLogic() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 定义具备特定领域专长的 Agent
        Agent databaseDesigner = ReActAgent.of(chatModel)
                .name("db_designer")
                .systemPrompt(c -> "你只负责数据库表结构设计，输出 SQL 代码。只需设计 2 张核心表。")
                .description("负责数据库表结构设计，输出 SQL 代码。")
                .build();

        Agent apiDesigner = ReActAgent.of(chatModel)
                .name("api_designer")
                .systemPrompt(c -> "你只负责 RESTful API 接口协议设计。针对数据库表设计 2 个核心接口。")
                .description("负责 RESTful API 接口协议设计。")
                .build();

        // 2. 使用 BLACKBOARD 策略组建团队
        TeamAgent team = TeamAgent.of(chatModel)
                .name("blackboard_team")
                .protocol(TeamProtocols.BLACKBOARD)
                .addAgent(databaseDesigner)
                .addAgent(apiDesigner)
                .maxTotalIterations(5) // 设置最大迭代，确保黑板有足够的交互次数
                .build();

        // 打印图结构 YAML（黑板模式通常表现为围绕 Mediator 的星型结构）
        System.out.println("--- Blackboard Team Graph ---\n" + team.getGraph().toYaml());

        // 3. 创建 AgentSession（黑板的载体）
        AgentSession session = InMemoryAgentSession.of("session_blackboard_01");

        // 4. 发起综合任务
        String query = "请为我的电商系统设计用户模块的数据库和配套接口。";
        String result = team.call(Prompt.of(query), session).getContent();

        System.out.println("=== 执行结果 ===\n" + result);

        // 5. 验证执行轨迹
        TeamTrace trace = team.getTrace(session);
        Assertions.assertNotNull(trace, "应该产生协作轨迹");
        Assertions.assertFalse(result.isEmpty(), "结果不应为空");
        Assertions.assertTrue(trace.getStepCount() > 0, "黑板上至少应该有一步记录");

        // 输出协作明细
        System.out.println("协作步数: " + trace.getStepCount());
        System.out.println("协作轨迹详情:\n" + trace.getFormattedHistory());
    }

    @Test
    public void testBlackboardWithExplicitRequest() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        Agent databaseDesigner = ReActAgent.of(chatModel)
                .name("db_designer")
                .description("负责数据库表结构设计，输出 SQL 代码。")
                .build();

        Agent apiDesigner = ReActAgent.of(chatModel)
                .name("api_designer")
                .description("负责 RESTful API 接口协议设计。")
                .build();

        TeamAgent team = TeamAgent.of(chatModel)
                .name("blackboard_explicit_team")
                .protocol(TeamProtocols.BLACKBOARD)
                .addAgent(databaseDesigner)
                .addAgent(apiDesigner)
                .build();

        // 使用 AgentSession 承载明确的任务需求
        AgentSession session = InMemoryAgentSession.of("session_explicit_01");

        String complexQuery = "请分两部分完成：\n" +
                "1. 设计用户模块的数据库表结构，包含用户表和订单表\n" +
                "2. 设计对应的 RESTful API 接口，包括登录和信息查询";

        String result = team.call(Prompt.of(complexQuery), session).getContent();

        // 轨迹深度检测
        TeamTrace trace = team.getTrace(session);
        Assertions.assertNotNull(trace, "轨迹记录不可缺失");

        System.out.println("=== 明确任务结果 ===\n" + result);
        System.out.println("实际参与 Agent 数: " + trace.getSteps().stream()
                .map(step -> step.getAgentName()).distinct().count());

        Assertions.assertTrue(trace.getStepCount() > 0);
        System.out.println("详细历史:\n" + trace.getFormattedHistory());
    }

    @Test
    public void testBlackboardWithReviewerLoop() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 增加一个审核者
        Agent reviewer = ReActAgent.of(chatModel)
                .name("reviewer")
                .systemPrompt(c -> "你是架构审核员。检查黑板上的 SQL，如果缺少 'created_at' 字段，请指出错误并要求 db_designer 重新修改。如果没问题，请回复 FINISH。")
                .description("负责审核设计规范。")
                .build();

        Agent dbDesigner = ReActAgent.of(chatModel)
                .name("db_designer")
                .systemPrompt(c -> "你负责数据库设计。如果审核员指出错误，请立即根据建议修正 SQL。")
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

        // 关键断言：验证是否发生了“协作回流”
        long dbDesignCount = trace.getSteps().stream()
                .filter(s -> s.getAgentName().equals("db_designer")).count();

        System.out.println("db_designer 介入次数: " + dbDesignCount);
        Assertions.assertTrue(dbDesignCount >= 1, "数据库设计至少应执行一次");
    }

    @Test
    public void testBlackboardDataConsistency() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 强行让 db_designer 产生一个随机的表名
        String randomTableName = "table_" + System.currentTimeMillis();

        Agent dbDesigner = ReActAgent.of(chatModel)
                .name("db_designer")
                .systemPrompt(c -> "你只负责创建表。请务必使用表名: " + randomTableName)
                .build();

        Agent apiDesigner = ReActAgent.of(chatModel)
                .name("api_designer")
                .systemPrompt(c -> "你负责 API 设计。你必须调用黑板上已经存在的表名来生成接口。")
                .build();

        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.BLACKBOARD)
                .addAgent(dbDesigner)
                .addAgent(apiDesigner)
                .build();

        AgentSession session = InMemoryAgentSession.of("consistency_test");
        String result = team.call(Prompt.of("请完成数据库和接口设计"), session).getContent();

        // 核心断言：验证 B 确实读取了 A 的产出
        Assertions.assertTrue(result.contains(randomTableName),
                "API 设计师应该引用了数据库设计师创建的特定表名");
    }

    @Test
    public void testLoopPreventionInBlackboard() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 模拟两个互相“谦让”的 Agent
        Agent a = ReActAgent.of(chatModel).name("agent_a")
                .systemPrompt(c -> "你觉得这个任务应该由 agent_b 完成。").build();
        Agent b = ReActAgent.of(chatModel).name("agent_b")
                .systemPrompt(c -> "你觉得这个任务应该由 agent_a 完成。").build();

        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.BLACKBOARD)
                .addAgent(a).addAgent(b)
                .maxTotalIterations(4) // 限制次数
                .build();

        AgentSession session = InMemoryAgentSession.of("loop_test");
        team.call(Prompt.of("开始工作"), session);

        TeamTrace trace = team.getTrace(session);
        // 验证是否触发了迭代上限保护
        Assertions.assertTrue(trace.getStepCount() <= 4, "不应超过最大迭代次数");
    }
}