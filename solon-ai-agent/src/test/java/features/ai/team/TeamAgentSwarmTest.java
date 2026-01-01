package features.ai.team;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamStrategy;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.flow.FlowContext;

/**
 * Swarm 策略测试：去中心化的接力模式
 */
public class TeamAgentSwarmTest {

    @Test
    public void testSwarmRelayLogic() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 定义翻译接力链
        Agent chineseTranslator = ReActAgent.builder(chatModel)
                .name("ChineseTranslator")
                .description("负责把用户的中文输入翻译成英文。")
                .build();

        Agent polisher = ReActAgent.builder(chatModel)
                .name("Polisher")
                .description("负责对英文文本进行文学化润色。")
                .build();

        // 2. 使用 SWARM 策略构建团队
        TeamAgent team = TeamAgent.builder(chatModel)
                .name("swarm_team")
                .strategy(TeamStrategy.SWARM)
                .addAgent(chineseTranslator) // 首位加入，Swarm 将直接从它开始执行
                .addAgent(polisher)
                .build();

        FlowContext context = FlowContext.of("test_swarm");
        String result = team.call(context, "你好，很高兴认识你");

        System.out.println("Swarm 执行结果: " + result);

        TeamTrace trace = team.getTrace(context);
        Assertions.assertNotNull(trace);

        // 验证：Swarm 模式第一步应该是 ChineseTranslator 直接执行
        Assertions.assertEquals("ChineseTranslator", trace.getSteps().get(0).getAgentName());
        // 验证：总步数应包含 Translator 和 Polisher (>= 2)
        Assertions.assertTrue(trace.getStepCount() >= 2);
    }
}