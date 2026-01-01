package features.ai.team.level;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.flow.FlowContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TeamAgentRecursiveTest {
    private static final Logger log = LoggerFactory.getLogger(TeamAgentRecursiveTest.class);

    @Test
    public void testNestedTeam() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 底层团队：强制其输出简洁，并带上完成标记
        TeamAgent devTeam = TeamAgent.builder(chatModel)
                .name("dev_team")
                .description("研发小组。输入需求，直接给出代码实现。完成后必须回复：[FINISH] 研发已完成")
                .addAgent(createSimpleAgent("Coder", "负责写代码"))
                .maxTotalIterations(2) // 严格限制子团队次数
                .build();

        // 2. 顶层团队
        TeamAgent projectTeam = TeamAgent.builder(chatModel)
                .name("project_team")
                .description("项目管理。先让 Analyst 分析，然后交给 dev_team 执行。")
                .addAgent(createSimpleAgent("Analyst", "需求分析师"))
                .addAgent(devTeam)
                .maxTotalIterations(5)
                .build();

        FlowContext context = FlowContext.of("sn_2026");

        log.info(">>> 开始测试...");
        // 核心改动：在 Prompt 中明确要求一次性处理
        String finalResult = projectTeam.call(context, "请 Java 程序员帮我写一个 Hello World。完成后直接结束。");

        TeamTrace rootTrace = context.getAs("__project_team");
        TeamTrace subTrace = context.getAs("__dev_team");

        // 打印简化的 Trace 路径
        if (rootTrace != null) {
            log.info("父团队路径: {}", String.join(" -> ",
                    rootTrace.getSteps().stream().map(s -> s.getAgentName()).toArray(String[]::new)));
        }

        Assertions.assertNotNull(rootTrace, "父团队 Trace 丢失");
        Assertions.assertTrue(rootTrace.getIterationsCount() < 5, "触发了死循环！日志过多通常是因为这里。");
    }

    private static Agent createSimpleAgent(String name, String desc) {
        return new Agent() {
            @Override public String name() { return name; }
            @Override public String description() { return desc; }
            @Override
            public String call(FlowContext context, Prompt prompt) {
                // 模拟一个带有明确结束意图的返回
                return "[Result from " + name + "]: 任务已处理。 [FINISH]";
            }
        };
    }

    @Test
    public void testFeedbackLoop() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 简单的开发子团队
        TeamAgent devTeam = TeamAgent.builder(chatModel).name("dev_team")
                .description("代码实现小组")
                .addAgent(createSimpleAgent("Coder", "程序员"))
                .build();

        // 2. 带有审核逻辑的顶层团队
        TeamAgent projectTeam = TeamAgent.builder(chatModel).name("quality_project")
                .description("带质检的项目组。如果结果不满意，Reviewer 会要求重写。")
                .addAgent(devTeam)
                .addAgent(new Agent() {
                    private int reviewCount = 0;
                    @Override public String name() { return "Reviewer"; }
                    @Override public String description() { return "代码审核员"; }
                    @Override public String call(FlowContext ctx, Prompt p) {
                        if (reviewCount++ == 0) {
                            return "代码发现安全漏洞，请 dev_team 重新修复！";
                        }
                        return "审核通过，表现完美。[FINISH]";
                    }
                })
                .maxTotalIterations(10)
                .build();

        FlowContext context = FlowContext.of("sn_feedback_loop");
        String result = projectTeam.call(context, "请开发一个登录模块。");

        TeamTrace rootTrace = context.getAs("__quality_project");

        // --- 关键检测点 ---

        // 1. 验证是否出现了打回重做的路径：dev_team -> Reviewer -> dev_team -> Reviewer
        long devTeamCalls = rootTrace.getSteps().stream()
                .filter(s -> "dev_team".equalsIgnoreCase(s.getAgentName())).count();

        log.info("dev_team 被调用次数: {}", devTeamCalls);
        Assertions.assertTrue(devTeamCalls >= 2, "当 Reviewer 不满意时，Supervisor 应该重新路由回 dev_team");

        // 2. 验证最终结果是否包含了审核通过的标记
        boolean hasApproval = rootTrace.getSteps().stream()
                .anyMatch(s -> "Reviewer".equals(s.getAgentName()) && s.getContent().contains("表现完美"));

        Assertions.assertTrue(hasApproval, "Trace 中应记录 Reviewer 的正面确认");
    }
}