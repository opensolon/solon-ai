package features.ai.output;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.intercept.StopLoopInterceptor;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.protocol.SequentialProtocol;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.core.util.Assert;

public class TeamAgentTransferTest {

    @Test
    public void testOutputKeyTransfer() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 优化翻译智能体配置
        ReActAgent translator = ReActAgent.of(chatModel)
                .name("translator")
                // 重点：在提示词中明确 ReAct 的输出格式，要求其输出 Answer
                .description("你是一个专业翻译。请将输入的中文翻译为英文。请以 'Answer: [译文内容]' 的格式回答并结束任务。")
                .maxSteps(2) // 翻译这种非工具调用任务，2步足够
                .finishMarker("Answer:") // 关键：识别这个前缀作为结束标志
                .outputKey("translate_result")
                .addInterceptor(new StopLoopInterceptor(2))
                .build();

        // 2. 优化润色智能体配置
        ReActAgent polisher = ReActAgent.of(chatModel)
                .name("polisher")
                .description("你是一个英文润色专家。请对传入的译文进行优化。请以 'Answer: [润色内容]' 的格式回答并结束任务。")
                .maxSteps(2)
                .finishMarker("Answer:")
                .build();

        // 3. 组建团队
        TeamAgent team = TeamAgent.of(chatModel)
                .name("editor_team")
                .addAgent(translator, polisher)
                .protocol(SequentialProtocol::new)
                .outputKey("final_report")
                // 限制团队总循环，防止协议层面的死循环
                .maxTotalIterations(5)
                .build();

        // 4. 执行测试
        AgentSession session = InMemoryAgentSession.of("demo1");

        try {
            // 使用简短直接的指令，降低模型的推理开销
            team.call(Prompt.of("翻译并润色：'人工智能正在改变世界'"), session);
        } catch (Exception e) {
            // 打印出 Trace 信息，查看模型最后到底说了什么
            System.err.println("Execution failed: " + e.getMessage());
            throw e;
        }

        // 5. 验证
        String translateResult = session.getSnapshot().getAs("translate_result");
        String finalReport = session.getSnapshot().getAs("final_report");

        System.out.println("--- [translator] 产出: " + translateResult);
        System.out.println("--- [polisher] 产出: " + finalReport);

        Assertions.assertTrue(Assert.isNotBlank(translateResult), "中间翻译结果丢失！");
        Assertions.assertTrue(Assert.isNotBlank(finalReport), "最终报告结果丢失！");
    }
}