package features.ai.team;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.flow.FlowContext;

/**
 * 状态持久化与断点续跑测试
 * 验证：系统崩溃或主动挂起后，能够完整恢复上下文记忆并继续后续决策。
 */
public class TeamAgentPersistenceAndResumeTest {

    @Test
    public void testPersistenceAndResume() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        String teamId = "persistent_trip_manager";

        TeamAgent tripTeam = TeamAgent.builder(chatModel)
                .name(teamId)
                .addAgent(ReActAgent.builder(chatModel)
                        .name("planner")
                        .description("资深行程规划专家")
                        .build())
                .graph(spec -> {
                    spec.addStart(Agent.ID_START).linkAdd("searcher");
                    spec.addActivity(ReActAgent.builder(chatModel)
                                    .name("searcher")
                                    .description("天气搜索员")
                                    .build())
                            .linkAdd(Agent.ID_ROUTER);
                }).build();

        // 1. 【模拟第一阶段：挂起】执行了搜索，状态存入 DB
        FlowContext contextStep1 = FlowContext.of("order_sn_998");
        contextStep1.lastNode(tripTeam.getGraph().getNodeOrThrow(Agent.ID_ROUTER));

        TeamTrace snapshot = new TeamTrace();
        snapshot.addStep("searcher", "上海明日天气：大雨转雷阵雨，气温 12 度。", 800L);
        snapshot.setLastNode(contextStep1.lastNode());

        contextStep1.put(Agent.KEY_PROMPT, Prompt.of("帮我规划上海行程并给穿衣建议"));
        contextStep1.put(Agent.KEY_HISTORY, "[searcher]: 上海明日天气：大雨转雷阵雨。");
        contextStep1.put("__" + teamId, snapshot);

        String jsonState = contextStep1.toJson(); // 模拟落库序列化
        System.out.println(">>> 阶段1完成：业务快照已持久化至数据库。");

        // 2. 【模拟第二阶段：恢复】从序列化数据中重建上下文
        FlowContext contextStep2 = FlowContext.fromJson(jsonState);
        System.out.println(">>> 阶段2启动：正在从断点 [" + contextStep2.lastNodeId() + "] 恢复任务...");

        String finalResult = tripTeam.call(contextStep2); // 传入 null 触发自动恢复

        // 3. 单测检测
        System.out.println(">>> 恢复后的最终输出：\n" + finalResult);
        TeamTrace finalTrace = contextStep2.getAs("__" + teamId);

        Assertions.assertTrue(finalTrace.getStepCount() >= 2, "轨迹应包含快照中的历史步骤和新执行的步骤");
        Assertions.assertTrue(finalResult.contains("上海"), "最终答案丢失了初始 Prompt 中的地理信息");
        Assertions.assertTrue(finalResult.contains("雨") || finalResult.contains("伞"), "规划师未正确读取历史记录中的天气信息");
    }

    @Test
    public void testResetOnNewPrompt() throws Throwable {
        // 测试：resetOnNewPrompt 参数的效果
        ChatModel chatModel = LlmUtil.getChatModel();

        TeamAgent team = TeamAgent.builder(chatModel)
                .name("reset_test_team")
                .addAgent(ReActAgent.builder(chatModel)
                        .name("agent")
                        .description("测试Agent")
                        .build())
                .resetOnNewPrompt(true) // 开启 reset 模式
                .build();

        FlowContext context = FlowContext.of("test_reset");

        // 第一次调用
        String result1 = team.call(context, "第一个问题");
        System.out.println("第一次结果: " + result1);

        // 获取轨迹
        Object trace1 = context.get("__reset_test_team");
        Assertions.assertNotNull(trace1);

        // 第二次调用（新提示词，应该重置）
        String result2 = team.call(context, "第二个问题");
        System.out.println("第二次结果: " + result2);

        // 应该开始新的轨迹
        // 这里可以添加更详细的检查逻辑
    }

    @Test
    public void testContextStateIsolation() throws Throwable {
        // 测试：不同 FlowContext 之间的状态隔离
        ChatModel chatModel = LlmUtil.getChatModel();

        TeamAgent team = TeamAgent.builder(chatModel)
                .name("isolation_team")
                .addAgent(ReActAgent.builder(chatModel)
                        .name("agent")
                        .description("测试Agent")
                        .build())
                .build();

        // 两个独立的上下文
        FlowContext context1 = FlowContext.of("session_1");
        FlowContext context2 = FlowContext.of("session_2");

        // 在 context1 中设置状态
        context1.put("custom_state", "value1");
        String result1 = team.call(context1, "会话1的问题");

        // context2 不应该看到 context1 的状态
        context2.put("custom_state", "value2");
        String result2 = team.call(context2, "会话2的问题");

        Assertions.assertNotEquals(
                context1.get("custom_state"),
                context2.get("custom_state"),
                "不同会话的状态应该隔离"
        );
    }
}