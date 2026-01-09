package features.ai.team.protocol;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamProtocols;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;

/**
 * MarketBased 策略测试：基于能力描述的竞争指派
 * <p>
 * 验证目标：
 * 1. 验证 MARKET_BASED 协议下，协调者能否通过语义匹配从“人才市场”中选出最合适的 Agent。
 * 2. 验证基于 AgentSession 的状态流转与 Trace 记录的完整性。
 * </p>
 */
public class TeamAgentMarketTest {

    @Test
    public void testMarketSelectionLogic() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 提供领域细分的专家
        Agent pythonExpert = ReActAgent.of(chatModel)
                .name("python_coder")
                .description("Python 专家，擅长数据处理和自动化脚本。")
                .build();

        Agent javaExpert = ReActAgent.of(chatModel)
                .name("java_coder")
                .description("Java 专家，擅长高并发架构设计、支付结算和分布式网关。")
                .build();

        // 2. 使用 MARKET_BASED 策略组建团队
        TeamAgent team = TeamAgent.of(chatModel)
                .name("market_team")
                .protocol(TeamProtocols.MARKET_BASED)
                .addAgent(pythonExpert)
                .addAgent(javaExpert)
                .build();

        // 打印市场结构 YAML
        System.out.println("--- Market-Based Team Graph ---\n" + team.getGraph().toYaml());

        // 3. 使用 AgentSession 管理会话
        AgentSession session = InMemoryAgentSession.of("session_market_01");

        // 发起一个明显属于 Java 领域的高并发需求
        String query = "我需要实现一个支持每秒万级并发的支付结算网关后端。";
        String result = team.call(Prompt.of(query), session).getContent();

        System.out.println("=== 任务结果 ===\n" + result);

        // 4. 验证决策轨迹
        TeamTrace trace = team.getTrace(session);
        Assertions.assertNotNull(trace, "应该有轨迹记录");
        Assertions.assertFalse(result.isEmpty(), "结果不应该为空");

        // 检查首位执行者
        if (trace.getStepCount() > 0) {
            String firstAgentName = trace.getSteps().get(0).getAgentName();
            System.out.println("调解器(Mediator)在市场中选择的专家: " + firstAgentName);

            // 语义期望：Java 专家应处理高并发支付网关
            boolean selectedJavaExpert = "java_coder".equals(firstAgentName);
            System.out.println("符合预期选择: " + selectedJavaExpert);
        }

        System.out.println("总步数: " + trace.getStepCount());
        System.out.println("详细协作轨迹:\n" + trace.getFormattedHistory());
    }

    @Test
    public void testMarketSelectionForPythonTask() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 创建领域专家
        Agent pythonExpert = ReActAgent.of(chatModel)
                .name("python_data_scientist")
                .description("Python 数据科学家，擅长数据分析、特征工程和机器学习建模。")
                .build();

        Agent javaExpert = ReActAgent.of(chatModel)
                .name("java_backend_engineer")
                .description("Java 后端工程师，擅长微服务、分布式系统和事务处理。")
                .build();

        TeamAgent team = TeamAgent.of(chatModel)
                .name("market_python_team")
                .protocol(TeamProtocols.MARKET_BASED)
                .addAgent(pythonExpert)
                .addAgent(javaExpert)
                .build();

        // 使用 AgentSession
        AgentSession session = InMemoryAgentSession.of("session_market_python");

        // 发起一个明显属于 Python 领域的数据分析任务
        String query = "我需要分析一个大型数据集，进行特征工程和机器学习建模，预测用户行为。";
        String result = team.call(Prompt.of(query), session).getContent();

        // 2. 轨迹验证
        TeamTrace trace = team.getTrace(session);
        Assertions.assertNotNull(trace);

        if (trace.getStepCount() > 0) {
            String selectedAgent = trace.getSteps().get(0).getAgentName();
            System.out.println("市场指派的专家: " + selectedAgent);

            boolean selectedPythonExpert = "python_data_scientist".equals(selectedAgent);
            System.out.println("符合 Python 领域匹配期望: " + selectedPythonExpert);
        }

        System.out.println("协作轨迹:\n" + trace.getFormattedHistory());
        Assertions.assertTrue(trace.getStepCount() > 0);
    }
}