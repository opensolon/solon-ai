package features.ai.team;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.chat.ChatModel;
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
                .addAgent(ReActAgent.builder(chatModel).name("planner").description("资深行程规划专家").build())
                .graph(spec -> {
                    spec.addStart(Agent.ID_START).linkAdd("searcher");
                    spec.addActivity(ReActAgent.builder(chatModel).name("searcher").description("天气搜索员").build())
                            .linkAdd(Agent.ID_ROUTER);
                }).build();

        // 1. 【模拟第一阶段：挂起】执行了搜索，状态存入 DB
        FlowContext contextStep1 = FlowContext.of("order_sn_998");
        TeamTrace snapshot = new TeamTrace();
        snapshot.addStep("searcher", "上海明日天气：大雨转雷阵雨，气温 12 度。", 800L);
        snapshot.setLastNode(tripTeam.getGraph().getNodeOrThrow(Agent.ID_ROUTER));

        contextStep1.put(Agent.KEY_PROMPT, "帮我规划上海行程并给穿衣建议");
        contextStep1.put(Agent.KEY_HISTORY, "[searcher]: 上海明日天气：大雨转雷阵雨。");
        contextStep1.put("__" + teamId, snapshot);

        String jsonState = contextStep1.toJson(); // 模拟落库序列化
        System.out.println(">>> 阶段1完成：业务快照已持久化至数据库。");

        // 2. 【模拟第二阶段：恢复】从序列化数据中重建上下文
        FlowContext contextStep2 = FlowContext.fromJson(jsonState);
        System.out.println(">>> 阶段2启动：正在从断点 [" + contextStep2.lastNodeId() + "] 恢复任务...");

        String finalResult = tripTeam.call(contextStep2, null); // 传入 null 触发自动恢复

        // 3. 单测检测
        System.out.println(">>> 恢复后的最终输出：\n" + finalResult);
        TeamTrace finalTrace = contextStep2.getAs("__" + teamId);

        Assertions.assertTrue(finalTrace.getStepCount() >= 2, "轨迹应包含快照中的历史步骤和新执行的步骤");
        Assertions.assertTrue(finalResult.contains("上海"), "最终答案丢失了初始 Prompt 中的地理信息");
        Assertions.assertTrue(finalResult.contains("雨") || finalResult.contains("伞"), "规划师未正确读取历史记录中的天气信息");
    }
}