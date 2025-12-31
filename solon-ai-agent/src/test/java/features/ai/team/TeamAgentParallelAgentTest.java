package features.ai.team;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Graph;
import java.util.stream.Collectors;

public class TeamAgentParallelAgentTest {
    @Test
    public void testParallelAgents() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        String teamName = "parallel_translator";

        // 1. 定义子 Agent
        Agent enTranslator = ReActAgent.builder(chatModel).nameAs("en_translator")
                .systemPromptProvider(c -> "你负责将文本翻译为英文").build();
        Agent frTranslator = ReActAgent.builder(chatModel).nameAs("fr_translator")
                .systemPromptProvider(c -> "你负责将文本翻译为法文").build();

        // 2. 定义并行图
        Graph parallelGraph = Graph.create(teamName, spec -> {
            spec.addStart("start").linkAdd("parallel_gate");

            // 并行分发
            spec.addParallel("parallel_gate")
                    .linkAdd("en_translator")
                    .linkAdd("fr_translator");

            spec.addActivity("en_translator").task(enTranslator).linkAdd("join_node");
            spec.addActivity("fr_translator").task(frTranslator).linkAdd("join_node");

            // 汇聚节点：直接从 TeamTrace 提取各并行分支的结果
            spec.addParallel("join_node").task((ctx, n) -> {
                TeamTrace trace = ctx.getAs("__" + teamName);
                if (trace != null) {
                    String summary = trace.getSteps().stream()
                            .map(s -> "[" + s.getAgentName() + "]: " + s.getContent())
                            .collect(Collectors.joining("\n\n"));
                    ctx.put(Agent.KEY_ANSWER, "多语言翻译汇总：\n" + summary);
                }
            }).linkAdd("end");

            spec.addEnd("end");
        });

        // 3. 运行
        TeamAgent team = new TeamAgent(parallelGraph).nameAs(teamName);
        FlowContext context = FlowContext.of("parallel_1");

        long start = System.currentTimeMillis();
        String result = team.ask(context, "你好，世界");
        long end = System.currentTimeMillis();

        System.out.println(result);
        System.out.println("并行翻译总耗时: " + (end - start) + "ms");
    }
}