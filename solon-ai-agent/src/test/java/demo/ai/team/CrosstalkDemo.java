package demo.ai.team;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamProtocols;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.flow.FlowContext;

/**
 * 快速测试相声团队
 */
public class CrosstalkDemo {
    @Test
    public void testQuickCrosstalk() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        TeamAgent crosstalkTeam = TeamAgent.of(chatModel)
                .name("crosstalk")
                .protocol(TeamProtocols.SWARM)
                .addAgent(ReActAgent.of(chatModel)
                        .name("aaa")
                        .description("阿飞")
                        .promptProvider(c -> "你是一个智能体名字叫“阿飞”，演逗哏。将跟另一个叫“阿紫”的智能体，表演相声。每句话不要超过50个字")
                        .build())
                .addAgent(ReActAgent.of(chatModel)
                        .name("bbb")
                        .description("阿紫")
                        .promptProvider(c -> "你是一个智能体名字叫“阿紫”，演捧哏。将跟另一个叫“阿飞”的智能体，表演相声。每句话不要超过50个字")
                        .build())
                .maxTotalIterations(5)  // 给点余量
                .build();

        FlowContext ctx = FlowContext.of("quick_test");
        String result = crosstalkTeam.call(ctx,"让阿飞和阿紫吵个架");

        System.out.println("=== 快速测试 ===");

        // 获取并打印完整轨迹
        TeamTrace trace = crosstalkTeam.getTrace(ctx);
        if (trace != null) {
            System.out.println("\n=== 完整表演记录("+trace.getStepCount()+") ===");
            trace.getSteps().forEach(step -> {
                System.out.printf("\n%s：\n", step.getAgentName());
                System.out.println(step.getContent());
                System.out.println("----------------------------");
            });
        }

        System.out.println("\n=== 最终结果 ===");
        System.out.println(result);
    }
}