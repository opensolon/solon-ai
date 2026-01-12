package features.ai.team.graph;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.simple.SimpleAgent;
import org.noear.solon.ai.agent.simple.SimpleSystemPrompt;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.flow.NodeSpec;

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
                        createTddAgent(chatModel, "reviewer", "审查专家", "请检查代码规范性和安全性，给出 Pass/Fail 结论。")
                )
                .graphAdjuster(spec -> {
                    // 2. 质量检查逻辑（作为流程结束前的校验）
                    spec.addActivity("quality_check")
                            .task((ctx, node) -> {
                                System.out.println(">>> [Node] 最终质量合规性检查通过");
                            })
                            .linkAdd(Agent.ID_END); // 必须连接到结束节点

                    // --- 3. 路由精准编排 ---

                    // A. 修改入口：由 Supervisor 直接触发 Tester
                    NodeSpec supervisor = spec.getNode("supervisor");
                    supervisor.getLinks().clear();
                    supervisor.linkAdd("tester");

                    // B. TDD 线性链路：Tester -> Developer -> Reviewer
                    spec.getNode("tester").getLinks().clear();
                    spec.getNode("tester").linkAdd("developer");

                    spec.getNode("developer").getLinks().clear();
                    spec.getNode("developer").linkAdd("reviewer");

                    // C. Reviewer -> Quality Check
                    spec.getNode("reviewer").getLinks().clear();
                    spec.getNode("reviewer").linkAdd("quality_check");
                })
                .outputKey("tdd_final_report")
                .build();

        // 4. 执行流程
        AgentSession session = InMemoryAgentSession.of("session_tdd_01");
        String query = "实现一个用户登录功能，包含用户名密码验证和记住我选项";
        team.call(Prompt.of(query), session);

        // 5. 轨迹与逻辑验证
        TeamTrace trace = team.getTrace(session);
        List<String> executionOrder = trace.getSteps().stream()
                .map(TeamTrace.TeamStep::getSource)
                .filter(name -> !name.equals("supervisor")) // 过滤掉主管节点
                .collect(Collectors.toList());

        System.out.println("TDD 协作流水线: " + String.join(" -> ", executionOrder));

        // 检测点 1: TDD 核心顺序（测试先行）
        Assertions.assertEquals("tester", executionOrder.get(0), "流程必须以测试专家开始");
        Assertions.assertTrue(executionOrder.contains("developer"), "流程必须包含开发环节");
        Assertions.assertTrue(executionOrder.contains("reviewer"), "流程必须包含审查环节");

        // 检测点 2: 验证节点完整性
        Assertions.assertTrue(executionOrder.contains("quality_check"), "质量检查节点未被触发");

        // 检测点 3: 最终结果
        String finalResult = trace.getSteps().get(trace.getStepCount() - 1).getContent();
        Assertions.assertNotNull(finalResult, "最终代码审查报告不能为空");
    }

    private Agent createTddAgent(ChatModel chatModel, String name, String role, String instruction) {
        return SimpleAgent.of(chatModel)
                .name(name)
                .systemPrompt(SimpleSystemPrompt.builder()
                        .role(role)
                        .instruction("你是" + role + "。" + instruction + " 不要输出多余的解释，直接输出代码或报告。")
                        .build())
                .build();
    }
}