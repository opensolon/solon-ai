package labs.ai;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatRequest;
import org.noear.solon.ai.chat.ChatResponseDefault;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.session.InMemoryChatSession;
import org.noear.solon.ai.llm.dialect.openai.OpenaiChatDialect;

import static org.junit.jupiter.api.Assertions.*;

/**
 *
 * @author noear 2026/7/1 created
 *
 */
public class OpenAiTest {
    @Test
    public void testStreamUsageWithEmptyChoicesChunk() {
        // 模拟 MiMo 流式响应：最后一个 chunk choices=[] 但 usage 有数据
        String[] chunks = {
                // Chunk 0-3: reasoning_content 增量（省略）
                "{\"choices\":[{\"delta\":{\"content\":\"\",\"role\":\"assistant\"},\"finish_reason\":null,\"index\":0}],\"model\":\"mimo-v2.5-pro\"}",
                "{\"choices\":[{\"delta\":{\"content\":null,\"reasoning_content\":\"thinking...\"},\"finish_reason\":null,\"index\":0}],\"model\":\"mimo-v2.5-pro\"}",
                // Chunk 4: finish_reason 设置 finished=true
                "{\"choices\":[{\"delta\":{\"content\":null},\"finish_reason\":\"length\",\"index\":0}],\"model\":\"mimo-v2.5-pro\",\"usage\":null}",
                // Chunk 5: choices=[] 但 usage 有数据 ← 触发 bug 的 chunk
                "{\"choices\":[],\"model\":\"mimo-v2.5-pro\",\"usage\":{\"completion_tokens\":5,\"prompt_tokens\":260,\"total_tokens\":265}}"
        };

        // 使用 solon-ai-dialect-openai 的 OpenaiChatDialect 解析
        OpenaiChatDialect dialect = new OpenaiChatDialect();
        ChatConfig config = new ChatConfig();

        ChatRequest req = new ChatRequest(config, dialect, config.getModelOptions(), InMemoryChatSession.builder().build(), ChatMessage.ofSystem(""), Prompt.of(""), true);
        ChatResponseDefault resp = new ChatResponseDefault(req, true);

        for (String chunk : chunks) {
            resp.reset();
            boolean parsed = dialect.parseResponseJson(config, resp, chunk);
            assertTrue(parsed);

            // Chunk 5 解析后：
            // - isFinished() = true（从 Chunk 4 继承）
            // - hasChoices() 应该 = true（parseResponseJson 应该补了空 choice）
            // - getUsage() 应该 != null
        }

        // 最终断言
        assertNotNull(resp.getUsage(), "usage 不应为 null");
        assertEquals(5, resp.getUsage().completionTokens());
        assertEquals(260, resp.getUsage().promptTokens());
        assertEquals(265, resp.getUsage().totalTokens());
    }
}
