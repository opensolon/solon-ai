package features.ai.team.strategy;

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
                .promptProvider(c -> "你只负责数据库表结构设计，输出 SQL 代码。只需设计 2 张核心表。")
                .description("负责数据库表结构设计，输出 SQL 代码。")
                .build();

        Agent apiDesigner = ReActAgent.of(chatModel)
                .name("api_designer")
                .promptProvider(c -> "你只负责 RESTful API 接口协议设计。针对数据库表设计 2 个核心接口。")
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
}