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
import org.noear.solon.expression.snel.SnEL;

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

    @Test
    public void testOutputKeyTransfer2() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 翻译智能体：产出 translate_result
        ReActAgent translator = ReActAgent.of(chatModel)
                .name("translator")
                .description("你是一个专业翻译。请将用户输入的中文翻译为英文。请务必以 'Answer: [译文内容]' 格式回答。")
                .maxSteps(2)
                .finishMarker("Answer:")
                .outputKey("translate_result") // 结果存入 Session 的 translate_result 键
                .build();

        // 2. 润色智能体：从 Session 获取 translate_result 进行处理
        // 技巧：在 description 中明确告诉它输入来自上一步的结果变量
        ReActAgent polisher = ReActAgent.of(chatModel)
                .name("polisher")
                .systemPrompt(trace -> SnEL.evalTmpl("#{translate_result} xxx", trace.getContext().model()) )
                .description("你是一个英文润色专家。" +
                        "请获取上一步的翻译结果：${translate_result}，并对其进行地道化润色。" +
                        "请务必以 'Answer: [润色后的译文]' 格式回答。")
                .maxSteps(2)
                .finishMarker("Answer:")
                .outputKey("final_report") // 结果存入 Session 的 final_report 键
                .build();

        // 3. 组建团队：使用顺序协议 (SequentialProtocol)
        TeamAgent team = TeamAgent.of(chatModel)
                .name("editor_team")
                .addAgent(translator, polisher)
                .protocol(SequentialProtocol::new) // 确保 translator 先跑，polisher 后跑
                .maxTotalIterations(5)
                .build();

        // 4. 执行测试：所有 Agent 共享同一个 session
        AgentSession session = InMemoryAgentSession.of("session_" + System.currentTimeMillis());

        try {
            // 输入原始文本
            team.call(Prompt.of("人工智能正在改变世界"), session);
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }

        // 5. 验证数据流向
        String translateResult = session.getSnapshot().getAs("translate_result");
        String finalReport = session.getSnapshot().getAs("final_report");

        System.out.println("--- [1. Translator Result] ---");
        System.out.println(translateResult);
        System.out.println("--- [2. Polisher Final Report] ---");
        System.out.println(finalReport);

        // 断言验证
        Assertions.assertTrue(Assert.isNotBlank(translateResult), "中间翻译结果 [translate_result] 未能存入 Session！");
        Assertions.assertTrue(Assert.isNotBlank(finalReport), "最终润色结果 [final_report] 未能存入 Session！");

        // 验证 polisher 是否真的拿到了 translator 的内容（通过长度或内容包含判断）
        Assertions.assertNotEquals(translateResult, finalReport, "润色结果不应与原翻译完全一致，说明数据流或润色环节可能存在异常。");
    }
}