package features.ai.output;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.protocol.SequentialProtocol_H;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.core.util.Assert;

public class TeamAgentTransferTest {

    /**
     * 测试 1：基础 OutputKey 传递（验证 TeamAgent 自动捕获 Agent 结果到 Session）
     */
    @Test
    public void testOutputKeyTransfer() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        ReActAgent translator = ReActAgent.of(chatModel)
                .name("translator")
                .description("你是一个专业翻译。请将输入的中文翻译为英文。请以 'Answer: [译文内容]' 的格式回答。")
                .maxSteps(2)
                .finishMarker("Answer:")
                .outputKey("translate_result") // 存入 session
                .build();

        ReActAgent polisher = ReActAgent.of(chatModel)
                .name("polisher")
                .description("你是一个英文润色专家。请对上一步 translator 的产出进行英文润色。请以 'Answer: [润色内容]' 的格式回答。")
                .maxSteps(2)
                .finishMarker("Answer:")
                .build();

        TeamAgent team = TeamAgent.of(chatModel)
                .name("editor_team")
                .addAgent(translator, polisher)
                .protocol(SequentialProtocol_H::new)
                .outputKey("final_report") // 团队最终结果存入 final_report
                .maxTotalIterations(5)
                .build();

        AgentSession session = InMemoryAgentSession.of("session_base");
        team.call(Prompt.of("翻译并润色：'人工智能正在改变世界'"), session);

        String translateResult = session.getSnapshot().getAs("translate_result");
        String finalReport = session.getSnapshot().getAs("final_report");

        System.out.println("--- [translator] 产出: " + translateResult);
        System.out.println("--- [polisher] 产出: " + finalReport);

        Assertions.assertTrue(Assert.isNotBlank(translateResult));
        Assertions.assertTrue(Assert.isNotBlank(finalReport));
    }

    /**
     * 测试 2：变量模板注入（验证 systemPrompt 的 #{key} 动态渲染能力）
     */
    @Test
    public void testOutputKeyAndTemplateTransfer() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        ReActAgent translator = ReActAgent.of(chatModel)
                .name("translator")
                .description("你是一个翻译官，直接输出英文译文，不要解释。")
                .outputKey("translate_result")
                .build();

        ReActAgent polisher = ReActAgent.of(chatModel)
                .name("polisher")
                // 关键点：使用 systemPrompt Lambda 配合 #{key} 进行强注入
                .systemPrompt(trace -> "你是一个润色专家。请对这段译文进行优化：#{translate_result}")
                .description("请直接给出润色后的内容。")
                .outputKey("final_result")
                .build();

        TeamAgent team = TeamAgent.of(chatModel)
                .name("template_team")
                .addAgent(translator, polisher)
                .protocol(SequentialProtocol_H::new)
                .build();

        AgentSession session = InMemoryAgentSession.of("session_template");
        team.call(Prompt.of("代码改变世界"), session);

        String midResult = session.getSnapshot().getAs("translate_result");
        String finalResult = session.getSnapshot().getAs("final_result");

        System.out.println("--- 模板传递测试 ---");
        System.out.println("中间变量 (translate_result): " + midResult);
        System.out.println("最终变量 (final_result): " + finalResult);

        Assertions.assertNotNull(midResult);
        Assertions.assertTrue(finalResult.toLowerCase().contains("code"), "最终结果应包含关键词 'code'");
    }

    /**
     * 测试 3：Session 自动流转链条（验证 Agent 如何在没有人工预置数据下自主流转）
     */
    @Test
    public void testContextAndSessionChain() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        AgentSession session = InMemoryAgentSession.of("session_chain");

        // 优化：通过 description 约束模型不要因为“信息不足”而产生索要指令的幻觉
        ReActAgent preferenceLoader = ReActAgent.of(chatModel)
                .name("preference_loader")
                .description("你负责加载用户偏好。直接输出 '正式商务风格' 即可，不要向用户提问。")
                .outputKey("user_preference")
                .build();

        ReActAgent polisher = ReActAgent.of(chatModel)
                .name("polisher")
                // 通过 ${key} 在 description 中占位，框架会在路由判断时辅助模型理解上下文
                .description("参考用户偏好 ${user_preference}，对输入的翻译内容进行润色。")
                .outputKey("final_answer")
                .build();

        TeamAgent team = TeamAgent.of(chatModel)
                .name("chain_team")
                .addAgent(preferenceLoader, polisher)
                .protocol(SequentialProtocol_H::new)
                .build();

        // 初始任务只给指令，看 preference_loader 能否产出 user_preference 并传给 polisher
        team.call(Prompt.of("根据我的偏好润色翻译：'Hello World'"), session);

        Object finalAnswer = session.getSnapshot().get("final_answer");
        System.out.println("--- 链条测试最终结果: " + finalAnswer);

        Assertions.assertNotNull(finalAnswer, "Session 链条中断，polisher 未能产出结果");
    }

    /**
     * 测试 4：JSON Schema 结构化产出
     */
    @Test
    public void testJsonSchemaOutput() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        ReActAgent extractor = ReActAgent.of(chatModel)
                .name("extractor")
                .description("提取文本中的实体信息。")
                .outputSchema("{\"entity_name\": \"string\", \"category\": \"string\"}")
                .outputKey("json_data")
                .build();

        AgentSession session = InMemoryAgentSession.of("session_json");
        extractor.call(Prompt.of("马斯克在1971年出生。"), session);

        String jsonData = session.getSnapshot().getAs("json_data");
        System.out.println("--- 结构化 JSON 产出: " + jsonData);

        Assertions.assertTrue(jsonData.contains("entity_name"));
        Assertions.assertTrue(jsonData.contains("1971") || jsonData.contains("马斯克"));
    }
}