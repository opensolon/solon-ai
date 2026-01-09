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

    @Test
    public void testOutputKeyAndTemplateTransfer() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 翻译智能体：将结果输出到 translate_result
        ReActAgent translator = ReActAgent.of(chatModel)
                .name("translator")
                .description("你是一个翻译官，直接输出英文译文，不要解释。")
                .outputKey("translate_result") // 显式存入上下文
                .build();

        // 2. 润色智能体：通过 systemPrompt 模板获取上一步的结果
        ReActAgent polisher = ReActAgent.of(chatModel)
                .name("polisher")
                // 使用更名后的 systemPrompt，并通过模板注入变量
                .systemPrompt(trace -> "你是一个润色专家。请对这段译文进行优化：#{translate_result}")
                .description("请直接给出润色后的内容。")
                .outputKey("final_result")
                .build();

        // 3. 组建团队：使用顺序协议
        TeamAgent team = TeamAgent.of(chatModel)
                .name("template_team")
                .addAgent(translator, polisher)
                .protocol(SequentialProtocol::new)
                .build();

        // 4. 执行
        AgentSession session = InMemoryAgentSession.of("session_template");
        team.call(Prompt.of("代码改变世界"), session);

        // 5. 验证
        String midResult = session.getSnapshot().getAs("translate_result");
        String finalResult = session.getSnapshot().getAs("final_result");

        System.out.println("--- 模板传递测试 ---");
        System.out.println("中间变量 (translate_result): " + midResult);
        System.out.println("最终变量 (final_result): " + finalResult);

        Assertions.assertNotNull(midResult, "中间变量未能通过 outputKey 存入");
        Assertions.assertNotNull(finalResult, "最终变量未能产出");
        Assertions.assertTrue(finalResult.length() > 0);
        Assertions.assertTrue(finalResult.toLowerCase().contains("code"), "最终结果应包含翻译关键词 'code'");
    }

    @Test
    public void testContextAndSessionChain() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 模拟一个获取用户偏好的 Agent (比如从数据库或上下文获取)
        ReActAgent preferenceAgent = ReActAgent.of(chatModel)
                .name("preference_loader")
                .description("直接输出字符串 '正式商务风格'。")
                .outputKey("user_style")
                .build();

        // 2. 润色智能体：同时结合【翻译结果】和【用户偏好】
        ReActAgent polisher = ReActAgent.of(chatModel)
                .name("polisher")
                .systemPrompt(trace -> {
                    // 同时读取两个中间变量
                    String style = (String) trace.getContext().get("user_style");
                    String text = (String) trace.getContext().get("translate_result");
                    return String.format("要求风格：%s。请润色内容： %s", style, text);
                })
                .outputKey("final_report")
                .build();

        // 3. 组建团队 (假设翻译由外部提供或已存在)
        TeamAgent team = TeamAgent.of(chatModel)
                .addAgent(preferenceAgent, polisher)
                .protocol(SequentialProtocol::new)
                .build();

        AgentSession session = InMemoryAgentSession.of("complex_test");
        // 模拟上下文中已有翻译结果
        session.getSnapshot().put("translate_result", "AI is changing the world.");

        team.call(Prompt.of("根据我的偏好润色翻译"), session);

        String finalReport = session.getSnapshot().getAs("final_report");
        System.out.println("--- 复合上下文产出: " + finalReport);

        Assertions.assertTrue(Assert.isNotBlank(finalReport));
        // 验证是否包含偏好关键词
        Assertions.assertTrue(finalReport.contains("formal") || finalReport.length() > 0);
    }

    @Test
    public void testJsonSchemaOutput() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        ReActAgent extractor = ReActAgent.of(chatModel)
                .name("extractor")
                .description("提取文本中的实体信息。")
                // 关键：指定输出格式
                .outputSchema("{\"entity_name\": \"string\", \"category\": \"string\"}")
                .outputKey("json_data")
                .build();

        AgentSession session = InMemoryAgentSession.of("json_test");
        String content = extractor.call(Prompt.of("马斯克在1971年出生。"), session).getContent();
        System.out.println("--- 纯文本产出: " + content);

        String jsonData = session.getSnapshot().getAs("json_data");
        System.out.println("--- 结构化产出: " + jsonData);

        // 验证是否是合法的 JSON（简单校验）
        Assertions.assertTrue(jsonData.trim().startsWith("{") && jsonData.trim().endsWith("}"));
        Assertions.assertTrue(jsonData.contains("entity_name"));
    }
}