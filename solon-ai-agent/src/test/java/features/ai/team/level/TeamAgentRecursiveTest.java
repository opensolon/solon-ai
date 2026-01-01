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

/**
 * 递归团队测试
 * 验证：父团队的主管能否正确调度一个子团队，且 Trace 信息不冲突。
 */
public class TeamAgentRecursiveTest {
    private static final Logger log = LoggerFactory.getLogger(TeamAgentRecursiveTest.class);

    @Test
    public void testNestedTeam() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 定义底层子团队：专门写代码的
        // 显式指定 description，防止 TeamConfig 校验不通过
        TeamAgent devTeam = TeamAgent.builder(chatModel)
                .name("dev_team")
                .description("负责代码实现和技术研发的专业小组，包含 Java 和 Python 专家。")
                .addAgent(createSimpleAgent("JavaCoder", "擅长 Java 语言开发，熟悉 Spring 和 Solon 框架。"))
                .addAgent(createSimpleAgent("PythonCoder", "擅长 Python 语言，熟悉 AI 数据处理。"))
                .build();

        // 2. 定义顶层团队：包含需求分析师和开发小组
        TeamAgent projectTeam = TeamAgent.builder(chatModel)
                .name("project_team")
                .description("项目管理核心团队，负责从需求分析到研发交付的全流程。")
                .addAgent(createSimpleAgent("Analyst", "负责解析用户需求，将其转化为技术任务。"))
                .addAgent(devTeam) // 嵌套子团队
                .build();

        FlowContext context = FlowContext.of("sn_recursive_999");

        log.info("--- 开始调用顶层团队 ---");
        String result = projectTeam.call(context, "我们需要一个 Java 写的支付模块");
        log.info("--- 最终输出结果 ---\n{}", result);

        // 验证：Context 中应该同时存在两个团队的 Trace
        // 对应 TeamAgentBuilder 中生成的 traceKey = "__" + config.getName()
        TeamTrace rootTrace = context.getAs("__project_team");
        TeamTrace subTrace = context.getAs("__dev_team");

        Assertions.assertNotNull(rootTrace, "父团队轨迹 (__project_team) 丢失");

        // 打印父团队的执行过程，方便观察它是否路由给了 dev_team
        log.info("父团队步骤轨迹：");
        rootTrace.getSteps().forEach(s -> log.info("  [{}] -> {}", s.getAgentName(), s.getContent()));

        // 验证：父团队是否曾将任务路由给子团队
        boolean routedToSub = rootTrace.getSteps().stream()
                .anyMatch(s -> "dev_team".equalsIgnoreCase(s.getAgentName()));

        if (routedToSub) {
            Assertions.assertNotNull(subTrace, "父团队已指派子团队，但子团队轨迹 (__dev_team) 丢失");
            log.info("子团队步骤轨迹：");
            subTrace.getSteps().forEach(s -> log.info("  [{}] -> {}", s.getAgentName(), s.getContent()));
        }

        Assertions.assertTrue(routedToSub, "父团队主管未能识别并指派任务给子团队 'dev_team'");
    }

    /**
     * 创建一个简单的 Agent 实例，确保所有属性非空
     */
    private Agent createSimpleAgent(String name, String description) {
        return new Agent() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public String call(FlowContext context, Prompt prompt) {
                log.info("Agent [{}] 正在处理任务...", name);
                return "这是来自 " + name + " 的处理结果：已准备好相关模块。";
            }
        };
    }
}