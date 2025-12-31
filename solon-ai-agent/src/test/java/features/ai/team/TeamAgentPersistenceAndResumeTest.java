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
 * 多智能体持久化与恢复测试
 * 模拟场景：Agent 团队在执行中途状态被存入数据库，随后重启并从断点恢复执行
 *
 * @author noear
 * @since 3.8.1
 */
public class TeamAgentPersistenceAndResumeTest {

    @Test
    public void testPersistenceAndResume() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        String teamName = "persistence_team";

        TeamAgent teamResumed = TeamAgent.builder(chatModel)
                .name(teamName)
                .addAgent(ReActAgent.builder(chatModel).name("planner").description("穿衣规划师").build())
                .graph(spec -> {
                    //修改开始连接
                    spec.addStart(Agent.ID_START).linkAdd("searcher");
                    //添加个独立的智能体
                    spec.addActivity(ReActAgent.builder(chatModel).name("searcher").build())
                            .linkAdd(Agent.ID_ROUTER);
                })
                .build();


        // 2. 第一次运行：模拟运行到 searcher 节点后发生“中断”
        FlowContext context1 = FlowContext.of("session_001");
        String initialPrompt = "帮我调研一下上海的天气并给穿衣建议";

        // 构造中断前的状态：已经有了 searcher 的结果，且停留在 router 准备决策
        TeamTrace trace1 = new TeamTrace();
        trace1.addStep("searcher", "上海明天晴，15度。", 1200L);
        trace1.setLastNode(teamResumed.getGraph().getNodeOrThrow("router")); // 核心：记录停在路由节点

        context1.put(Agent.KEY_PROMPT, initialPrompt);
        context1.put(Agent.KEY_HISTORY, "[searcher]: 上海明天晴，15度。");
        context1.put("__" + teamName, trace1); // 记录团队轨迹

        // 3. 序列化持久化：将 FlowContext 转换为 JSON（模拟存入数据库）
        // 在 Solon Flow 中，context.toJson() 会包含所有自定义的对象（如 TeamTrace）
        String jsonState = context1.toJson();
        System.out.println(">>> 持久化状态已保存至数据库: " + jsonState);

        // --- 模拟系统重启或新服务器接手任务 ---

        // 4. 从持久化状态中恢复
        FlowContext context2 = FlowContext.fromJson(jsonState);


        // 5. 继续执行
        // 传入 prompt 为 null，MultiAgent 内部会判断并从 trace.getLastNodeId() 恢复运行
        System.out.println(">>> 正在从断点 [" + context2.lastNodeId() + "] 恢复执行...");
        String finalResult = teamResumed.ask(context2, null);

        // 6. 验证结果
        System.out.println(">>> 恢复后的最终输出:\n" + finalResult);

        TeamTrace finalTrace = context2.getAs("__" + teamName);
        Assertions.assertNotNull(finalTrace);
        // 步骤应包含：初始的 searcher (从恢复中来) + 恢复后执行的 planner
        Assertions.assertTrue(finalTrace.getStepCount() >= 2);
        Assertions.assertTrue(finalResult.contains("上海"));
    }
}