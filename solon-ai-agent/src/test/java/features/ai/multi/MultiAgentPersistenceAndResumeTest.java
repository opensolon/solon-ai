package features.ai.multi;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.multi.AgentRouterTask;
import org.noear.solon.ai.agent.multi.MultiAgent;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Graph;

/**
 *
 * @author noear 2025/12/30 created
 *
 */
public class MultiAgentPersistenceAndResumeTest {
    @Test
    public void testPersistenceAndResume() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 定义一个简单的协作图：搜索 -> 路由 -> 规划
        Graph graph = Graph.create("persistence_test", spec -> {
            spec.addStart("start").linkAdd("searcher");
            spec.addActivity("searcher").task(ReActAgent.builder(chatModel).nameAs("searcher").build()).linkAdd("router");
            spec.addExclusive("router")
                    .task(new AgentRouterTask(chatModel, "planner"))
                    .linkAdd("planner", l -> l.when(ctx -> "planner".equals(ctx.get(Agent.KEY_NEXT_AGENT))))
                    .linkAdd("end");
            spec.addActivity("planner").task(ReActAgent.builder(chatModel).nameAs("planner").build()).linkAdd("end");
            spec.addEnd("end");
        });

        MultiAgent team = new MultiAgent(graph);
        FlowContext context1 = FlowContext.of("session_001");

        // 2. 第一次运行：模拟只运行到 searcher 完成
        // 注意：这里我们手动控制只跑一个节点，或者模拟在运行中途序列化
        String initialPrompt = "帮我调研一下明天上海的天气并给穿衣建议";
        context1.put(Agent.KEY_PROMPT, initialPrompt);

        // 假设流程在 searcher 运行完后暂停了
        // 我们可以通过拦截器或手动 eval 部分节点来模拟
        context1.put(Agent.KEY_HISTORY, "[Agent searcher]: 上海明天晴，15度。");
        context1.lastNode(graph.getNodeOrThrow("router")); // 模拟停在路由节点

        // 3. 序列化持久化（存储到数据库/Redis）
        String jsonState = context1.toJson();
        System.out.println("持久化状态: " + jsonState);

        // 4. 从持久化状态中恢复（模拟新请求或重启）
        FlowContext context2 = FlowContext.fromJson(jsonState);
        MultiAgent teamResumed = new MultiAgent(graph);

        // 5. 继续执行：team 会从 lastNodeId ("router") 开始运行
        String finalResult = teamResumed.ask(context2, initialPrompt);

        System.out.println("恢复后的最终结果: " + finalResult);
        Assertions.assertTrue(finalResult.contains("上海") && context2.getOrDefault(Agent.KEY_ITERATIONS, 0) > 0);
    }
}
