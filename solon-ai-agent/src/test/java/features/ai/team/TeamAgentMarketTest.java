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
 * MarketBased 策略测试：基于能力描述的竞争指派
 */
public class TeamAgentMarketTest {

    @Test
    public void testMarketSelectionLogic() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 提供领域细分的专家
        Agent pythonExpert = ReActAgent.builder(chatModel)
                .name("python_coder")
                .description("Python 专家，擅长数据处理和自动化脚本。")
                .build();

        Agent javaExpert = ReActAgent.builder(chatModel)
                .name("java_coder")
                .description("Java 专家，擅长高并发架构设计。")
                .build();

        // 2. 使用 MARKET_BASED 策略
        TeamAgent team = TeamAgent.builder(chatModel)
                .name("market_team")
                .strategy(TeamStrategy.MARKET_BASED)
                .addAgent(pythonExpert)
                .addAgent(javaExpert)
                .build();

        FlowContext context = FlowContext.of("test_market");
        // 发起一个明显属于 Java 领域的高并发需求
        String result = team.call(context, "我需要实现一个支持每秒万级并发的支付结算网关后端。");

        System.out.println("MarketBased 决策产出: " + result);

        TeamTrace trace = team.getTrace(context);
        Assertions.assertNotNull(trace);

        // 3. 验证路由决策
        String firstAgentName = trace.getSteps().get(0).getAgentName();
        System.out.println("调解器选择的专家: " + firstAgentName);

        // 验证逻辑：针对高并发后端，LLM 应该大概率指派 java_coder
        Assertions.assertEquals("java_coder", firstAgentName, "调解器应基于市场专业度选择 Java 专家");
    }
}