/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package features.ai.output;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActSystemPrompt;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.simple.SimpleAgent;
import org.noear.solon.ai.agent.simple.SimpleSystemPrompt;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamProtocols;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.core.util.Assert;

/**
 * TeamAgent 数据流转与 Session 传递测试
 * * 验证：
 * 1. OutputKey 如何将 Agent 的产出持久化到 Session 变量池。
 * 2. SystemPrompt 如何通过 #{key} 实现上下文变量的动态注入。
 * 3. 结构化 JSON Schema 产出的准确性。
 */
public class TeamAgentTransferTest {

    /**
     * 测试 1：基础 OutputKey 自动捕获
     * 验证 TeamAgent 执行过程中，Agent 的回答能自动存入 Session 指定键值。
     */
    @Test
    public void testOutputKeyTransfer() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        ReActAgent translator = ReActAgent.of(chatModel)
                .name("translator")
                .description("专业翻译官")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("你是一个专业翻译专家")
                        .instruction("将输入的中文翻译为英文，直接给出译文。")
                        .build())
                .outputKey("translate_result") // 自动存入 session
                .build();

        ReActAgent polisher = ReActAgent.of(chatModel)
                .name("polisher")
                .description("润色专家")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("你是一个英文润色润色大师")
                        .instruction("对上一步 translator 产出的译文进行学术化处理。")
                        .build())
                .build();

        TeamAgent team = TeamAgent.of(chatModel)
                .name("editor_team")
                .agentAdd(translator, polisher)
                .protocol(TeamProtocols.SEQUENTIAL) // 顺序执行
                .outputKey("final_report")
                .build();

        AgentSession session = InMemoryAgentSession.of("session_001");
        team.call(Prompt.of("翻译并润色：'代码构建未来'"), session);

        String translateResult = session.getSnapshot().getAs("translate_result");
        String finalReport = session.getSnapshot().getAs("final_report");

        System.out.println("--- 步骤1产出 (translate_result): " + translateResult);
        System.out.println("--- 团队最终产出 (final_report): " + finalReport);

        Assertions.assertTrue(Assert.isNotBlank(translateResult));
        Assertions.assertTrue(Assert.isNotBlank(finalReport));
    }

    /**
     * 测试 2：变量模板强注入 (Variable Injection)
     * 验证 systemPrompt 渲染 #{translate_result} 的能力。
     */
    @Test
    public void testOutputKeyAndTemplateTransfer() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        ReActAgent translator = ReActAgent.of(chatModel)
                .name("translator")
                .outputKey("translate_result")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("翻译官")
                        .instruction("直接输出译文，不要任何前缀解释。").build())
                .build();

        ReActAgent polisher = ReActAgent.of(chatModel)
                .name("polisher")
                // 核心：框架自动解析 #{translate_result} 并替换为 Session 中的值
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("润色专家")
                        .instruction("请对这段译文进行优化：#{translate_result}")
                        .build())
                .outputKey("final_result")
                .build();

        TeamAgent team = TeamAgent.of(chatModel)
                .name("template_team")
                .agentAdd(translator, polisher)
                .protocol(TeamProtocols.SEQUENTIAL)
                .build();

        AgentSession session = InMemoryAgentSession.of("session_002");
        team.call(Prompt.of("人工智能正在改变世界"), session);

        String midResult = session.getSnapshot().getAs("translate_result");
        String finalResult = session.getSnapshot().getAs("final_result");

        System.out.println("中间翻译: " + midResult);
        System.out.println("润色结果: " + finalResult);

        Assertions.assertNotNull(midResult);
        Assertions.assertNotNull(finalResult);
    }

    /**
     * 测试 3：JSON Schema 结构化产出验证
     */
    @Test
    public void testJsonSchemaOutput() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        //

        SimpleAgent extractor = SimpleAgent.of(chatModel)
                .name("extractor")
                .description("实体关系提取器")
                .systemPrompt(p -> p
                        .role("你是一个高精度信息提取专家")
                        .instruction("从文本中提取关键实体，严格遵守 JSON Schema 规范。"))
                .outputSchema(Personnel.class)//"{\"entity_name\": \"string\", \"birth_year\": \"integer\", \"title\": \"string\"}")
                .outputKey("structured_data")
                .build();

        AgentSession session = InMemoryAgentSession.of("session_003");
        Personnel personnel = extractor.prompt("伊隆·马斯克，1971年出生，现任特斯拉CEO。")
                .session(session)
                .call()
                .toBean(Personnel.class);

        String jsonData = session.getSnapshot().getAs("structured_data");
        System.out.println("结构化结果: " + personnel.entity_name);
        System.out.println("结构化结果: " + jsonData);

        Assertions.assertTrue(jsonData.contains("entity_name"));
        Assertions.assertTrue(jsonData.contains("1971"));
        Assertions.assertTrue(jsonData.contains("CEO"));
    }

    public static class Personnel {
        String entity_name;
        int birth_year;
        String title;
    }
}