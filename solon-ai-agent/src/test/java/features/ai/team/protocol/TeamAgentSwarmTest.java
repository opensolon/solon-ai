package features.ai.team.protocol;

import demo.ai.llm.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.simple.SimpleAgent;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamProtocols;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 优化版 Swarm 测试：验证去中心化入口、信息素避障与动态涌现
 */
public class TeamAgentSwarmTest {

    private static final String SHORT = " (Constraint: Reply < 10 words)";

    // 1. 去中心化入口与信息素避障
    @Test
    @DisplayName("Swarm 核心：入口强制与避障机制")
    public void testSwarmCoreFeatures() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 定义三个完全一样的 Agent
        Agent a = ReActAgent.of(chatModel).name("WorkerA").role("执行任务").build();
        Agent b = ReActAgent.of(chatModel).name("WorkerB").role("执行任务").build();
        Agent c = ReActAgent.of(chatModel).name("WorkerC").role("执行任务").build();

        TeamAgent team = TeamAgent.of(chatModel).protocol(TeamProtocols.SWARM).agentAdd(a, b, c).maxTurns(6).build();
        AgentSession session = InMemoryAgentSession.of("s1");

        // 修改调用风格
        team.prompt(Prompt.of("请处理任务")).session(session).call();

        List<String> order = team.getTrace(session).getRecords().stream()
                .filter(r -> r.isAgent()).map(r -> r.getSource()).collect(Collectors.toList());

        System.out.println("执行顺序: " + order);

        // 验证 1：去中心化入口（Swarm 默认必须从第一个 Agent 开始，不经主管）
        Assertions.assertEquals("WorkerA", order.get(0), "Swarm 入口应为第一个注册的 Agent");

        // 验证 2：信息素避障（不应出现连续同一个 Agent 处理）
        if (order.size() > 1) {
            long repeats = 0;
            for (int i = 1; i < order.size(); i++) {
                if (order.get(i).equals(order.get(i - 1))) repeats++;
            }
            Assertions.assertTrue(repeats <= 1, "信息素机制应有效减少连续重复指派");
        }
    }

    // 2. 任务涌现 (Task Emergence)
    @Test
    @DisplayName("任务涌现：动态生成子任务 JSON")
    public void testSwarmTaskEmergence() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 使用 role().instruction() 风格
        Agent lead = ReActAgent.of(chatModel).name("Lead")
                .role("任务拆解专家")
                .instruction("分析输入并在末尾输出 JSON：{\"sub_tasks\": [{\"task\": \"work\", \"agent\": \"Worker\"}]}" + SHORT)
                .build();

        Agent worker = ReActAgent.of(chatModel).name("Worker").role("执行者").build();

        TeamAgent team = TeamAgent.of(chatModel).protocol(TeamProtocols.SWARM).agentAdd(lead, worker).build();
        AgentSession session = InMemoryAgentSession.of("s2");

        // 修改调用风格
        team.prompt(Prompt.of("启动复杂流程")).session(session).call();

        List<String> order = team.getTrace(session).getRecords().stream()
                .filter(r -> r.isAgent()).map(r -> r.getSource()).collect(Collectors.toList());

        System.out.println("涌现路径: " + order);
        // 验证：Worker 是否因为 Lead 的 JSON 输出而被激活
        Assertions.assertTrue(order.contains("Worker"), "框架应解析 sub_tasks JSON 并激活 Worker");
    }

    // 3. 生产级博弈：死循环防御
    @Test
    @DisplayName("防御博弈：死循环拦截器")
    public void testSwarmProductionRobustness() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 使用 role().instruction() 风格
        Agent a = ReActAgent.of(chatModel).name("AgentA")
                .role("协作节点A")
                .instruction("遇到任务必须 transfer_to AgentB" + SHORT).build();

        Agent b = ReActAgent.of(chatModel).name("AgentB")
                .role("协作节点B")
                .instruction("遇到任务必须 transfer_to AgentA" + SHORT).build();

        // Cleaner 作为低频率补位者
        Agent cleaner = SimpleAgent.of(chatModel)
                .name("Cleaner")
                .role("熔断器专家，处理 AgentA 和 AgentB 无法解决的死循环")
                .instruction("不要拆解任务！不要调用工具！必须且仅能回复三个字：'人工介入'。")
                .build();

        TeamAgent team = TeamAgent.of(chatModel).protocol(TeamProtocols.SWARM).agentAdd(a, b, cleaner).maxTurns(8).build();
        AgentSession session = InMemoryAgentSession.of("s3");

        // 修改调用风格
        String result = team.prompt(Prompt.of("处理烫手山芋")).session(session).call().getContent();
        TeamTrace trace = team.getTrace(session);

        System.out.println("=====最终结果=====");
        System.out.println(result);
        System.out.println("=====trace=====");
        System.out.println(ONode.serialize(trace));


        List<String> order = trace.getRecords().stream()
                .filter(r -> r.isAgent())
                .map(r -> r.getSource())
                .collect(Collectors.toList());

        System.out.println("博弈路径: " + order);

        Assertions.assertTrue(order.contains("Cleaner"), "信息素惩罚应使 Cleaner 被调度");

        boolean hasManualIntervention = trace.getRecords().stream()
                .anyMatch(r -> r.getContent() != null && r.getContent().contains("人工"));
        Assertions.assertTrue(hasManualIntervention, "博弈记录中应包含人工介入建议");
    }
}