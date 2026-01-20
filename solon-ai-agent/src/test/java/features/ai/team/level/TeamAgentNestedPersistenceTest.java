package features.ai.team.level;

import demo.ai.llm.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.flow.FlowContext;

/**
 * TeamAgent 嵌套持久化测试
 * <p>验证场景：父团队包含子团队，手动模拟子团队已完成并持久化，
 * 恢复后父团队应能识别子团队状态并正确流转到下一个 Agent。</p>
 */
public class TeamAgentNestedPersistenceTest {

    @Test
    public void testNestedPersistence() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 定义底层 Agent
        Agent coder = new Agent() {
            @Override public String name() { return "Coder"; }
            @Override public String description() { return "负责编写核心业务代码"; }
            @Override public AssistantMessage call(Prompt prompt, AgentSession session) {
                return ChatMessage.ofAssistant("代码已提交: login.java");
            }
        };

        Agent reviewer = new Agent() {
            @Override public String name() { return "Reviewer"; }
            @Override public String description() { return "负责代码质量审核"; }
            @Override public AssistantMessage call(Prompt prompt, AgentSession session) {
                return ChatMessage.ofAssistant("审核通过 [FINISH]");
            }
        };

        // 2. 构建层级团队：Project (Parent) -> Dev (Child) -> Coder
        TeamAgent devTeam = TeamAgent.of(chatModel).name("dev_team").agentAdd(coder).build();
        TeamAgent projectTeam = TeamAgent.of(chatModel)
                .name("quality_project")
                .agentAdd(devTeam)
                .agentAdd(reviewer)
                .build();

        // 打印 DAG 图结构 YAML
        System.out.println("--- 层级团队图结构 ---\n" + projectTeam.getGraph().toYaml());

        // --- 阶段 1：模拟系统挂起，构建序列化快照 ---
        FlowContext context1 = FlowContext.of("p_job_1");

        // 模拟子团队 (dev_team) 的执行轨迹：已经完成
        TeamTrace devTrace = new TeamTrace();
        devTrace.addRecord(ChatRole.ASSISTANT,"Coder", "代码已提交: login.java", 100);
        devTrace.setRoute(Agent.ID_END); // 子团队标记为已结束

        // 模拟父团队 (quality_project) 的执行轨迹：已调用过子团队
        TeamTrace projectTrace = new TeamTrace(Prompt.of("开发登录功能"));
        projectTrace.addRecord(ChatRole.ASSISTANT,"dev_team", "开发环节已交付成果", 200);
        projectTrace.setRoute(TeamAgent.ID_SUPERVISOR); // 断点设在父团队的决策中心

        // 注入状态到上下文
        context1.put("__dev_team", devTrace);
        context1.put("__quality_project", projectTrace);

        // 记录断点位置，方便恢复后 Flow 引擎定位
        context1.trace().recordNodeId(projectTeam.getGraph(), TeamAgent.ID_SUPERVISOR);

        // 模拟落库序列化
        String jsonState = context1.toJson();
        System.out.println(">>> 阶段 1：嵌套状态已持久化。");

        // --- 阶段 2：恢复 Session 并验证逻辑衔接 ---
        System.out.println(">>> 阶段 2：从快照恢复并触发续跑...");

        // 从 JSON 恢复上下文并包装为 AgentSession
        FlowContext context2 = FlowContext.fromJson(jsonState);
        AgentSession session = InMemoryAgentSession.of(context2);

        // 触发调用（不传 Prompt，系统自动从 Trace 恢复）
        String result = projectTeam.call(null, session).getContent();

        // --- 验证点 ---
        TeamTrace finalTrace = projectTeam.getTrace(session);

        // 验证 1：父团队是否成功衔接到了 Reviewer
        Assertions.assertTrue(finalTrace.getFormattedHistory().contains("Reviewer"),
                "恢复后应自动识别子团队已完工，并指派 Reviewer");

        // 验证 2：最终输出内容
        Assertions.assertTrue(result.contains("通过"));

        System.out.println("最终协作轨迹:\n" + finalTrace.getFormattedHistory());
        System.out.println("最终回复: " + result);
    }
}