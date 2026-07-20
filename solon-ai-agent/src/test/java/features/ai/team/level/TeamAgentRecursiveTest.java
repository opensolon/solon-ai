package features.ai.team.level;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamProtocols;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * TeamAgent 递归与循环协作测试
 * <p>场景 1: 测试嵌套团队（Team in Team），验证 Trace 轨迹在父子团队间的正确记录。</p>
 * <p>场景 2: 测试反馈修正循环（Feedback Loop），验证审核不通过时可重新回到研发节点。</p>
 * <p>使用 SEQUENTIAL + 确定性 mock Agent，避免 LLM 网关抖动导致的假失败。</p>
 */
public class TeamAgentRecursiveTest {
    private static final Logger log = LoggerFactory.getLogger(TeamAgentRecursiveTest.class);

    /**
     * 测试：嵌套团队协作
     * 验证：父团队调用子团队时，各层级 Trace 均能正确生成且不触发死循环。
     */
    @Test
    public void testNestedTeam() throws Throwable {
        AtomicInteger coderCalls = new AtomicInteger();
        AtomicInteger analystCalls = new AtomicInteger();

        // 1. 底层团队：研发小组 (dev_team)
        TeamAgent devTeam = TeamAgent.of(null)
                .name("dev_team")
                .role("研发小组")
                .instruction("输入需求，直接给出代码实现。")
                .protocol(TeamProtocols.SEQUENTIAL)
                .feedbackMode(false)
                .agentAdd(createCountingAgent("Coder", "负责写代码", coderCalls, "Hello World 代码已完成"))
                .maxTurns(3)
                .build();

        // 2. 顶层团队：项目管理组 (project_team)
        TeamAgent projectTeam = TeamAgent.of(null)
                .name("project_team")
                .role("严谨的项目主管")
                .instruction("先分析再交由 dev_team 执行。")
                .protocol(TeamProtocols.SEQUENTIAL)
                .feedbackMode(false)
                .agentAdd(createCountingAgent("Analyst", "需求分析师", analystCalls, "需求已澄清：输出 Hello World"))
                .agentAdd(devTeam)
                .maxTurns(5)
                .build();

        System.out.println("--- Project Team Graph ---\n" + projectTeam.getGraph().toYaml() + "\n---");

        AgentSession session = InMemoryAgentSession.of("sn_recursive_2026");

        log.info(">>> 开始嵌套团队调用测试...");
        String promptText = "请 Java 程序员帮我写一个 Hello World。完成后直接结束。";
        String result = projectTeam.prompt(Prompt.of(promptText)).session(session).call().getContent();

        TeamTrace rootTrace = session.getContext().getAs("__project_team");
        TeamTrace subTrace = session.getContext().getAs("__dev_team");

        Assertions.assertNotNull(rootTrace, "父团队 Trace 记录丢失");
        Assertions.assertTrue(rootTrace.getRecordCount() >= 2,
                "父团队应至少调度 Analyst 与 dev_team。history=" + rootTrace.getFormattedHistory());
        Assertions.assertTrue(rootTrace.getTurnCount() < 5, "触发了非预期的高频迭代，可能存在逻辑死循环");

        Assertions.assertNotNull(subTrace, "子团队 Trace 应被写入会话上下文");
        Assertions.assertTrue(subTrace.getRecordCount() >= 1, "子团队应至少有一次专家产出");
        Assertions.assertEquals(1, analystCalls.get(), "Analyst 应被调用一次");
        Assertions.assertEquals(1, coderCalls.get(), "Coder 应被调用一次");
        Assertions.assertTrue(result != null && result.contains("Hello World"),
                "最终结果应包含子团队产出: " + result);

        log.info("父团队执行路径: {}", rootTrace.getRecords().stream()
                .map(TeamTrace.TeamRecord::getSource).collect(Collectors.joining(" -> ")));
        log.info("子团队轨迹存在: {}, records={}", true, subTrace.getRecordCount());
    }

    /**
     * 测试：反馈修正循环
     * 验证：当 Reviewer 首次打回时，通过再次调用父团队可重新调度 dev_team 修复。
     */
    @Test
    public void testFeedbackLoop() throws Throwable {
        AtomicInteger coderCalls = new AtomicInteger();
        AtomicInteger reviewCount = new AtomicInteger();

        TeamAgent devTeam = TeamAgent.of(null).name("dev_team")
                .role("代码实现小组")
                .instruction("负责根据主管要求编写或修复代码实现。")
                .protocol(TeamProtocols.SEQUENTIAL)
                .feedbackMode(false)
                .agentAdd(createCountingAgent("Coder", "程序员", coderCalls, "Controller 代码已提交"))
                .build();

        Agent reviewer = new Agent() {
            @Override public String name() { return "Reviewer"; }
            @Override public String role() { return "代码审核员"; }

            @Override
            public AssistantMessage call(Prompt prompt, AgentSession session) {
                if (reviewCount.getAndIncrement() == 0) {
                    return ChatMessage.ofAssistant("代码发现安全漏洞，请 dev_team 重新修复！");
                }
                return ChatMessage.ofAssistant("审核通过，表现完美。");
            }
        };

        // 第一轮：dev_team -> Reviewer（打回）
        TeamAgent projectTeam = TeamAgent.of(null).name("quality_project")
                .role("严谨的代码项目主管")
                .instruction("开发后必须审核；审核不通过则再次开发。")
                .protocol(TeamProtocols.SEQUENTIAL)
                .feedbackMode(false)
                .agentAdd(devTeam)
                .agentAdd(reviewer)
                .maxTurns(10)
                .build();

        AgentSession session = InMemoryAgentSession.of("sn_feedback_loop_2026");
        String first = projectTeam.prompt(Prompt.of("请使用 Java 编写一个简单的登录 Controller 接口。"))
                .session(session)
                .call()
                .getContent();

        System.out.println("=====第一轮输出=====");
        System.out.println(first);

        TeamTrace rootTrace1 = session.getContext().getAs(projectTeam.getConfig().getTraceKey());
        Assertions.assertNotNull(rootTrace1, "执行轨迹丢失");
        System.out.println("--- Round1 History ---\n" + rootTrace1.getFormattedHistory());

        // 第二轮：再次发起，模拟“审核打回后重新开发+复审”
        String second = projectTeam.prompt(Prompt.of("请根据审核意见修复安全漏洞，并再次提交审核。"))
                .session(session)
                .call()
                .getContent();

        System.out.println("=====第二轮输出=====");
        System.out.println(second);

        TeamTrace rootTrace2 = session.getContext().getAs(projectTeam.getConfig().getTraceKey());
        Assertions.assertNotNull(rootTrace2, "第二轮轨迹丢失");
        System.out.println("--- Round2 History ---\n" + rootTrace2.getFormattedHistory());

        // 跨两轮：dev_team 至少被激活 2 次；Reviewer 至少 2 次；最终应通过
        Assertions.assertTrue(coderCalls.get() >= 2,
                "当审核未通过时，应重新指派 dev_team 修复。coderCalls=" + coderCalls.get());
        Assertions.assertTrue(reviewCount.get() >= 2,
                "应完成至少两轮审核。reviewCount=" + reviewCount.get());

        List<String> routeHistory2 = rootTrace2.getRecords().stream()
                .filter(TeamTrace.TeamRecord::isAgent)
                .map(TeamTrace.TeamRecord::getSource)
                .collect(Collectors.toList());
        Assertions.assertTrue(routeHistory2.size() >= 2, "第二轮至少应有开发与审核步骤");
        Assertions.assertEquals("dev_team", routeHistory2.get(0), "第二轮首位执行者应该是 dev_team");

        boolean hasApproval = rootTrace2.getRecords().stream()
                .anyMatch(s -> "Reviewer".equals(s.getSource()) && s.getContent().contains("表现完美"));
        Assertions.assertTrue(hasApproval, "最终结果应包含 Reviewer 的通过确认");
        Assertions.assertNotNull(rootTrace2.getFinalAnswer(), "任务结束时应有最终答案产出");

        log.info("反馈循环中 Coder 被激活次数: {}", coderCalls.get());
    }

    private static Agent createCountingAgent(String name, String desc, AtomicInteger counter, String content) {
        return new Agent() {
            @Override public String name() { return name; }
            @Override public String role() { return desc; }
            @Override
            public AssistantMessage call(Prompt prompt, AgentSession session) {
                counter.incrementAndGet();
                return ChatMessage.ofAssistant("[Result from " + name + "]: " + content);
            }
        };
    }
}
