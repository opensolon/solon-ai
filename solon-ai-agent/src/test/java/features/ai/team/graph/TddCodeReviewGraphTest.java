package features.ai.team.graph;

import demo.ai.llm.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.simple.SimpleAgent;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.stream.Collectors;

/**
 * TDD代码审查流程测试
 * 场景：测试先行 (Tester) -> 代码实现 (Developer) -> 代码审查 (Reviewer) -> 质量闭环
 */
public class TddCodeReviewGraphTest {

    @Test
    @DisplayName("测试 TDD Graph：验证测试先行流程与代码交付链路")
    public void testCodeReviewProcess() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 定义专家：使用 SimpleAgent，确保输出的是纯净的代码或用例
        TeamAgent team = TeamAgent.of(chatModel)
                .name("tdd_team")
                .agentAdd(
                        createTddAgent(chatModel, "tester", "测试专家", "请为需求编写 JUnit 测试用例。"),
                        createTddAgent(chatModel, "developer", "开发专家", "请根据测试用例实现业务代码。"),
                        createTddAgent(chatModel, "reviewer", "审查专家", "请检查代码规范性，给出 Pass/Fail 结论。")
                )
                .graphAdjuster(spec -> {
                    // --- 节点定义与链路紧凑排列 ---

                    // A. 入口：主管 -> 测试专家
                    spec.getNode("supervisor").linkClear().linkAdd("tester");

                    // B. 测试专家 -> 开发专家
                    spec.getNode("tester").linkClear().linkAdd("developer");

                    // C. 开发专家 -> 审查专家
                    spec.getNode("developer").linkClear().linkAdd("reviewer");

                    // D. 审查专家 -> 质量检查 (Activity)
                    spec.getNode("reviewer").linkClear().linkAdd("quality_check");

                    // E. 质量检查 Activity -> 结束
                    spec.addActivity("quality_check")
                            .title("质量检查")
                            .task((ctx, node) -> {
                                ctx.put("quality_verified", true);
                                System.out.println(">>> [Node] 最终质量合规性检查通过");
                            })
                            .linkAdd(Agent.ID_END);
                })
                .build();

        // 2. 执行流程
        AgentSession session = InMemoryAgentSession.of("session_tdd_01");
        String query = "实现一个用户登录功能，包含用户名密码验证和记住我选项";
        // 修改为 .prompt(prompt).session(session).call() 风格
        team.prompt(Prompt.of(query)).session(session).call();

        // 3. 结果验证 (Agent 走 Trace，Activity 走 Context)
        TeamTrace trace = team.getTrace(session);
        List<String> agentSteps = trace.getRecords().stream()
                .map(TeamTrace.TeamRecord::getSource)
                .collect(Collectors.toList());

        System.out.println("TDD 协作流水线: " + String.join(" -> ", agentSteps));

        // --- 断言逻辑优化 ---

        // 检测点 1: TDD 核心顺序验证（Agent 轨迹）
        Assertions.assertTrue(agentSteps.size() >= 3, "AI 协作步骤不足");
        Assertions.assertEquals("tester", agentSteps.get(0), "TDD 流程必须以测试先行（tester）");

        int testerIdx = agentSteps.indexOf("tester");
        int developerIdx = agentSteps.indexOf("developer");
        int reviewerIdx = agentSteps.indexOf("reviewer");

        Assertions.assertTrue(testerIdx < developerIdx, "顺序错误：测试必须在开发之前");
        Assertions.assertTrue(developerIdx < reviewerIdx, "顺序错误：开发必须在审查之前");

        // 检测点 2: 验证 Activity 节点执行（不走 Trace，检查 Context）
        Assertions.assertTrue(session.getSnapshot().<Boolean>getAs("quality_verified"), "质量检查 Activity 未被触发");

        // 检测点 3: 最终审查报告验证
        String finalReport = trace.getRecords().get(trace.getRecordCount() - 1).getContent();
        Assertions.assertNotNull(finalReport, "最终代码审查报告不应为空");

        System.out.println("单元测试成功：TDD 协作链路完整。");
    }

    private Agent createTddAgent(ChatModel chatModel, String name, String role, String instruction) {
        // 修改为 role(x).instruction(y) 风格替代 systemPrompt
        return SimpleAgent.of(chatModel)
                .name(name)
                .role(role)
                .instruction("你是" + role + "。" + instruction + " 请直接输出结果，不要有额外解释。")
                .build();
    }
}