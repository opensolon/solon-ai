package features.ai.team.def;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;

import java.util.stream.Collectors;

/**
 * 并行协作测试：多语种同步翻译
 * <p>验证目标：
 * 1. 验证并行分发节点（Parallel Gate）能否同时激活多个子智能体。
 * 2. 验证汇聚节点（Aggregate Node）能否从协作轨迹中结构化汇聚不同分支的产出结果。
 * </p>
 */
public class TeamAgentParallelAgentTest {

    @Test
    public void testParallelAgents() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        String teamId = "parallel_translator";

        // 1. 定义翻译专家（采用 ReAct 模式以支持更复杂的逻辑推理）
        Agent enTranslator = ReActAgent.of(chatModel)
                .name("en_translator")
                .promptProvider(p -> "你是一个专业的英语翻译专家。请直接输出翻译结果，不要输出多余的解释。")
                .description("负责英语翻译的专家")
                .build();

        Agent frTranslator = ReActAgent.of(chatModel)
                .name("fr_translator")
                .promptProvider(p -> "你是一个专业的法语翻译专家。请直接输出翻译结果，不要输出多余的解释。")
                .description("负责法语翻译的专家")
                .build();

        // 2. 自定义 Team 图结构：实现分发与汇聚
        TeamAgent team = TeamAgent.of(null) // 此处传 null 因为逻辑完全由 graphAdjuster 定义
                .name(teamId)
                .graphAdjuster(spec -> {
                    // [开始] -> [并行网关]
                    spec.addStart(Agent.ID_START).linkAdd("dispatch_gate");

                    // 并行分发：同时激活英、法两个翻译 Agent
                    spec.addParallel("dispatch_gate").title("翻译分发")
                            .linkAdd(enTranslator.name())
                            .linkAdd(frTranslator.name());

                    // 定义 Agent 执行节点并指向汇聚点
                    spec.addActivity(enTranslator).linkAdd("aggregate_node");
                    spec.addActivity(frTranslator).linkAdd("aggregate_node");

                    // 汇聚节点：自定义 Task，从 TeamTrace 中提取各分支协作结果
                    spec.addParallel("aggregate_node").title("结果汇聚").task((ctx, n) -> {
                        // 通过 team.getTrace(session) 的底层逻辑获取轨迹
                        TeamTrace trace = ctx.getAs("__" + teamId);
                        if (trace != null) {
                            String summary = trace.getSteps().stream()
                                    .map(s -> String.format("[%s]: %s", s.getAgentName(), s.getContent()))
                                    .collect(Collectors.joining("\n"));
                            trace.setFinalAnswer("多语言翻译处理完成：\n" + summary);
                        }
                    }).linkAdd(Agent.ID_END);

                    spec.addEnd(Agent.ID_END);
                })
                .build();

        // 打印图结构（YAML），便于可视化调试
        System.out.println("=== Team Graph Structure ===\n" + team.getGraph().toYaml());

        // 3. 使用 AgentSession 替代 FlowContext 执行
        AgentSession session = InMemoryAgentSession.of("sn_2026_para_01");
        String result = team.call(Prompt.of("你好，世界"), session).getContent();

        // 4. 结果检测
        System.out.println("=== 最终汇聚结果 ===\n" + result);

        // 获取协作轨迹进行精细化验证
        TeamTrace trace = team.getTrace(session);

        Assertions.assertNotNull(trace, "协作轨迹对象不能为空");
        Assertions.assertTrue(trace.getStepCount() >= 2, "并行链条应至少产生 2 个执行步骤（英/法翻译）");

        // 验证翻译结果的准确性
        Assertions.assertTrue(result.contains("Hello") || result.contains("world"), "结果应包含英文翻译内容");
        Assertions.assertTrue(result.contains("Monde") || result.contains("Bonjour"), "结果应包含法语翻译内容");
    }
}