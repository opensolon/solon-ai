package features.ai.team.protocol;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamSystemPrompt;
import org.noear.solon.ai.agent.team.TeamSystemPromptCn;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.agent.react.ReActSystemPromptCn;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.prompt.Prompt;

/**
 * Supervisor 决策逻辑测试
 * <p>
 * 验证目标：
 * 1. 验证在多智能体团队中，Supervisor（主管）如何根据成员描述分发任务。
 * 2. 验证基于 AgentSession 的协作痕迹（Trace）记录与提取。
 * </p>
 */
public class TeamAgentSupervisorTest {

    /**
     * 测试：Supervisor 的标准决策路径
     */
    @Test
    public void testSupervisorDecisionLogic() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 创建两个有明显分工的 Agent
        Agent dataCollector = ReActAgent.of(chatModel)
                .name("collector")
                .description("数据收集员，负责从海量信息中抓取原始事实，不进行主观分析。")
                .build();

        Agent analyzer = ReActAgent.of(chatModel)
                .name("analyzer")
                .description("数据分析师，负责对收集到的事实进行逻辑加工，推导趋势，不负责基础收集。")
                .build();

        // 2. 构建团队智能体，默认协议通常即为 SUPERVISOR
        TeamAgent team = TeamAgent.of(chatModel)
                .name("decision_team")
                .agentAdd(dataCollector)
                .agentAdd(analyzer)
                .maxTotalIterations(10)
                .build();

        // 打印团队图结构的 YAML（包含节点与连接关系）
        System.out.println("--- 团队图结构 ---\n" + team.getGraph().toYaml());

        // 3. 使用 AgentSession 替换 FlowContext
        AgentSession session = InMemoryAgentSession.of("test_decision_session");

        // 4. 调用团队：由 Supervisor 进行初步评估并指派成员
        String query = "分析一下最近的市场趋势";
        String result = team.call(Prompt.of(query), session).getContent();

        System.out.println("Supervisor 决策结果: " + result);

        // 5. 检查轨迹：验证协作过程是否被正确记录在 Session 快照中
        // 在 3.8.x 中，TeamAgent 会自动维护 Trace，可通过接口便捷获取
        TeamTrace trace = team.getTrace(session);

        Assertions.assertNotNull(trace, "轨迹记录不应为空");
        Assertions.assertTrue(trace.getStepCount() > 0, "协作步骤应大于0");

        // 打印决策历史
        System.out.println("决策历史:\n" + trace.getFormattedHistory());
    }

    /**
     * 测试：自定义提示词提供者（控制 Supervisor 的思考逻辑）
     */
    @Test
    public void testCustomPromptProvider() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 使用 TeamPromptProviderCn.builder 方式构建自定义提示词
        // 这种方式会自动利用框架内置的结构化分段逻辑（Role, Instruction, Output Specification等）
        TeamSystemPrompt customProvider = TeamSystemPrompt.builder()
                .role("你是一个高效的任务调度主管，擅长根据专家能力分配工作。")
                .instruction(trace -> {
                    // 仅需注入增量的业务指令，基础的成员列表和输出规范由 Builder 自动维护
                    return "特殊调度逻辑：\n" +
                            "1. 如果当前任务涉及‘代码’，优先指派技术类 Agent。\n" +
                            "2. 必须参考历史记录，避免重复指派同一 Agent 执行相同指令。";
                })
                .build();

        // 2. 构建包含自定义逻辑的团队
        TeamAgent team = TeamAgent.of(chatModel)
                .name("custom_prompt_team")
                .agentAdd(ReActAgent.of(chatModel)
                        .name("worker")
                        .description("负责通用任务执行的工作者")
                        .build())
                .systemPrompt(customProvider)
                .build();

        // 3. 准备会话
        AgentSession session = InMemoryAgentSession.of("test_custom_prompt_session");

        // 4. 执行任务
        String result = team.call(Prompt.of("简单任务"), session).getContent();

        System.out.println("自定义提示词结果: " + result);

        Assertions.assertNotNull(result, "结果不应为空");

        // 验证轨迹提取
        TeamTrace trace = team.getTrace(session);
        Assertions.assertNotNull(trace);
        System.out.println("自定义模式协作轨迹:\n" + trace.getFormattedHistory());
    }

    /**
     * 测试：Supervisor 的多轮链式决策
     * 验证：A 收集完成后，Supervisor 能否感知进度并指派 B 分析
     */
    @Test
    public void testSupervisorChainDecision() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        Agent searcher = ReActAgent.of(chatModel).name("searcher")
                .description("搜索员：负责查找事实。").build();
        Agent summarizer = ReActAgent.of(chatModel).name("summarizer")
                .description("总结员：负责对事实进行简短总结。").build();

        TeamAgent team = TeamAgent.of(chatModel)
                .agentAdd(searcher)
                .agentAdd(summarizer)
                .maxTotalIterations(5)
                .build();

        AgentSession session = InMemoryAgentSession.of("session_chain_test");

        // 强制要求两步走的复杂任务
        String query = "先帮我搜一下 Solon 框架的最新版本，然后再总结它的主要特性。";
        team.call(Prompt.of(query), session);

        TeamTrace trace = team.getTrace(session);

        // 核心断言：验证是否至少涉及了两个不同的 Agent
        long workerCount = trace.getSteps().stream()
                .map(s -> s.getSource())
                .filter(name -> !name.equals(Agent.ID_SUPERVISOR)) // 排除主管自身
                .distinct().count();

        System.out.println("实际参与工作的专家数: " + workerCount);
        Assertions.assertTrue(workerCount >= 2, "Supervisor 应该指派了多个专家完成链式任务");
    }

    /**
     * 测试：无合适 Agent 时的 Supervisor 响应
     * 验证：当所有成员都不匹配时，Supervisor 是否会陷入死循环
     */
    @Test
    public void testSupervisorWithNoMatch() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        Agent javaDev = ReActAgent.of(chatModel).name("java_dev")
                .description("只懂 Java 后端开发。").build();

        TeamAgent team = TeamAgent.of(chatModel)
                .agentAdd(javaDev)
                .maxTotalIterations(3) // 设小一点，防止死循环
                .build();

        AgentSession session = InMemoryAgentSession.of("session_no_match");

        // 发送一个完全不相关的厨师任务
        String result = team.call(Prompt.of("教我如何做一顿正宗的川菜"), session).getContent();

        System.out.println("无法处理时的结果: " + result);

        TeamTrace trace = team.getTrace(session);
        Assertions.assertTrue(trace.getStepCount() <= 3, "应该在有限步数内停止");
    }

    /**
     * 测试：FinalAnswer 应该是 Coder 的代码输出，而不是 Reviewer 的审查结果
     * <p>
     * 验证场景：Coder 编写代码 -> Reviewer 审查通过 -> Supervisor 输出最终结果
     * 预期结果：finalAnswer 和 result.getContent() 应该包含 Coder 输出的 HTML 代码
     * </p>
     */
    @Test
    public void testFinalAnswer() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 定义【开发者】Agent
        Agent coder = ReActAgent.of(chatModel)
                .name("Coder")
                .description("前端开发专家，负责编写 HTML/CSS/JS 代码，并根据审查反馈进行修改。")
                .systemPrompt(ReActSystemPromptCn.builder()
                        .role("你是一位经验丰富的网页开发者，精通 HTML、JS、CSS、TailwindCSS、现代前端技术")
                        .instruction(
                                "# 任务\n" +
                                        "你需要帮助我生成一个完整的前端页面代码（简单实现，不超过500行代码），包含 HTML、CSS、JavaScript 三部分。\n" +
                                        "如果收到审查反馈，请根据反馈修改代码。\n\n" +
                                        "# 输出要求\n" +
                                        "直接输出完整的 HTML 代码，不需要用 ```html 包裹。\n" +
                                        "如果是修改，先简要说明修改点。"
                        )
                        .build())
                .build();

        // 2. 定义【审核员】Agent
        Agent reviewer = ReActAgent.of(chatModel)
                .name("Reviewer")
                .description("代码审查专家，负责检查代码质量，输出审查结果和修改建议。")
                .systemPrompt(ReActSystemPromptCn.builder()
                        .role("你是一名严格的代码审查专家")
                        .instruction(
                                "# 任务\n" +
                                        "审查前端代码的质量、规范性、功能完整性。\n\n" +
                                        "# 输出格式\n" +
                                        "- 如果代码有问题：以【需要修改】开头，列出具体问题和修改建议\n" +
                                        "- 如果代码没问题：输出【审查通过】代码质量良好，可以交付。\n\n" +
                                        "# 审查要点\n" +
                                        "1. HTML 结构是否语义化\n" +
                                        "2. CSS 样式是否合理\n" +
                                        "3. JS 逻辑是否正确\n" +
                                        "4. 是否满足原始需求"
                        )
                        .build())
                .build();

        // 3. 组建【开发小组】Team
        TeamAgent devTeam = TeamAgent.of(chatModel)
                .name("DevTeam")
                .agentAdd(coder, reviewer)
                .maxTotalIterations(8)
                .finishMarker("FINISH")
                .systemPrompt(TeamSystemPromptCn.builder()
                        .role("团队指挥")
                        .instruction("如果Reviewer审查通过，输出 FINISH 并在其后完整转发 Coder 的 HTML 代码。")
                        .build())
                .build();

        // 4. 执行任务
        AgentSession agentSession = InMemoryAgentSession.of();
        AssistantMessage result = devTeam.call(Prompt.of("帮我写一个简单的计数器页面，包含加减按钮"), agentSession);

        // 5. 获取轨迹和最终答案
        TeamTrace trace = devTeam.getTrace(agentSession);
        String finalAnswer = trace.getFinalAnswer();
        String resultContent = result.getContent();

        System.out.println("--- FinalAnswer (前200字符) ---");
        System.out.println(finalAnswer != null && finalAnswer.length() > 200
                ? finalAnswer.substring(0, 200) + "..."
                : finalAnswer);

        System.out.println("\n--- Result Content (前200字符) ---");
        System.out.println(resultContent != null && resultContent.length() > 200
                ? resultContent.substring(0, 200) + "..."
                : resultContent);

        // 6. 打印执行轨迹
        System.out.println("\n--- 执行轨迹 ---");
        for (TeamTrace.TeamStep step : trace.getSteps()) {
            String content = step.getContent();
            int len = Math.min(100, content.length());
            System.out.println("[" + step.getSource() + "] " + content.substring(0, len) + "...");
        }

        // 7. finalAnswer 应该包含 HTML 代码，而不是审查结果
        Assertions.assertNotNull(finalAnswer, "finalAnswer 不应为空");
        Assertions.assertNotNull(resultContent, "result.getContent() 不应为空");

        boolean containsHtml = finalAnswer.contains("<html") || finalAnswer.contains("<!DOCTYPE")
                || finalAnswer.contains("<div") || finalAnswer.contains("<button");
        Assertions.assertTrue(containsHtml,
                "finalAnswer 应该包含 HTML 代码（Coder 的输出），而不是审查结果。实际内容: "
                        + (finalAnswer.length() > 100 ? finalAnswer.substring(0, 100) + "..." : finalAnswer));

        // finalAnswer 不应该是 Reviewer 的审查结果
        boolean containsReview = finalAnswer.contains("【审查通过】") || finalAnswer.contains("【需要修改】");
        Assertions.assertFalse(containsReview, "finalAnswer 不应该是 Reviewer 的审查结果");

        // result.getContent() 应该与 finalAnswer 一致
        Assertions.assertEquals(finalAnswer, resultContent,
                "result.getContent() 应该与 trace.getFinalAnswer() 一致");
    }
}