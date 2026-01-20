package features.ai.team.def;

import demo.ai.llm.LlmUtil;
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
 */
public class TeamAgentParallelAgentTest {

    @Test
    public void testParallelAgents() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        String teamId = "parallel_translator";

        // ============== 1. 优化系统提示词 (System Prompt) ==============

        // 英语翻译专家：引入职业角色与负向约束
        Agent enTranslator = ReActAgent.of(chatModel)
                .name("en_translator")
                .description("负责中英高保真翻译")
                .systemPrompt(p->p
                        .role("你是一个资深的中英同声传译专家")
                        .instruction("### 任务要求\n" +
                                "1. 将输入内容翻译为地道的英语。\n" +
                                "2. **禁止**输出任何解释、引导词或标点说明。\n" +
                                "3. **直接**返回译文文本。"))
                .build();

        // 法语翻译专家：引入语境深度
        Agent frTranslator = ReActAgent.of(chatModel)
                .name("fr_translator")
                .description("负责中法地道表达翻译")
                .systemPrompt(p->p
                        .role("你是一个精通法语文化的翻译专家")
                        .instruction("### 任务要求\n" +
                                "1. 将输入内容翻译为准确的法语。\n" +
                                "2. 确保用词符合当地表达习惯。\n" +
                                "3. **仅**输出翻译结果，不要回复除译文外的任何内容。"))
                .build();

        // ============== 2. 自定义 Team 图结构 (逻辑不变) ==============



        TeamAgent team = TeamAgent.of(null)
                .name(teamId)
                .graphAdjuster(spec -> {
                    spec.addStart(Agent.ID_START).linkAdd("dispatch_gate");

                    spec.addParallel("dispatch_gate").title("翻译分发")
                            .linkAdd(enTranslator.name())
                            .linkAdd(frTranslator.name());

                    spec.addActivity(enTranslator).linkAdd("aggregate_node");
                    spec.addActivity(frTranslator).linkAdd("aggregate_node");

                    spec.addParallel("aggregate_node").title("结果汇聚").task((ctx, n) -> {
                        TeamTrace trace = ctx.getAs("__" + teamId);
                        if (trace != null) {
                            String summary = trace.getRecords().stream()
                                    .map(s -> String.format("[%s]: %s", s.getSource(), s.getContent().trim()))
                                    .collect(Collectors.joining("\n"));
                            trace.setFinalAnswer("多语言翻译处理完成：\n" + summary);
                        }
                    }).linkAdd(Agent.ID_END);

                    spec.addEnd(Agent.ID_END);
                })
                .build();

        System.out.println("=== Team Graph Structure ===\n" + team.getGraph().toYaml());

        // 3. 执行
        AgentSession session = InMemoryAgentSession.of("sn_2026_para_01");
        String result = team.call(Prompt.of("你好，世界"), session).getContent();

        // 4. 结果检测
        System.out.println("=== 最终汇聚结果 ===\n" + result);

        TeamTrace trace = team.getTrace(session);
        Assertions.assertNotNull(trace);
        Assertions.assertTrue(trace.getRecordCount() >= 2);
        Assertions.assertTrue(result.contains("Hello") || result.contains("world"));
        Assertions.assertTrue(result.contains("Monde") || result.contains("Bonjour"));
    }
}