package features.ai.team.level;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamProtocols;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.flow.FlowContext;

/**
 * TeamAgent 嵌套持久化测试
 * <p>验证场景：父团队包含子团队，手动模拟子团队已完成并持久化，
 * 恢复后父团队应能识别子团队状态并正确流转到下一个 Agent。</p>
 * <p>使用 SEQUENTIAL 协议 + 确定性 mock Agent，避免 LLM 网关抖动。</p>
 */
public class TeamAgentNestedPersistenceTest {

    @Test
    public void testNestedPersistence() throws Throwable {
        // 1. 定义底层 Agent
        Agent coder = new Agent() {
            @Override public String name() { return "Coder"; }
            @Override public String role() { return "负责编写核心业务代码"; }
            @Override public AssistantMessage call(Prompt prompt, AgentSession session) {
                return ChatMessage.ofAssistant("代码已提交: login.java");
            }
        };

        Agent reviewer = new Agent() {
            @Override public String name() { return "Reviewer"; }
            @Override public String role() { return "负责代码质量审核"; }
            @Override public AssistantMessage call(Prompt prompt, AgentSession session) {
                return ChatMessage.ofAssistant("审核通过");
            }
        };

        // 2. 构建层级团队：Project (Parent) -> Dev (Child) -> Coder
        // 父/子均用 SEQUENTIAL，保证恢复后按物理顺序衔接到 Reviewer
        TeamAgent devTeam = TeamAgent.of(null)
                .name("dev_team")
                .role("开发执行小组")
                .instruction("负责根据任务需求完成代码实现与初步自测。")
                .protocol(TeamProtocols.SEQUENTIAL)
                .feedbackMode(false)
                .agentAdd(coder)
                .build();

        TeamAgent projectTeam = TeamAgent.of(null)
                .name("quality_project")
                .role("质量管理项目组")
                .instruction("负责协调开发小组进行功能实现，并指派审核员进行质量终审。")
                .protocol(TeamProtocols.SEQUENTIAL)
                .feedbackMode(false)
                .agentAdd(devTeam)
                .agentAdd(reviewer)
                .build();

        System.out.println("--- 层级团队图结构 ---\n" + projectTeam.getGraph().toYaml());

        // --- 阶段 1：模拟系统挂起，构建序列化快照 ---
        FlowContext context1 = FlowContext.of("p_job_1");

        // 模拟子团队 (dev_team) 的执行轨迹：已经完成
        TeamTrace devTrace = new TeamTrace(Prompt.of("开发登录功能"));
        devTrace.addRecord(ChatRole.ASSISTANT, "Coder", "代码已提交: login.java", 100);
        devTrace.setRoute(Agent.ID_END);
        devTrace.setFinalAnswer("代码已提交: login.java");

        // 模拟父团队 (quality_project) 的执行轨迹：已调用过子团队
        // originalPrompt 必须保留，否则 prompt() 空续跑无法恢复任务上下文
        TeamTrace projectTrace = new TeamTrace(Prompt.of("开发登录功能"));
        projectTrace.addRecord(ChatRole.ASSISTANT, "dev_team", "开发环节已交付成果", 200);
        // SEQUENTIAL 断点：回到 routing，由流水线推进到 Reviewer
        projectTrace.setRoute(org.noear.solon.ai.agent.team.protocol.SequentialProtocol.ID_ROUTING);

        context1.put("__dev_team", devTrace);
        context1.put("__quality_project", projectTrace);
        context1.trace().recordNodeId(projectTeam.getGraph(),
                org.noear.solon.ai.agent.team.protocol.SequentialProtocol.ID_ROUTING);

        String jsonState = context1.toJson();
        System.out.println(">>> 阶段 1：嵌套状态已持久化。");

        // --- 阶段 2：恢复 Session 并验证逻辑衔接 ---
        System.out.println(">>> 阶段 2：从快照恢复并触发续跑...");

        FlowContext context2 = FlowContext.fromJson(jsonState);
        AgentSession session = InMemoryAgentSession.of(context2);

        String result = projectTeam.prompt().session(session).call().getContent();

        TeamTrace finalTrace = projectTeam.getTrace(session);
        Assertions.assertNotNull(finalTrace, "父团队轨迹丢失");

        // 验证 1：父团队是否成功衔接到了 Reviewer
        Assertions.assertTrue(finalTrace.getFormattedHistory().contains("Reviewer"),
                "恢复后应自动识别子团队已完工，并指派 Reviewer。history=" + finalTrace.getFormattedHistory());

        // 验证 2：最终输出内容
        Assertions.assertTrue(result != null && result.contains("通过"),
                "最终输出应包含审核结论: " + result);

        System.out.println("最终协作轨迹:\n" + finalTrace.getFormattedHistory());
        System.out.println("最终回复: " + result);
    }
}
