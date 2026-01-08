package features.ai.team.level;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.flow.FlowContext;

public class TeamAgentNestedPersistenceTest {

    @Test
    public void testNestedPersistence() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        Agent coder = new Agent() {
            @Override public String name() { return "Coder"; }
            @Override public String description() { return "程序员"; }
            @Override public AssistantMessage call(AgentSession session, Prompt p) { return ChatMessage.ofAssistant("代码: login.java"); }
        };

        Agent reviewer = new Agent() {
            @Override public String name() { return "Reviewer"; }
            @Override public String description() { return "审核员"; }
            @Override public AssistantMessage call(AgentSession session, Prompt p) { return ChatMessage.ofAssistant("OK [FINISH]"); }
        };

        TeamAgent projectTeam = TeamAgent.of(chatModel)
                .name("quality_project")
                .addAgent(TeamAgent.of(chatModel).name("dev_team").addAgent(coder).build())
                .addAgent(reviewer)
                .build();

        String yaml = projectTeam.getGraph().toYaml();

        System.out.println("------------------\n\n");
        System.out.println(yaml);
        System.out.println("\n\n------------------");

        // 阶段 1：构建快照并持久化
        FlowContext context1 = FlowContext.of("p_job_1");
        TeamTrace devTrace = new TeamTrace();
        devTrace.addStep("Coder", "代码: login.java", 100);
        devTrace.setRoute(Agent.ID_END);

        TeamTrace projectTrace = new TeamTrace(Prompt.of("开发登录"));
        projectTrace.addStep("dev_team", "开发已就绪", 200);

        // 设置手动断点
        context1.trace().recordNodeId(projectTeam.getGraph(), Agent.ID_SUPERVISOR);
        context1.put("__dev_team", devTrace);
        context1.put("__quality_project", projectTrace);

        String jsonState = context1.toJson();

        // 阶段 2：恢复并验证逻辑衔接
        FlowContext context2 = FlowContext.fromJson(jsonState);
        String result = projectTeam.call(context2).getContent();

        TeamTrace finalTrace = context2.getAs("__quality_project");
        Assertions.assertTrue(finalTrace.getFormattedHistory().contains("Reviewer"), "恢复后应衔接 Reviewer 环节");
        Assertions.assertTrue(result.contains("OK"));
    }
}