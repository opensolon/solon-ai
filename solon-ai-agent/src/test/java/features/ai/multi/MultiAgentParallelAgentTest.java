package features.ai.multi;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.multi.MultiAgent;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Graph;

/**
 *
 * @author noear 2025/12/30 created
 *
 */
public class MultiAgentParallelAgentTest {
    @Test
    public void testParallelAgents() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 定义子 Agent
        Agent enTranslator = ReActAgent.builder(chatModel).nameAs("en_translator")
                .systemPromptProvider(c -> "你负责将文本翻译为英文").build();
        Agent frTranslator = ReActAgent.builder(chatModel).nameAs("fr_translator")
                .systemPromptProvider(c -> "你负责将文本翻译为法文").build();

        // 2. 定义并行图
        Graph parallelGraph = Graph.create("parallel_translator", spec -> {
            spec.addStart("start").linkAdd("parallel_gate");

            // 并行分发
            spec.addParallel("parallel_gate")
                    .linkAdd("en_translator")
                    .linkAdd("fr_translator");

            // 两个并行任务
            spec.addActivity("en_translator").task(enTranslator).linkAdd("join_node");
            spec.addActivity("fr_translator").task(frTranslator).linkAdd("join_node");

            // 汇聚节点：处理并行结果的合并
            spec.addParallel("join_node").task((ctx,n) -> {
                String en = ctx.getAs("answer"); // 注意：这里需要更细的 Key 管理来区分并行结果
                // 在生产中，建议在 Agent.run 之后将结果存入特定的 key，如 answer_en, answer_fr
                // 此时 history 中已经有了两者的记录
                String history = ctx.getAs(Agent.KEY_HISTORY);
                ctx.put(Agent.KEY_ANSWER, "汇总结果：\n" + history);
            }).linkAdd("end");

            spec.addEnd("end");
        });

        Agent team = new MultiAgent(parallelGraph);
        FlowContext context = FlowContext.of("parallel_1");

        long start = System.currentTimeMillis();
        String result = team.ask(context, "你好，世界");
        long end = System.currentTimeMillis();

        System.out.println(result);
        // 并行执行下，总耗时应显著小于两个 Agent 顺序执行之和
        System.out.println("并行翻译总耗时: " + (end - start) + "ms");
    }
}
