package features.ai.team.protocol;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActSystemPrompt;
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
                .agentAdd(databaseDesigner)
                .agentAdd(apiDesigner)
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
                .agentAdd(dbDesigner)
                .agentAdd(reviewer)
                .maxTotalIterations(6)
                .build();

        AgentSession session = InMemoryAgentSession.of("session_loop_01");
        String result = team.call(Prompt.of("设计一张用户表，必须满足审核员的要求"), session).getContent();

        TeamTrace trace = team.getTrace(session);
        long dbDesignCount = trace.getSteps().stream()
                .filter(s -> s.getSource().equals("db_designer")).count();

        Assertions.assertTrue(dbDesignCount >= 1);
    }

    @Test
    public void testBlackboardDataConsistency() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        // 随机表名
        String randomTableName = "t_" + System.currentTimeMillis() % 10000;

        // 1. db_designer：不再提及内部方法名
        Agent dbDesigner = ReActAgent.of(chatModel)
                .name("db_designer")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("数据库开发者")
                        .instruction("### 核心任务\n" +
                                "1. 创建用户表，表名必须设为: `" + randomTableName + "`\n" +
                                "2. **关键动作**：请使用你的同步工具将该表名登记到协作黑板上。")
                        .build())
                .build();

        // 2. api_designer：依赖黑板
        Agent apiDesigner = ReActAgent.of(chatModel)
                .name("api_designer")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("API 开发者")
                        .instruction("### 核心限制\n" +
                                "1. 检查协作黑板，基于已登记的表名生成 RESTful 接口。\n" +
                                "2. 严禁自创表名。")
                        .build())
                .build();

        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.BLACKBOARD)
                .agentAdd(dbDesigner, apiDesigner)
                .maxTotalIterations(10)
                .build();

        AgentSession session = InMemoryAgentSession.of("session_" + randomTableName);

        // 执行：给 Supervisor 一个清晰的启动指令
        team.call(Prompt.of("请协作完成设计：先由数据库专家定义表名并登记，再由API专家设计接口"), session);

        // --- 3. 基于源码的精准断言 ---
        TeamTrace trace = team.getTrace(session);

        // 获取黑板数据的 JSON 快照
        String blackboardSnapshot = trace.getProtocolDashboardSnapshot();
        System.out.println(">>> 协作黑板快照: " + blackboardSnapshot);

        // 获取所有专家的产出文本
        String allExpertContent = trace.getSteps().stream()
                .filter(TeamTrace.TeamStep::isAgent)
                .map(TeamTrace.TeamStep::getContent)
                .collect(Collectors.joining(" "));

        // 验证逻辑：表名要么在黑板数据里，要么在专家的最终陈述里
        boolean isFoundInBlackboard = blackboardSnapshot.contains(randomTableName);
        boolean isFoundInContent = allExpertContent.contains(randomTableName);

        System.out.println("验证结果：[黑板存在: " + isFoundInBlackboard + "], [内容存在: " + isFoundInContent + "]");

        Assertions.assertTrue(isFoundInBlackboard || isFoundInContent,
                "错误：表名 [" + randomTableName + "] 彻底丢失，协作链路断裂");
    }

    @Test
    public void testLoopPreventionInBlackboard() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 定义互相推诿的专家
        Agent a = ReActAgent.of(chatModel).name("agent_a")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("专家 A")
                        .instruction("你非常懒，坚持认为任何任务都该由专家 B 完成。不要自己尝试。")
                        .build()).build();

        Agent b = ReActAgent.of(chatModel).name("agent_b")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("专家 B")
                        .instruction("你非常懒，坚持认为任何任务都该由专家 A 完成。不要自己尝试。")
                        .build()).build();

        // 2. 构建团队，设置严格的团队迭代上限
        int maxTeamRounds = 3;
        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.BLACKBOARD)
                .agentAdd(a, b)
                .maxTotalIterations(maxTeamRounds) // 限制 Supervisor 只准点名 3 次
                .build();

        // 3. 使用全新的 Session，防止被日志中出现的 "Maximum iterations reached" 历史污染
        AgentSession session = InMemoryAgentSession.of("loop_test_" + System.currentTimeMillis());

        team.call(Prompt.of("请帮我写一段代码"), session);

        TeamTrace trace = team.getTrace(session);

        // --- 修正后的断言策略 ---

        // 策略 A: 验证团队决策轮次（Supervisor 工作的次数）
        long supervisorRounds = trace.getSteps().stream()
                .filter(s -> "supervisor".equalsIgnoreCase(s.getSource()))
                .count();

        System.out.println("团队决策总轮次: " + supervisorRounds);
        Assertions.assertTrue(supervisorRounds <= maxTeamRounds, "Supervisor 决策次数超标，死循环防御失效");

        // 策略 B: 如果非要检查 StepCount，需要预留 Agent 内部 ReAct 思考的步数
        // 一个 Agent 正常回复至少 1-2 步，Supervisor 决策 1 步
        System.out.println("轨迹总步数: " + trace.getStepCount());
        // 允许 3 轮决策，每轮 Agent 思考 3 步，总步数预留到 15 比较安全
        Assertions.assertTrue(trace.getStepCount() < 20, "总步数异常，可能发生了深度死循环");
    }
}