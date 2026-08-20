package labs.ai;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatRequest;
import org.noear.solon.ai.chat.ChatResponseDefault;
import org.noear.solon.ai.chat.message.AssistantMessage;
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

    @Test
    public void testDeepSeekUsageParsing() {
        // DeepSeek 思考模式 usage：无 think_tokens，走 completion_tokens_details.reasoning_tokens + prompt_cache_hit_tokens
        String json = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"你好\"},\"finish_reason\":\"stop\",\"index\":0}],"
                + "\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":88,\"total_tokens\":100,"
                + "\"prompt_cache_hit_tokens\":10,\"prompt_cache_miss_tokens\":2,"
                + "\"completion_tokens_details\":{\"reasoning_tokens\":60}}}";

        OpenaiChatDialect dialect = new OpenaiChatDialect();
        ChatConfig config = new ChatConfig();
        ChatRequest req = new ChatRequest(config, dialect, config.getModelOptions(), InMemoryChatSession.builder().build(), ChatMessage.ofSystem(""), Prompt.of(""), false);
        ChatResponseDefault resp = new ChatResponseDefault(req, false);

        assertTrue(dialect.parseResponseJson(config, resp, json));

        assertNotNull(resp.getUsage());
        assertEquals(12, resp.getUsage().promptTokens());
        assertEquals(88, resp.getUsage().completionTokens());
        assertEquals(100, resp.getUsage().totalTokens());
        // 思考 token 应从 completion_tokens_details.reasoning_tokens 提取
        assertEquals(60, resp.getUsage().thinkTokens());
        // 缓存命中应从顶层 prompt_cache_hit_tokens 提取
        assertEquals(10, resp.getUsage().cacheReadInputTokens());
    }

    @Test
    public void testLegacyUsageFieldsStillWork() {
        // 兼容形态：think_tokens + prompt_tokens_details.cached_tokens（部分国产模型仍使用）
        String json = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"你好\"},\"finish_reason\":\"stop\",\"index\":0}],"
                + "\"usage\":{\"prompt_tokens\":5,\"think_tokens\":20,\"completion_tokens\":30,\"total_tokens\":35,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":4}}}";

        OpenaiChatDialect dialect = new OpenaiChatDialect();
        ChatConfig config = new ChatConfig();
        ChatRequest req = new ChatRequest(config, dialect, config.getModelOptions(), InMemoryChatSession.builder().build(), ChatMessage.ofSystem(""), Prompt.of(""), false);
        ChatResponseDefault resp = new ChatResponseDefault(req, false);

        assertTrue(dialect.parseResponseJson(config, resp, json));

        assertEquals(20, resp.getUsage().thinkTokens());
        assertEquals(4, resp.getUsage().cacheReadInputTokens());
    }

    @Test
    public void testStreamThinkingChunksParsing() {
        // DeepSeek 思考模式流式 chunk：思考增量 → 正文 → 工具调用
        String[] chunks = {
                "{\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"\",\"reasoning_content\":\"第一步思考\"},\"finish_reason\":null,\"index\":0}],\"model\":\"deepseek-reasoner\"}",
                "{\"choices\":[{\"delta\":{\"content\":null,\"reasoning_content\":\"第二步思考\"},\"finish_reason\":null,\"index\":0}],\"model\":\"deepseek-reasoner\"}",
                "{\"choices\":[{\"delta\":{\"content\":\"正文\",\"reasoning_content\":\"\"},\"finish_reason\":null,\"index\":0}],\"model\":\"deepseek-reasoner\"}",
                "{\"choices\":[{\"delta\":{\"content\":null,\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"北京\\\"}\"}}]},\"finish_reason\":\"tool_calls\",\"index\":0}],\"model\":\"deepseek-reasoner\"}"
        };

        OpenaiChatDialect dialect = new OpenaiChatDialect();
        ChatConfig config = new ChatConfig();
        ChatRequest req = new ChatRequest(config, dialect, config.getModelOptions(), InMemoryChatSession.builder().build(), ChatMessage.ofSystem(""), Prompt.of(""), true);
        ChatResponseDefault resp = new ChatResponseDefault(req, true);

        // Chunk 1：首段思考 → 产出 <think> 标记消息 + 思维链消息
        resp.reset();
        assertTrue(dialect.parseResponseJson(config, resp, chunks[0]));
        assertEquals(2, resp.getChoices().size());
        assertTrue(resp.getChoices().get(0).getMessage().isThinking());
        assertEquals("<think>", resp.getChoices().get(0).getMessage().getContent());
        assertEquals("第一步思考", resp.getChoices().get(1).getMessage().getThinking());

        // Chunk 2：中间思考增量
        resp.reset();
        assertTrue(dialect.parseResponseJson(config, resp, chunks[1]));
        assertEquals(1, resp.getChoices().size());
        assertEquals("第二步思考", resp.getChoices().get(0).getMessage().getThinking());
        assertTrue(resp.getChoices().get(0).getMessage().isThinking());

        // Chunk 3：思考结束 → 产出 </think> 标记消息 + 正文消息
        resp.reset();
        assertTrue(dialect.parseResponseJson(config, resp, chunks[2]));
        assertEquals(2, resp.getChoices().size());
        assertEquals("</think>", resp.getChoices().get(0).getMessage().getContent());
        assertFalse(resp.getChoices().get(1).getMessage().isThinking());
        assertEquals("正文", resp.getChoices().get(1).getMessage().getContent());

        // Chunk 4：工具调用
        resp.reset();
        assertTrue(dialect.parseResponseJson(config, resp, chunks[3]));
        AssistantMessage toolMsg = resp.getMessage();
        assertNotNull(toolMsg.getToolCalls());
        assertEquals("get_weather", toolMsg.getToolCalls().get(0).getName());
    }
}
