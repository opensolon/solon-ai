package features.ai.team;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Graph;
import java.util.stream.Collectors;

/**
 * 并行协作测试：多语种同步翻译
 * 验证：并行分支的独立运行与结果在 Join 节点的结构化汇聚。
 */
public class TeamAgentParallelAgentTest {
    @Test
    public void testParallelAgents() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        String teamId = "parallel_translator";

        // 1. 定义翻译专家（ReAct 模式）
        Agent enTranslator = ReActAgent.builder(chatModel)
                .name("en_translator")
                .promptProvider(p-> "你是负责英语翻译的专家")
                .description("负责英语翻译的专家").build();
        Agent frTranslator = ReActAgent.builder(chatModel)
                .name("fr_translator")
                .promptProvider(p-> "你是负责法语翻译的专家")
                .description("负责法语翻译的专家").build();

        // 2. 自定义并行图：实现分发与汇聚
        Graph parallelGraph = Graph.create(teamId, spec -> {
            spec.addStart(Agent.ID_START).linkAdd("dispatch_gate");

            // 并行分发：同时激活英、法两个 Agent
            spec.addParallel("dispatch_gate")
                    .linkAdd(enTranslator.name())
                    .linkAdd(frTranslator.name());

            spec.addActivity(enTranslator).linkAdd("aggregate_node");
            spec.addActivity(frTranslator).linkAdd("aggregate_node");

            // 汇聚节点：从协作轨迹中提取各分支产出
            spec.addParallel("aggregate_node").task((ctx, n) -> {
                TeamTrace trace = ctx.getAs("__" + teamId);
                String summary = trace.getSteps().stream()
                        .map(s -> String.format("[%s]: %s", s.getAgentName(), s.getContent()))
                        .collect(Collectors.joining("\n"));
                ctx.put(Agent.KEY_ANSWER, "多语言处理完成：\n" + summary);
            }).linkAdd(Agent.ID_END);

            spec.addEnd(Agent.ID_END);
        });

        // 3. 执行任务
        TeamAgent team = new TeamAgent(parallelGraph, teamId);
        FlowContext context = FlowContext.of("sn_2025_para_01");
        String result = team.call(context, "你好，世界");

        // 4. 单测检测
        System.out.println(result);
        TeamTrace trace = context.getAs("__" + teamId);

        Assertions.assertNotNull(trace, "轨迹对象不能为空");
        Assertions.assertEquals(2, trace.getStepCount(), "并行链条应产生 2 个执行步骤");
        Assertions.assertTrue(result.contains("Hello"), "结果应包含英文翻译内容");
        Assertions.assertTrue(result.contains("Monde") || result.contains("Bonjour"), "结果应包含法语翻译内容");
    }
}