package features.ai.team.def;

import demo.ai.llm.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.flow.FlowContext;

/**
 * 状态持久化与断点续跑测试
 * <p>验证：当 Agent 系统发生崩溃或主动挂起后，能够通过序列化快照重建上下文记忆并继续后续决策。</p>
 */
public class TeamAgentPersistenceAndResumeTest {

    @Test
    public void testPersistenceAndResume() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        String teamName = "persistent_trip_manager";

        // 1. 构建一个带有自定义流程的团队
        TeamAgent tripAgent = TeamAgent.of(chatModel)
                .name(teamName)
                .graphAdjuster(spec -> {
                    // 自定义流程：Start -> searcher -> Supervisor (决策后续)
                    spec.addStart(Agent.ID_START).linkAdd("searcher");
                    spec.addActivity(ReActAgent.of(chatModel)
                                    .name("searcher")
                                    .description("天气搜索员，负责提供实时气候数据")
                                    .build())
                            .linkAdd(TeamAgent.ID_SUPERVISOR);
                }).build();

        // --- 阶段 A：模拟第一阶段执行并手动构建持久化快照 ---
        // 假设我们在另一台机器上运行，执行完 searcher 后，我们将状态序列化到 DB
        FlowContext contextStep1 = FlowContext.of("order_sn_998");

        // 手动模拟 Trace 状态：已经完成了天气搜索
        TeamTrace snapshot = new TeamTrace(Prompt.of("帮我规划上海行程并给穿衣建议"));
        snapshot.addRecord(ChatRole.ASSISTANT,"searcher", "上海明日天气：大雨转雷阵雨，气温 12 度。", 800L);
        // 设置当前路由断点为 Supervisor，准备让它恢复后进行决策
        snapshot.setRoute(TeamAgent.ID_SUPERVISOR);

        // 将轨迹存入上下文，key 遵循框架规范 "__" + teamName
        contextStep1.put("__" + teamName, snapshot);

        // 模拟落库序列化（JSON）
        String jsonState = contextStep1.toJson();
        System.out.println(">>> 阶段 A：初始状态已持久化至数据库。当前断点：" + snapshot.getRoute());

        // --- 阶段 B：从持久化数据恢复并续跑 ---
        System.out.println("\n>>> 阶段 B：正在从 JSON 快照恢复任务...");

        // 从 JSON 重建 FlowContext，并包装成新的 AgentSession
        FlowContext restoredContext = FlowContext.fromJson(jsonState);
        AgentSession session = InMemoryAgentSession.of(restoredContext);

        // 验证恢复：调用时不传 Prompt，触发“断点续跑”模式
        String finalResult = tripAgent.resume(session).getContent();

        // --- 阶段 C：核心验证 ---
        TeamTrace finalTrace = tripAgent.getTrace(session);

        // 验证 1：状态恢复完整性
        Assertions.assertNotNull(finalTrace, "恢复后的轨迹不应为空");
        Assertions.assertTrue(finalTrace.getRecordCount() >= 2, "轨迹应包含预设的 searcher 步及后续生成步");

        // 验证 2：历史记忆持久性（Agent 是否还记得 searcher 提供的数据）
        boolean remembersWeather = finalTrace.getFormattedHistory().contains("上海明日天气");
        Assertions.assertTrue(remembersWeather, "恢复后的 Agent 应该记得快照中的天气信息");

        // 验证 3：最终决策结果
        Assertions.assertNotNull(finalResult);
        System.out.println("恢复执行后的最终答复: " + finalResult);
    }

    @Test
    public void testResetOnNewPrompt() throws Throwable {
        // 测试：在新提示词驱动下，Session 是否会自动开启新轨迹
        ChatModel chatModel = LlmUtil.getChatModel();
        TeamAgent team = TeamAgent.of(chatModel)
                .name("reset_test_team")
                .agentAdd(ReActAgent.of(chatModel).name("agent").build())
                .build();

        AgentSession session = InMemoryAgentSession.of("test_reset_id");

        // 第一次调用：建立初始上下文
        team.call(Prompt.of("你好"), session);
        TeamTrace trace1 = team.getTrace(session);
        Assertions.assertNotNull(trace1);
        int initialSteps = trace1.getRecordCount();

        // 第二次调用：传入完全不同的 Prompt
        // 框架应识别出这是一个新任务，并根据业务需要决定是否重置或追加
        String result2 = team.call(Prompt.of("再见"), session).getContent();

        Assertions.assertNotNull(result2);
        System.out.println("第二次调用成功完成");
    }

    @Test
    public void testContextStateIsolation() throws Throwable {
        // 测试：不同 Session 实例之间的完全状态隔离
        ChatModel chatModel = LlmUtil.getChatModel();
        TeamAgent team = TeamAgent.of(chatModel)
                .name("isolation_team")
                .agentAdd(ReActAgent.of(chatModel).name("agent").build())
                .build();

        // 创建两个独立的 Session
        AgentSession session1 = InMemoryAgentSession.of("session_1");
        AgentSession session2 = InMemoryAgentSession.of("session_2");

        // 分别注入私有状态
        session1.getSnapshot().put("user_name", "张三");
        session2.getSnapshot().put("user_name", "李四");

        // 执行调用
        team.call(Prompt.of("谁在和你说话？"), session1);
        team.call(Prompt.of("谁在和你说话？"), session2);

        Assertions.assertNotEquals(
                session1.getSnapshot().get("user_name"),
                session2.getSnapshot().get("user_name"),
                "不同会话的私有变量必须物理隔离"
        );
    }
}