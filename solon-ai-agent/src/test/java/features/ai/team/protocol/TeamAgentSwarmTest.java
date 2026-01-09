package features.ai.team.protocol;

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
 * Swarm 策略测试：去中心化的接力模式
 * <p>
 * 验证目标：
 * 1. 验证智能体之间如何通过 Swarm 协议进行去中心化的任务移交。
 * 2. 验证 AgentSession 如何承载长链条协作的执行状态与轨迹。
 * </p>
 */
public class TeamAgentSwarmTest {

    /**
     * 测试：Swarm 基础接力逻辑
     * <p>场景：中文翻译 -> 英文润色。验证两个 Agent 是否能够识别职责并完成移交。</p>
     */
    @Test
    public void testSwarmRelayLogic() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 定义翻译接力链成员
        Agent chineseTranslator = ReActAgent.of(chatModel)
                .name("ChineseTranslator")
                .description("负责把用户的中文输入翻译成英文。")
                .build();

        Agent polisher = ReActAgent.of(chatModel)
                .name("Polisher")
                .description("负责对英文文本进行文学化润色。")
                .build();

        // 2. 使用 SWARM 策略构建团队
        TeamAgent team = TeamAgent.of(chatModel)
                .name("swarm_team")
                .protocol(TeamProtocols.SWARM)
                .addAgent(chineseTranslator)
                .addAgent(polisher)
                .build();

        // 打印团队协作图的 YAML，观察去中心化的节点拓扑
        System.out.println("--- Swarm Team Graph ---\n" + team.getGraph().toYaml() + "\n");

        // 3. 使用 AgentSession 替换 FlowContext
        AgentSession session = InMemoryAgentSession.of("test_swarm_session");

        // 4. 执行任务：将中文翻译并润色
        String input = "你好，很高兴认识你";
        String result = team.call(Prompt.of(input), session).getContent();

        System.out.println("=== Swarm 策略测试 ===");
        System.out.println("任务: 翻译并润色 '" + input + "'");
        System.out.println("执行结果: " + result);

        // 5. 通过 session 获取协作轨迹
        TeamTrace trace = team.getTrace(session);
        Assertions.assertNotNull(trace, "应该有轨迹记录");
        Assertions.assertNotNull(result, "任务应该有结果");
        Assertions.assertTrue(trace.getStepCount() > 0, "至少应该执行一步");

        // 6. 验证执行过程
        System.out.println("第一步执行者: " + trace.getSteps().get(0).getAgentName());
        System.out.println("总步数: " + trace.getStepCount());

        long uniqueAgents = trace.getSteps().stream()
                .map(step -> step.getAgentName())
                .distinct()
                .count();
        System.out.println("实际参与 Agent 数: " + uniqueAgents + " (期望参与: 2)");

        // 检查接力参与情况
        boolean hasTranslator = trace.getSteps().stream().anyMatch(s -> "ChineseTranslator".equals(s.getAgentName()));
        boolean hasPolisher = trace.getSteps().stream().anyMatch(s -> "Polisher".equals(s.getAgentName()));
        System.out.println("是否包含翻译器: " + hasTranslator);
        System.out.println("是否包含润色器: " + hasPolisher);

        System.out.println("完整协作轨迹:\n" + trace.getFormattedHistory());
        System.out.println("=== 测试结束 ===\n");
    }

    /**
     * 测试：Swarm 长链条处理逻辑
     * <p>场景：翻译 -> 语法检查 -> 风格改进 -> 最终审阅。验证在复杂链条下的 Session 稳定性。</p>
     */
    @Test
    public void testSwarmWithLongerChain() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 创建多级处理链
        Agent translator = ReActAgent.of(chatModel).name("Translator").description("将中文翻译成英文。").build();
        Agent grammarChecker = ReActAgent.of(chatModel).name("GrammarChecker").description("检查英文语法和拼写错误。").build();
        Agent styleImprover = ReActAgent.of(chatModel).name("StyleImprover").description("改进英文文本的写作风格。").build();
        Agent finalReviewer = ReActAgent.of(chatModel).name("FinalReviewer").description("进行最终审阅，确保质量。").build();

        TeamAgent team = TeamAgent.of(chatModel)
                .name("swarm_chain_team")
                .protocol(TeamProtocols.SWARM)
                .addAgent(translator)
                .addAgent(grammarChecker)
                .addAgent(styleImprover)
                .addAgent(finalReviewer)
                .build();

        // 创建 Session 并执行复杂文本处理
        AgentSession session = InMemoryAgentSession.of("test_swarm_chain_session");
        String content = "人工智能正在改变世界，它通过机器学习算法分析海量数据，为各种行业提供智能解决方案。";

        String result = team.call(Prompt.of(content), session).getContent();

        System.out.println("=== Swarm 策略测试（长处理链） ===");
        System.out.println("任务: 处理复杂文本接力");
        System.out.println("最终结果: " + result);

        TeamTrace trace = team.getTrace(session);
        Assertions.assertNotNull(trace);
        Assertions.assertTrue(trace.getStepCount() > 0);

        System.out.println("协作总步数: " + trace.getStepCount());

        // 统计参与 Agent 种类
        long uniqueAgents = trace.getSteps().stream()
                .map(step -> step.getAgentName())
                .distinct()
                .count();
        System.out.println("实际参与 Agent 数: " + uniqueAgents + " (可用总数: 4)");

        // 打印每个步骤的简要摘要
        System.out.println("执行链路快照:");
        trace.getSteps().forEach(step -> {
            String summary = step.getContent().trim().replace("\n", " ");
            System.out.println("  - [" + step.getAgentName() + "]: " +
                    summary.substring(0, Math.min(50, summary.length())) + "...");
        });

        System.out.println("\n协作轨迹详情:\n" + trace.getFormattedHistory());
        System.out.println("=== 测试结束 ===\n");
    }

    /**
     * 测试：Swarm 动态分发逻辑
     * <p>场景：分发员根据输入类型，动态决定移交给后端开发还是前端开发。</p>
     */
    @Test
    public void testSwarmDynamicDispatch() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 定义具备分发能力的初始 Agent
        Agent dispatcher = ReActAgent.of(chatModel)
                .name("Dispatcher")
                .description("分析用户的任务类型。如果是 UI/页面相关，移交给 Designer；如果是逻辑/接口相关，移交给 Developer。")
                .build();

        Agent designer = ReActAgent.of(chatModel)
                .name("Designer")
                .description("负责处理前端样式和 UI 设计任务。")
                .build();

        Agent developer = ReActAgent.of(chatModel)
                .name("Developer")
                .description("负责处理后端逻辑和数据库任务。")
                .build();

        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.SWARM)
                .addAgent(dispatcher)
                .addAgent(designer)
                .addAgent(developer)
                .build();

        // 2. 发起一个纯后端的任务
        AgentSession session = InMemoryAgentSession.of("session_dynamic_swarm");
        String query = "请帮我写一个复杂的 SQL 查询来优化用户订单表。";

        String result = team.call(Prompt.of(query), session).getContent();

        System.out.println("=== Swarm 动态分发结果 ===");
        TeamTrace trace = team.getTrace(session);

        // 3. 验证路由逻辑
        boolean handledByDev = trace.getSteps().stream().anyMatch(s -> "Developer".equals(s.getAgentName()));
        boolean handledByDesigner = trace.getSteps().stream().anyMatch(s -> "Designer".equals(s.getAgentName()));

        System.out.println("后端开发是否参与: " + handledByDev);
        System.out.println("UI设计师是否参与: " + handledByDesigner);

        Assertions.assertTrue(handledByDev, "后端任务理应由 Developer 处理");
        Assertions.assertFalse(handledByDesigner, "后端任务不应惊动 Designer");
    }
}