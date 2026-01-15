package features.ai.team.protocol;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActSystemPrompt;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.simple.SimpleAgent;
import org.noear.solon.ai.agent.simple.SimpleSystemPrompt;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamProtocols;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.prompt.Prompt;

import java.util.List;

/**
 * Swarm 策略测试：去中心化的接力模式
 * <p>
 * Swarm（蜂群）协议核心特性：
 * 1. **去中心化入口**：第一个 Agent 直接启动任务，不经过 Supervisor 决策
 * 2. **信息素负载均衡**：执行过的 Agent 信息素值增加，短期内不会被重复指派
 * 3. **任务涌现**：Agent 可以在回复中动态生成子任务 {"sub_tasks": [...]}
 * </p>
 * <p>
 * 与 HIERARCHICAL 协议对比：
 * - HIERARCHICAL: Start -> Supervisor -> Agent -> Supervisor -> Agent -> End（完全中心化）
 * - SWARM:        Start -> Agent1 -> Supervisor -> Agent2 -> End（去中心化入口）
 * </p>
 */
public class TeamAgentSwarmTest {

    /**
     * 测试：Swarm 协议核心特性
     * <p>
     * 验证点：
     * 1. **去中心化入口**：第一个 Agent 直接启动任务，不经过 Supervisor 决策
     * 2. **信息素负载均衡**：执行过的 Agent 信息素值增加（+5），全员每轮挥发（-1），避免连续重复指派
     * 3. **FinalAnswer 正确性**：finalAnswer 应该是最后一个 Agent 的输出
     * </p>
     */
    @Test
    public void testSwarmCoreFeatures() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 定义三个能力完全相同的 Agent（只有名字不同）
        Agent workerA = ReActAgent.of(chatModel)
                .name("WorkerA")
                .description("通用工作者，可以处理任何简单文本任务。")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("工作者")
                        .instruction("简单回复：已处理。")
                        .build())
                .build();

        Agent workerB = ReActAgent.of(chatModel)
                .name("WorkerB")
                .description("通用工作者，可以处理任何简单文本任务。")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("工作者")
                        .instruction("简单回复：已处理。")
                        .build())
                .build();

        Agent workerC = ReActAgent.of(chatModel)
                .name("WorkerC")
                .description("通用工作者，可以处理任何简单文本任务。")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("工作者")
                        .instruction("简单回复：已处理。")
                        .build())
                .build();

        TeamAgent team = TeamAgent.of(chatModel)
                .name("swarm_pheromone_test")
                .protocol(TeamProtocols.SWARM)
                .agentAdd(workerA, workerB, workerC)
                .maxTotalIterations(6)  // 允许多轮执行
                .build();

        AgentSession session = InMemoryAgentSession.of("test_pheromone");

        AssistantMessage result = team.call(Prompt.of("处理这个简单任务"), session);

        TeamTrace trace = team.getTrace(session);

        // 获取所有 Agent 的执行顺序
        List<String> executionOrder = trace.getSteps().stream()
                .filter(TeamTrace.TeamStep::isAgent)
                .map(TeamTrace.TeamStep::getSource)
                .collect(java.util.stream.Collectors.toList());

        System.out.println("Agent 执行顺序: " + executionOrder);

        // 验证信息素机制的效果：
        // 1. 第一个 Agent 必须是 WorkerA（去中心化入口，这是协议强制的，不依赖 LLM）
        if (!executionOrder.isEmpty()) {
            Assertions.assertEquals("WorkerA", executionOrder.get(0),
                    "Swarm 协议的去中心化入口：第一个执行的必须是 WorkerA");
        }

        // 2. 如果有多个 Agent 参与，检查是否有连续重复指派
        // 信息素机制应该避免同一 Agent 被连续指派（除非只有一个 Agent）
        int consecutiveRepeats = 0;
        for (int i = 1; i < executionOrder.size(); i++) {
            if (executionOrder.get(i).equals(executionOrder.get(i - 1))) {
                consecutiveRepeats++;
            }
        }

        System.out.println("连续重复指派次数: " + consecutiveRepeats);
        System.out.println("参与的 Agent 数: " + executionOrder.stream().distinct().count());

        if (executionOrder.size() >= 2) {
            // 信息素机制生效时，连续重复应该很少
            // 允许最多 1 次连续重复（考虑 LLM 决策的随机性）
            Assertions.assertTrue(consecutiveRepeats <= 1,
                    "信息素机制应该避免连续重复指派。连续重复: " + consecutiveRepeats + ", 总步数: " + executionOrder.size());
        }

        // 3. 验证至少有一个 Agent 参与（去中心化入口保证）
        Assertions.assertTrue(executionOrder.size() >= 1, "至少应该有一个 Agent 参与");

        // 4. 验证 FinalAnswer 正确性
        String finalAnswer = trace.getFinalAnswer();
        String resultContent = result.getContent();

        // 获取最后一个 Agent 的输出
        String lastAgentOutput = "";
        for (int i = trace.getSteps().size() - 1; i >= 0; i--) {
            TeamTrace.TeamStep step = trace.getSteps().get(i);
            if (step.isAgent()) {
                lastAgentOutput = step.getContent();
                break;
            }
        }

        System.out.println("finalAnswer: " + finalAnswer);
        System.out.println("resultContent: " + resultContent);
        System.out.println("lastAgentOutput: " + lastAgentOutput);

        Assertions.assertNotNull(finalAnswer, "finalAnswer 不应为空");
        Assertions.assertNotNull(resultContent, "result.getContent() 不应为空");
        Assertions.assertEquals(lastAgentOutput, finalAnswer,
                "finalAnswer 应该是最后一个 Agent 的输出");
        Assertions.assertEquals(finalAnswer, resultContent,
                "result.getContent() 应该与 trace.getFinalAnswer() 一致");

        System.out.println("协作轨迹:\n" + trace.getFormattedHistory());
    }

    /**
     * 测试：Swarm 任务涌现机制（观察性测试）
     * <p>
     * 注意：此测试依赖 LLM 行为，无法保证每次都触发任务涌现。
     * 测试目的是验证当 LLM 生成子任务时，框架能够正确处理。
     * </p>
     * <p>
     * 验证方式：通过观察测试输出中的 DEBUG 日志来验证任务涌现是否发生。
     * 日志格式：Swarm: Agent [X] emerged Y new tasks into the pool.
     * </p>
     */
    @Test
    public void testSwarmTaskEmergence() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        Agent researcher = SimpleAgent.of(chatModel)
                .name("Researcher")
                .description("负责研究和分析问题，可以生成具体的子任务")
                .systemPrompt(SimpleSystemPrompt.builder()
                        .role("研究员")
                        .instruction("你是一个专业的研究员，负责分析复杂问题并将其分解为具体的子任务。分解的子任务列表在末尾提供，JSON 格式。例如：{\"sub_tasks\": [{\"task\": \"收集数据\", \"agent\": \"DataCollector\"}]}\n可用的 agent 有：DataCollector（数据收集）、Analyzer（数据分析）。")
                        .build())
                .build();

        Agent dataCollector = SimpleAgent.of(chatModel)
                .name("DataCollector")
                .description("负责收集数据")
                .systemPrompt(SimpleSystemPrompt.builder()
                        .role("数据收集员")
                        .instruction("你是一个专业的数据收集员，负责收集相关信息。")
                        .build())
                .build();

        Agent analyzer = SimpleAgent.of(chatModel)
                .name("Analyzer")
                .description("负责数据分析")
                .systemPrompt(SimpleSystemPrompt.builder()
                        .role("分析师")
                        .instruction("你是一个专业的分析师，负责分析收集到的数据并得出结论。")
                        .build())
                .build();

        TeamAgent team = TeamAgent.of(chatModel)
                .name("swarm_task_emergence_test")
                .protocol(TeamProtocols.SWARM)
                .agentAdd(researcher, dataCollector, analyzer)
                .maxTotalIterations(15)
                .build();

        AgentSession session = InMemoryAgentSession.of("test_task_emergence");
        AssistantMessage result = team.call(Prompt.of("请分析电动汽车在未来十年的发展趋势，包括技术发展、市场前景和政策影响"), session);

        TeamTrace trace = team.getTrace(session);
        List<String> executionOrder = trace.getSteps().stream()
                .filter(TeamTrace.TeamStep::isAgent)
                .map(TeamTrace.TeamStep::getSource)
                .collect(java.util.stream.Collectors.toList());

        System.out.println("任务涌现测试 - Agent 执行顺序: " + executionOrder);
        System.out.println("任务涌现测试 - 参与的 Agent 数: " + executionOrder.stream().distinct().count());
        System.out.println("提示：查看上方 DEBUG 日志中是否有 'emerged X new tasks into the pool' 来验证任务涌现");

        // 验证基本协作功能
        Assertions.assertTrue(executionOrder.size() >= 1, "至少应该有一个 Agent 参与");
        Assertions.assertNotNull(result.getContent(), "应该有最终结果");

        System.out.println("任务涌现测试 - 协作轨迹:\n" + trace.getFormattedHistory());
    }

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
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("专业翻译")
                        .instruction("将中文翻译成英文。直接输出英文翻译结果。")
                        .build())
                .build();

        Agent polisher = ReActAgent.of(chatModel)
                .name("Polisher")
                .description("负责对英文文本进行文学化润色。")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("润色专家")
                        .instruction("对上游提供的英文文本进行润色。输出润色后的英文。")
                        .build())
                .build();

        // 2. 使用 SWARM 策略构建团队
        TeamAgent team = TeamAgent.of(chatModel)
                .name("swarm_team")
                .protocol(TeamProtocols.SWARM)
                .agentAdd(chineseTranslator, polisher)
                .build();

        // 打印团队协作图的 YAML，观察去中心化的节点拓扑
        System.out.println("--- Swarm Team Graph ---\n" + team.getGraph().toYaml() + "\n");

        // 3. 使用 AgentSession 替换 FlowContext
        AgentSession session = InMemoryAgentSession.of("test_swarm_session");

        // 4. 执行任务：将中文翻译并润色
        String input = "人工智能技术正在深刻地改变着我们的生活方式，从智能家居到自动驾驶，从医疗诊断到金融风控，其应用场景日益广泛。";
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
        System.out.println("第一步执行者: " + trace.getSteps().get(0).getSource());
        System.out.println("总步数: " + trace.getStepCount());

        long uniqueAgents = trace.getSteps().stream()
                .map(step -> step.getSource())
                .distinct()
                .count();
        System.out.println("实际参与 Agent 数: " + uniqueAgents + " (期望参与: 2)");

        // 检查接力参与情况
        boolean hasTranslator = trace.getSteps().stream().anyMatch(s -> "ChineseTranslator".equals(s.getSource()));
        boolean hasPolisher = trace.getSteps().stream().anyMatch(s -> "Polisher".equals(s.getSource()));
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
        Agent translator = ReActAgent.of(chatModel)
                .name("Translator")
                .description("将中文翻译成英文。")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("专业翻译")
                        .instruction("将输入的中文文本翻译成英文。直接输出英文翻译结果，不要解释。")
                        .build())
                .build();

        Agent grammarChecker = ReActAgent.of(chatModel)
                .name("GrammarChecker")
                .description("检查英文语法和拼写错误。")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("语法检查专家")
                        .instruction("检查上游提供的英文文本的语法和拼写错误，输出修正后的英文文本。")
                        .build())
                .build();

        Agent styleImprover = ReActAgent.of(chatModel)
                .name("StyleImprover")
                .description("改进英文文本的写作风格。")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("文风优化专家")
                        .instruction("改进上游提供的英文文本的写作风格，使其更加流畅优美。输出优化后的英文文本。")
                        .build())
                .build();

        Agent finalReviewer = ReActAgent.of(chatModel)
                .name("FinalReviewer")
                .description("进行最终审阅，确保质量。")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("最终审阅专家")
                        .instruction("对上游提供的英文文本进行最终审阅，确保质量。输出最终版本的英文文本。")
                        .build())
                .build();

        TeamAgent team = TeamAgent.of(chatModel)
                .name("swarm_chain_team")
                .protocol(TeamProtocols.SWARM)
                .agentAdd(translator)
                .agentAdd(grammarChecker)
                .agentAdd(styleImprover)
                .agentAdd(finalReviewer)
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
                .map(step -> step.getSource())
                .distinct()
                .count();
        System.out.println("实际参与 Agent 数: " + uniqueAgents + " (可用总数: 4)");

        // 打印每个步骤的简要摘要
        System.out.println("执行链路快照:");
        trace.getSteps().forEach(step -> {
            String summary = step.getContent().trim().replace("\n", " ");
            System.out.println("  - [" + step.getSource() + "]: " +
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
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("任务分发员")
                        .instruction("分析用户任务类型，将 UI/页面任务移交给 Designer，将逻辑/接口/数据库任务移交给 Developer。")
                        .build())
                .build();

        Agent designer = ReActAgent.of(chatModel)
                .name("Designer")
                .description("负责处理前端样式和 UI 设计任务。")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("UI 设计师")
                        .instruction("负责处理前端样式、页面布局和 UI 设计相关任务。")
                        .build())
                .build();

        Agent developer = ReActAgent.of(chatModel)
                .name("Developer")
                .description("负责处理后端逻辑和数据库任务。")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("后端开发")
                        .instruction("负责处理后端逻辑、API 接口和数据库相关任务。")
                        .build())
                .build();

        TeamAgent team = TeamAgent.of(chatModel)
                .name("swarm_dynamic_dispatch_test")
                .protocol(TeamProtocols.SWARM)
                .agentAdd(dispatcher, designer, developer)
                .build();

        // 2. 发起一个纯后端的任务
        AgentSession session = InMemoryAgentSession.of("session_dynamic_swarm");
        String query = "请帮我写一个复杂的 SQL 查询来优化用户订单表。";

        AssistantMessage result = team.call(Prompt.of(query), session);

        System.out.println("=== Swarm 动态分发结果 ===");
        TeamTrace trace = team.getTrace(session);

        List<String> executionOrder = trace.getSteps().stream()
                .filter(TeamTrace.TeamStep::isAgent)
                .map(TeamTrace.TeamStep::getSource)
                .collect(java.util.stream.Collectors.toList());

        System.out.println("Agent 执行顺序: " + executionOrder);

        // 3. 验证 Swarm 去中心化入口：第一个必须是 Dispatcher
        if (!executionOrder.isEmpty()) {
            Assertions.assertEquals("Dispatcher", executionOrder.get(0),
                    "Swarm 协议的去中心化入口：第一个执行的必须是 Dispatcher");
        }

        // 4. 验证路由逻辑
        boolean handledByDev = executionOrder.contains("Developer");
        boolean handledByDesigner = executionOrder.contains("Designer");

        System.out.println("后端开发是否参与: " + handledByDev);
        System.out.println("UI设计师是否参与: " + handledByDesigner);

        Assertions.assertTrue(handledByDev, "后端任务理应由 Developer 处理");
        Assertions.assertFalse(handledByDesigner, "后端任务不应惊动 Designer");

        // 5. 验证 FinalAnswer 正确性
        String finalAnswer = trace.getFinalAnswer();
        String resultContent = result.getContent();

        System.out.println("finalAnswer: " + finalAnswer);
        System.out.println("resultContent: " + resultContent);

        Assertions.assertNotNull(finalAnswer, "finalAnswer 不应为空");
        Assertions.assertEquals(finalAnswer, resultContent,
                "result.getContent() 应该与 trace.getFinalAnswer() 一致");

        System.out.println("协作轨迹:\n" + trace.getFormattedHistory());
    }
}