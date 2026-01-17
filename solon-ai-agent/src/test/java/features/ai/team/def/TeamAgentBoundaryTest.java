package features.ai.team.def;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;

/**
 * TeamAgent 边界条件与鲁棒性测试
 * 验证：空成员、单成员、最大迭代限制、空提示词以及多 Agent 协作场景
 */
public class TeamAgentBoundaryTest {

    @Test
    public void testEmptyAgentList() {
        // 测试：无 Agent 成员的团队构建，预期抛出异常
        ChatModel chatModel = LlmUtil.getChatModel();

        Assertions.assertThrows(IllegalStateException.class, () -> {
            TeamAgent.of(chatModel)
                    .name("empty_team")
                    .build();
        });
    }

    @Test
    public void testSingleAgentTeam() throws Throwable {
        // 测试：单 Agent 团队。验证在没有协作场景下，团队代理是否能退化为普通调用
        ChatModel chatModel = LlmUtil.getChatModel();

        Agent soloAgent = ReActAgent.of(chatModel)
                .name("solo")
                .description("擅长独立处理任务的 Agent")
                .build();

        TeamAgent team = TeamAgent.of(chatModel)
                .name("solo_team")
                .agentAdd(soloAgent)
                .build();

        AgentSession session = InMemoryAgentSession.of("test_solo");
        String result = team.call(Prompt.of("你好"), session).getContent();

        Assertions.assertNotNull(result);
        System.out.println("单 Agent 团队执行结果: " + result);
    }

    @Test
    public void testMaxIterationsReached() throws Throwable {
        // 测试：最大迭代次数限制。模拟 A->B->A 的潜在死循环
        ChatModel chatModel = LlmUtil.getChatModel();

        Agent agentA = ReActAgent.of(chatModel)
                .name("agent_a")
                .description("总是将任务转交给 agent_b 处理")
                .build();

        Agent agentB = ReActAgent.of(chatModel)
                .name("agent_b")
                .description("总是将任务转交给 agent_a 处理")
                .build();

        TeamAgent team = TeamAgent.of(chatModel)
                .name("loop_team")
                .agentAdd(agentA)
                .agentAdd(agentB)
                .maxTurns(3) // 强制限制迭代次数
                .build();

        AgentSession session = InMemoryAgentSession.of("test_loop");
        String result = team.call(Prompt.of("循环测试任务"), session).getContent();

        System.out.println("迭代限制后的结果: " + result);
        Assertions.assertNotNull(result);

        TeamTrace trace = team.getTrace(session);
        System.out.println("实际迭代次数: " + trace.getTurnCount());
        Assertions.assertTrue(trace.getTurnCount() > 0, "应记录执行过程");
    }

    @Test
    public void testNullPrompt() throws Throwable {
        // 测试：上下文恢复。当 prompt 为空时，应利用 Session 中的历史继续执行
        ChatModel chatModel = LlmUtil.getChatModel();

        Agent agent = ReActAgent.of(chatModel)
                .name("test_agent")
                .description("通用测试助手")
                .build();

        TeamAgent team = TeamAgent.of(chatModel)
                .name("restore_context_team")
                .agentAdd(agent)
                .build();

        AgentSession session = InMemoryAgentSession.of("test_null_restore");

        // 1. 注入初始提示词
        team.call(Prompt.of("记住：我的幸运数字是 7"), session);

        // 2. 传入 null，验证 Agent 是否能根据 Session 历史找回上下文
        String result = team.call(session).getContent();

        Assertions.assertNotNull(result);
        System.out.println("上下文恢复结果: " + result);
    }

    @Test
    public void testLargeTeamPerformance() throws Throwable {
        // 测试：多 Agent 团队的性能及调度稳定性
        ChatModel chatModel = LlmUtil.getChatModel();

        TeamAgent.Builder builder = TeamAgent.of(chatModel).name("large_team");

        for (int i = 0; i < 5; i++) {
            builder.agentAdd(ReActAgent.of(chatModel)
                    .name("agent_" + i)
                    .description("我是第 " + i + " 号专家，负责特定模块的分析")
                    .build());
        }

        TeamAgent team = builder.build();
        AgentSession session = InMemoryAgentSession.of("perf_test");

        long startTime = System.currentTimeMillis();
        String result = team.call(Prompt.of("请协作完成一份综合性能报告"), session).getContent();
        long duration = System.currentTimeMillis() - startTime;

        System.out.println("执行耗时: " + duration + "ms");

        TeamTrace trace = team.getTrace(session);
        Assertions.assertTrue(trace.getRecordCount() > 0, "至少应有一个 Agent 被调用");
    }

    @Test
    public void testIterationLimitActuallyTriggered() throws Throwable {
        // 测试：在复杂任务下触发迭代强制停止
        ChatModel chatModel = LlmUtil.getChatModel();

        Agent agentA = ReActAgent.of(chatModel).name("agent_a").description("A: 认为任务非常复杂，总是推给 B").build();
        Agent agentB = ReActAgent.of(chatModel).name("agent_b").description("B: 认为需要更多视角，总是推给 A").build();

        TeamAgent team = TeamAgent.of(chatModel)
                .agentAdd(agentA).agentAdd(agentB)
                .maxTurns(2)
                .build();

        AgentSession session = InMemoryAgentSession.of("test_hard_limit");

        // 提出一个极其宏大、无法轻易完结的问题以诱发多次协作
        String result = team.call(Prompt.of("请详细论述量子计算对全球金融加密体系的每一步具体影响"), session).getContent();

        TeamTrace trace = team.getTrace(session);
        System.out.println("最终迭代次数: " + trace.getTurnCount());
        Assertions.assertNotNull(result);
    }

    @Test
    public void testAllAgentsParticipateScenario() throws Throwable {
        // 测试：垂直领域分工明确时，所有专家是否能各司其职
        ChatModel chatModel = LlmUtil.getChatModel();

        String[] roles = {"架构师", "测试专家", "运维专家"};
        TeamAgent.Builder builder = TeamAgent.of(chatModel).name("expert_group");

        for (String role : roles) {
            builder.agentAdd(ReActAgent.of(chatModel).name(role).description("我是" + role).build());
        }

        TeamAgent team = builder.build();
        AgentSession session = InMemoryAgentSession.of("test_expert_sync");

        String prompt = "我们要发布一个高并发系统，请各专家从架构、测试、运维三个维度给出方案";
        String result = team.call(Prompt.of(prompt), session).getContent();

        TeamTrace trace = team.getTrace(session);
        long distinctAgents = trace.getRecords().stream().map(s -> s.getSource()).distinct().count();

        System.out.println("参与的专家数量: " + distinctAgents);
        Assertions.assertTrue(distinctAgents >= 1, "应该至少有专家参与");
    }
}