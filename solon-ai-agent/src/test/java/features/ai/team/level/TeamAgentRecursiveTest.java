package features.ai.team.level;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamSystemPrompt;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TeamAgent 递归与循环协作测试
 * * <p>场景 1: 测试嵌套团队（Team in Team），验证 Trace 轨迹在父子团队间的正确记录。</p>
 * <p>场景 2: 测试反馈循环（Feedback Loop），验证当审核不通过时，流程能重新回到研发节点。</p>
 */
public class TeamAgentRecursiveTest {
    private static final Logger log = LoggerFactory.getLogger(TeamAgentRecursiveTest.class);

    /**
     * 测试：嵌套团队协作
     * 验证：父团队（项目组）调用子团队（研发组）时，各层级的 Trace 均能正确生成且不触发死循环。
     */
    @Test
    public void testNestedTeam() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 底层团队：研发小组 (dev_team)
        TeamAgent devTeam = TeamAgent.of(chatModel)
                .name("dev_team")
                .description("研发小组。输入需求，直接给出代码实现。完成后必须回复：[FINISH] 研发已完成")
                .addAgent(createSimpleAgent("Coder", "负责写代码"))
                .maxTotalIterations(2) // 严格限制子团队迭代次数
                .build();

        // 2. 顶层团队：项目管理组 (project_team)
        TeamAgent projectTeam = TeamAgent.of(chatModel)
                .name("project_team")
                .systemPrompt(TeamSystemPrompt.builder()
                        .role("你是一个严谨的项目主管。")
                        .instruction("1. 先指派 Analyst 进行分析；" +
                                     "2. 拿到分析结果后指派 dev_team 执行；" +
                                     "3. 当 dev_team 回复完成后，直接输出 [FINISH]。")
                        .build())
                .addAgent(createSimpleAgent("Analyst", "需求分析师"))
                .addAgent(devTeam) // 嵌套子团队
                .maxTotalIterations(5)
                .build();

        // 可视化结构：打印图定义的 YAML
        System.out.println("--- Project Team Graph ---\n" + projectTeam.getGraph().toYaml() + "\n---");

        // 3. 使用 AgentSession 替代 FlowContext
        AgentSession session = InMemoryAgentSession.of("sn_recursive_2026");

        log.info(">>> 开始嵌套团队调用测试...");
        // 核心：在 Prompt 中明确要求一次性处理
        String promptText = "请 Java 程序员帮我写一个 Hello World。完成后直接结束。";
        projectTeam.call(Prompt.of(promptText), session).getContent();

        // 4. 结果验证：从 session 的快照中提取 Trace
        // 在 3.8.x 中，TeamAgent 会将 Trace 存入 snapshot (即 FlowContext)
        TeamTrace rootTrace = session.getSnapshot().getAs("__project_team");
        TeamTrace subTrace = session.getSnapshot().getAs("__dev_team");

        if (rootTrace != null) {
            log.info("父团队执行路径: {}", String.join(" -> ",
                    rootTrace.getSteps().stream().map(s -> s.getAgentName()).toArray(String[]::new)));
        }

        Assertions.assertNotNull(rootTrace, "父团队 Trace 记录丢失");
        Assertions.assertTrue(rootTrace.getIterationsCount() < 5, "触发了非预期的高频迭代，可能存在逻辑死循环");

        // 子团队轨迹也应存在（如果被 Supervisor 调度到）
        log.info("子团队是否存在轨迹: {}", (subTrace != null));
    }

    /**
     * 测试：反馈修正循环
     * 验证：当 Reviewer 给出打回意见时，TeamAgent 能自动调度回 dev_team 进行修复。
     */
    @Test
    public void testFeedbackLoop() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 简单的开发子团队
        TeamAgent devTeam = TeamAgent.of(chatModel).name("dev_team")
                .description("代码实现小组")
                .addAgent(createSimpleAgent("Coder", "程序员"))
                .build();

        // 2. 带有审核逻辑的顶层团队
        TeamAgent projectTeam = TeamAgent.of(chatModel).name("quality_project")
                .description("带质检的项目组。如果结果不满意，Reviewer 会要求重写。")
                .addAgent(devTeam)
                .addAgent(new Agent() {
                    private int reviewCount = 0;
                    @Override public String name() { return "Reviewer"; }
                    @Override public String description() { return "代码审核员"; }

                    @Override
                    public AssistantMessage call(Prompt prompt, AgentSession session) {
                        // 第一次调用打回，第二次调用通过
                        if (reviewCount++ == 0) {
                            return ChatMessage.ofAssistant("代码发现安全漏洞，请 dev_team 重新修复！");
                        }
                        return ChatMessage.ofAssistant("审核通过，表现完美。[FINISH]");
                    }
                })
                .maxTotalIterations(10)
                .build();

        // 3. 执行任务
        AgentSession session = InMemoryAgentSession.of("sn_feedback_loop_2026");
        projectTeam.call(Prompt.of("请开发一个登录模块。"), session);

        // 4. 关键检测点：验证反馈循环是否生效
        TeamTrace rootTrace = session.getSnapshot().getAs("__quality_project");
        Assertions.assertNotNull(rootTrace, "执行轨迹丢失");

        // 验证 1：验证是否出现了打回重做的路径（dev_team 应被多次调用）
        long devTeamCalls = rootTrace.getSteps().stream()
                .filter(s -> "dev_team".equalsIgnoreCase(s.getAgentName())).count();

        log.info("反馈循环中 dev_team 被激活次数: {}", devTeamCalls);
        Assertions.assertTrue(devTeamCalls >= 2, "当审核打回时，Supervisor 应该重新路由回 dev_team");

        // 验证 2：验证最终 Trace 是否捕获到了审核通过的终态消息
        boolean hasApproval = rootTrace.getSteps().stream()
                .anyMatch(s -> "Reviewer".equals(s.getAgentName()) && s.getContent().contains("表现完美"));

        Assertions.assertTrue(hasApproval, "Trace 中应包含 Reviewer 的最终确认记录");
    }

    /**
     * 创建一个模拟简单回复的智能体
     */
    private static Agent createSimpleAgent(String name, String desc) {
        return new Agent() {
            @Override public String name() { return name; }
            @Override public String description() { return desc; }
            @Override
            public AssistantMessage call(Prompt prompt, AgentSession session) {
                // 返回带完成标记的模拟数据
                return ChatMessage.ofAssistant("[Result from " + name + "]: 任务处理完毕。 [FINISH]");
            }
        };
    }
}