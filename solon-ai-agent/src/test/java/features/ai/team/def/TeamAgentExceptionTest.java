package features.ai.team.def;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.prompt.Prompt;

/**
 * TeamAgent 异常处理与健壮性测试
 * <p>验证团队智能体在面对 Agent 内部故障或 Graph 节点逻辑错误时，异常的传播机制与状态保留情况。</p>
 */
public class TeamAgentExceptionTest {

    /**
     * 测试：Agent 执行期异常传播
     * <p>目标：验证当团队中的某个成员 Agent 抛出运行时异常时，该异常能穿透 TeamAgent 正常抛出，以便上层业务处理。</p>
     */
    @Test
    public void testAgentExceptionPropagation() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 创建一个故障 Agent
        Agent throwingAgent = new Agent() {
            @Override
            public String name() { return "trouble_maker"; }

            @Override
            public String description() { return "总是出问题的 Agent"; }

            @Override
            public AssistantMessage call(Prompt prompt, AgentSession session) throws Throwable {
                // 模拟 Agent 在推理或工具调用时发生的严重错误
                throw new RuntimeException("模拟 Agent 内部异常");
            }
        };

        // 2. 构建包含故障成员的团队
        TeamAgent team = TeamAgent.of(chatModel)
                .name("exception_team")
                .addAgent(throwingAgent)
                .build();

        // 3. 使用 AgentSession 开启会话
        AgentSession session = InMemoryAgentSession.of("session_exception_1");

        // 4. 执行并断言异常传播
        try {
            team.call(Prompt.of("触发异常测试"), session);
            Assertions.fail("期望抛出异常但未捕获到");
        } catch (Throwable e) {
            // 在 Solon AI 中，底层 RuntimeException 通常会被封装在调用链中
            String errorMsg = e.getCause().toString();
            System.out.println("捕获到预期的 Agent 异常: " + errorMsg);
            Assertions.assertTrue(errorMsg.contains("模拟 Agent 内部异常"),
                    "异常消息应包含原始错误信息");
        }
    }

    /**
     * 测试：Graph 编排节点执行异常
     * <p>目标：验证在自定义工作流（Graph）中，Task 节点抛出的异常能导致流程中断，并可通过 Session 快照核实位置。</p>
     */
    @Test
    public void testGraphNodeException() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 构建一个带有故障节点的团队图谱
        TeamAgent team = TeamAgent.of(chatModel)
                .name("graph_exception_team")
                .graphAdjuster(spec -> {
                    spec.addStart(Agent.ID_START).linkAdd("problem_node");
                    spec.addActivity("problem_node")
                            .task((c, n) -> {
                                // 模拟工作流中的逻辑崩溃（如数据库连接失败或权限非法）
                                throw new IllegalStateException("节点执行异常");
                            })
                            .linkAdd(Agent.ID_END);
                    spec.addEnd(Agent.ID_END);
                })
                .build();

        // 2. 创建会话，Session 会内部管理一个 FlowContext 快照
        AgentSession session = InMemoryAgentSession.of("session_graph_err");

        // 3. 执行测试并验证
        try {
            team.call(Prompt.of("测试工作流故障"), session);
            Assertions.fail("Graph 节点异常未能正确阻断流程");
        } catch (Throwable e) {
            System.out.println("捕获到预期的 Graph 节点异常: " + e.getCause().getMessage());

            // 验证异常内容
            Assertions.assertTrue(e.getCause().toString().contains("节点执行异常"));

            // 验证快照：检查流程是否停留在发生故障的节点
            Assertions.assertEquals("problem_node", session.getSnapshot().lastNodeId(),
                    "快照应记录最后执行失败的节点 ID");
        }
    }
}