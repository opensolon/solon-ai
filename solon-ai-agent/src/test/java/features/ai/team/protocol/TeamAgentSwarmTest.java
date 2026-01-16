package features.ai.team.protocol;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
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
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.MethodToolProvider;

import java.util.List;
import java.util.stream.Collectors;

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

    @Test
    @DisplayName("生产级 Swarm：自循环防御与结构化任务涌现")
    public void testSwarmProductionRobustness() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 定义 Agent A：设定清晰的拒绝逻辑和移交目标
        Agent agentA = ReActAgent.of(chatModel).name("AgentA")
                .description("处理器 A。仅负责常规咨询。若遇到'棘手'或'山芋'类任务，必须移交给 AgentB。")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("初级客服")
                        .instruction("1. 分析用户输入。若包含'山芋'，判定为权限不足。\n" +
                                "2. 权限不足时，必须调用 transfer_to 工具移交给 AgentB。\n" +
                                "3. 回复格式：[A已阅] + 移交动作。")
                        .build()).build();

        // 2. 定义 Agent B：设定回流逻辑，制造循环压力
        Agent agentB = ReActAgent.of(chatModel).name("AgentB")
                .description("处理器 B。负责复杂技术问题。若认为任务属于流程性推诿，可移回给 AgentA。")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("技术专家")
                        .instruction("1. 若收到来自 AgentA 的'山芋'任务且感到处理冗余，必须尝试移回 AgentA 或寻求高级主管。\n" +
                                "2. 必须使用移交工具，不得擅自终结任务。\n" +
                                "3. 回复格式：[B已阅] + 移交动作。")
                        .build()).build();

        // 3. 定义 Cleaner：作为“低信息素”浓度的全局出口
        //
        Agent cleaner = ReActAgent.of(chatModel).name("Cleaner")
                .description("全局异常清理员。专门处理 AgentA 和 AgentB 无法达成共识的死循环任务。")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("高级决策主管")
                        .instruction("1. 审查历史对话。如果你发现 A 和 B 正在重复移交同一任务，请立即介入。\n" +
                                "2. 你的职责是强制终止无效循环。输出：[任务终止：检测到逻辑死循环，转人工处理]。")
                        .build()).build();

        // 4. 构建 Swarm 团队
        TeamAgent team = TeamAgent.of(chatModel)
                .name("Swarm_Defense_Team")
                .protocol(TeamProtocols.SWARM) // Swarm 协议会自动根据使用频率计算惩罚分（Pheromone）
                .agentAdd(agentA, agentB, cleaner)
                .maxTotalIterations(10) // 给予足够的空间观察避障行为
                .build();

        AgentSession session = InMemoryAgentSession.of("swarm_robust_001");

        // 5. 执行诱导性任务
        System.out.println(">>> 正在启动 Swarm 压力测试...");
        String result = team.call(Prompt.of("请处理这个烫手山芋"), session).getContent();

        // --- 生产级深度断言 ---
        TeamTrace trace = team.getTrace(session);
        List<String> order = trace.getSteps().stream()
                .filter(TeamTrace.TeamStep::isAgent)
                .map(TeamTrace.TeamStep::getSource)
                .collect(java.util.stream.Collectors.toList());

        System.out.println("==== 最终执行链 ====");
        System.out.println(String.join(" -> ", order));
        System.out.println("==== 最终回复 ====");
        System.out.println(result);

        //

        // 断言 1：验证 AgentA 受到信息素惩罚（不应无限占用 CPU）
        long aCount = order.stream().filter(s -> s.equals("AgentA")).count();
        Assertions.assertTrue(aCount <= 2, "Swarm 避障失效：AgentA 出现次数过多(" + aCount + ")，说明惩罚机制未生效");

        // 断言 2：验证 Cleaner 节点的“任务涌现”
        // 当 A 和 B 互相刷高了对方的浓度，Cleaner 应该由于其 description 的匹配度和低浓度被选中
        Assertions.assertTrue(order.contains("Cleaner"), "博弈失效：Cleaner 节点未能在死循环中被调度唤醒");

        // 断言 3：验证执行顺序逻辑
        if (order.size() >= 3) {
            Assertions.assertEquals("AgentA", order.get(0), "首位处理者应为 AgentA");
        }

        // 断言 4：结果闭环
        Assertions.assertTrue(result.contains("任务终止") || result.contains("人工"), "最终产出不符合安全终止规范");
    }

    @Test
    @DisplayName("验证 Swarm 的子任务涌现协议逻辑 - 协议加固版")
    public void testSwarmSubTaskProtocol() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. Lead Agent：强化 JSON 契约。
        // 在不使用工具的情况下，必须确保输出的 JSON 纯净且 agent 名称精确。
        Agent lead = ReActAgent.of(chatModel).name("Lead")
                .description("项目组长。负责将复杂需求拆解为具体子任务。")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("项目组长")
                        .instruction("1. 分析用户需求。\n" +
                                "2. 必须在回复的最后，输出严格格式的 JSON 子任务块。\n" +
                                "3. 严禁使用 Markdown 代码块包裹 JSON。\n" +
                                "4. 格式：{\"sub_tasks\": [{\"task\": \"工作内容\", \"agent\": \"Worker\"}]}")
                        .build()).build();

        // 2. Worker Agent：执行者
        Agent worker = ReActAgent.of(chatModel).name("Worker")
                .description("具体执行者。接收子任务并实施。")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("技术员")
                        .instruction("执行分配给你的具体工作。完成后总结结果。")
                        .build()).build();

        TeamAgent team = TeamAgent.of(chatModel)
                .name("Swarm_Protocol_Team")
                .protocol(TeamProtocols.SWARM)
                .agentAdd(lead, worker)
                .maxTotalIterations(5)
                .build();

        AgentSession session = InMemoryAgentSession.of("test_protocol_emergence");

        // 执行任务
        String result = team.call(Prompt.of("请帮我处理一个紧急工单并由 Worker 执行。"), session).getContent();

        // --- 生产级深度断言 ---
        TeamTrace trace = team.getTrace(session);
        List<String> order = trace.getSteps().stream()
                .filter(TeamTrace.TeamStep::isAgent)
                .map(TeamTrace.TeamStep::getSource)
                .collect(Collectors.toList());

        System.out.println("Swarm 执行路径: " + String.join(" -> ", order));

        // 1. 验证 Worker 是否被激活（核心：验证框架对 Lead 输出的 sub_tasks JSON 的解析能力）
        boolean workerExecuted = order.contains("Worker");
        Assertions.assertTrue(workerExecuted, "Swarm 任务涌现失败：Worker 节点未被激活。请检查 Lead 的 JSON 输出格式。");

        // 2. 验证时序逻辑
        if (workerExecuted) {
            Assertions.assertTrue(order.indexOf("Lead") < order.indexOf("Worker"), "任务时序异常：执行者在拆解者之前运行");
        }

        // 3. 验证结果闭环
        Assertions.assertNotNull(result, "最终产出不应为空");
    }

    /**
     * 财务计算工具：为 Swarm 增加硬核数据支撑
     */
    public static class FinanceTools {
        @ToolMapping(description = "计算滞期费与汇率风险损失")
        public String calculateLoss(double cargoValue, int delayDays) {
            double loss = cargoValue * 0.001 * delayDays; // 每日千分之一滞期费
            return String.format("预计滞期损失: %.2f 万美金 (基于延时 %d 天)", loss / 10000, delayDays);
        }
    }

    /**
     * 测试：生产级 Swarm 复杂协作
     * 场景：跨境电商供应链风险处置。
     * 流程：风险识别 -> (涌现) 物流调度 + 财务对冲 -> 品牌公关 -> 最终报告
     */
    @Test
    @DisplayName("生产级 Swarm：跨境供应链风险动态处置博弈")
    public void testSwarmProductionSupplyChainCrisis() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 风险官：入口 Agent，负责结构化任务拆解
        Agent riskOfficer = ReActAgent.of(chatModel).name("RiskOfficer")
                .description("风险控制官。负责识别风险并拆解任务。")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("风险控制官")
                        .instruction("1. 识别货物滞留风险。\n" +
                                "2. 必须生成 sub_tasks JSON。格式：{\"sub_tasks\": [{\"task\": \"重调度\", \"agent\": \"Logistics\"}, {\"task\": \"亏损评估\", \"agent\": \"Finance\"}]}\n" +
                                "3. 必须通过调用移交工具同时通知 Logistics 和 Finance。")
                        .build()).build();

        // 2. 物流专家：故意制造“延时冲突”以触发 PR 审计博弈
        Agent logistics = ReActAgent.of(chatModel).name("Logistics")
                .description("物流专家。负责航线重调。")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("物流调度专家")
                        .instruction("1. 提出备选航线（如绕道好望角）。\n" +
                                "2. **关键约束**：初次方案必须声明‘预计延时 5 天’以观察合规反应。\n" +
                                "3. 任务完成后，必须移交给 PR_Expert 进行告知函审查。")
                        .build()).build();

        // 3. 财务专家：挂载计算工具，提供量化数据
        Agent finance = ReActAgent.of(chatModel).name("Finance")
                .description("财务分析师。负责损失核算。")
                .toolAdd(new MethodToolProvider(new FinanceTools()))
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("首席财务官")
                        .instruction("1. 调用 calculate_loss 工具计算损失。\n" +
                                "2. 输出结论必须包含具体的损失金额。")
                        .build()).build();

        // 4. 公关专家：扮演“质量闸门”，触发打回逻辑
        Agent prExpert = ReActAgent.of(chatModel).name("PR_Expert")
                .description("公关专家。负责对外口径审计。")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("公关合规官")
                        .instruction("1. 审计物流方案。如果延时超过 3 天，必须输出【方案驳回】并指派 Logistics 重新优化。\n" +
                                "2. 只有延时 <= 3 天，才输出正式的【客户告知函】。")
                        .build()).build();

        // 5. 组建 Swarm 团队
        TeamAgent crisisTeam = TeamAgent.of(chatModel)
                .name("SupplyChain_Crisis_Swarm")
                .protocol(TeamProtocols.SWARM)
                .agentAdd(riskOfficer, logistics, finance, prExpert)
                .maxTotalIterations(12)
                .build();

        AgentSession session = InMemoryAgentSession.of("crisis_001");
        String query = "由于苏伊士运河突发堵塞，我们有 5000 万美金的货物滞留，请立即处置。";

        System.out.println(">>> 启动 Swarm 跨境危机处置程序...");
        String result = crisisTeam.call(Prompt.of(query), session).getContent();

        // --- 深度生产断言 ---
        TeamTrace trace = crisisTeam.getTrace(session);
        List<String> order = trace.getSteps().stream()
                .filter(TeamTrace.TeamStep::isAgent)
                .map(TeamTrace.TeamStep::getSource)
                .collect(Collectors.toList());

        System.out.println("执行链路: " + String.join(" -> ", order));

        // 1. 验证去中心化入口逻辑
        Assertions.assertEquals("RiskOfficer", order.get(0), "Swarm 必须从首个注册 Agent 开始");

        // 2. 验证多维覆盖：Finance 必须参与（由于 RiskOfficer 的任务拆解）
        Assertions.assertTrue(order.contains("Finance"), "Swarm 未能触发 Finance 财务评估任务");
        Assertions.assertTrue(order.contains("Logistics"), "Swarm 未能触发 Logistics 重调度任务");

        // 3. 验证博弈往返：Logistics 和 PR 应该有至少一次“打回-重做”的交锋
        //
        boolean hasBout = false;
        for (int i = 0; i < order.size() - 1; i++) {
            if (order.get(i).equals("PR_Expert") && order.get(i + 1).equals("Logistics")) {
                hasBout = true;
                break;
            }
        }
        Assertions.assertTrue(hasBout, "PR 与 Logistics 之间的打回博弈未触发（可能由于 Prompt 约束不严）");

        // 4. 验证信息素避障：防止在推诿中死循环
        int maxRepeat = 0, currentRepeat = 1;
        for (int i = 1; i < order.size(); i++) {
            if (order.get(i).equals(order.get(i - 1))) {
                currentRepeat++;
            } else {
                maxRepeat = Math.max(maxRepeat, currentRepeat);
                currentRepeat = 1;
            }
        }
        Assertions.assertTrue(maxRepeat <= 2, "信息素避障失效，出现了过长的单一节点连续循环");

        // 5. 验证最终产出：必须经过 PR 审核通过
        Assertions.assertTrue(result.contains("告知函") || result.contains("处理完毕"), "最终报告缺失关键告知内容");

        System.out.println("===== 最终处置报告 =====");
        System.out.println(result);
    }
}