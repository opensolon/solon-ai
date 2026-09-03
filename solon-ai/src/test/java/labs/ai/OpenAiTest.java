package labs.ai;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.ChatAccumulator;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatRequest;
import org.noear.solon.ai.chat.ChatResponseDefault;
import org.noear.solon.ai.chat.event.ChatStreamContextDefault;
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
    /**
     * 走方言的唯一解析入口；单测不关心事件，故用「不发事件」的上下文
     */
    private void parse(OpenaiChatDialect dialect, ChatConfig config, ChatAccumulator acc, String json) {
        dialect.parseResponseJson(ChatStreamContextDefault.ofNoEmit(config, acc), json);
    }

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
        ChatAccumulator acc = new ChatAccumulator(req, true);

        for (String chunk : chunks) {
            acc.reset();
            parse(dialect, config, acc, chunk);
        }

        // 最后一帧（choices=[] 但带 usage）：终态下必须补位空内容项，且 usage 要落到累积器
        assertTrue(acc.hasContentItems(), "choices=[] 的 usage 帧仍应补出空内容项");
        assertNotNull(acc.getUsage(), "usage 不应为 null");
        assertEquals(5, acc.getUsage().completionTokens());
        assertEquals(260, acc.getUsage().promptTokens());
        assertEquals(265, acc.getUsage().totalTokens());
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
        ChatAccumulator acc = new ChatAccumulator(req, false);

        parse(dialect, config, acc, json);

        assertNotNull(acc.getUsage());
        assertEquals(12, acc.getUsage().promptTokens());
        assertEquals(88, acc.getUsage().completionTokens());
        assertEquals(100, acc.getUsage().totalTokens());
        // 思考 token 应从 completion_tokens_details.reasoning_tokens 提取
        assertEquals(60, acc.getUsage().thinkTokens());
        // 缓存命中应从顶层 prompt_cache_hit_tokens 提取
        assertEquals(10, acc.getUsage().cacheReadInputTokens());
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
        ChatAccumulator acc = new ChatAccumulator(req, false);

        parse(dialect, config, acc, json);

        assertEquals(20, acc.getUsage().thinkTokens());
        assertEquals(4, acc.getUsage().cacheReadInputTokens());
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
        ChatAccumulator acc = new ChatAccumulator(req, true);

        // Chunk 1：首段思考 → 产出思考开启信号帧（content 为空）+ 思维链消息
        // 4.1 起边界不再用 <think> 字面量缝进 content，改为空内容的 thinking 信号帧
        acc.reset();
        parse(dialect, config, acc, chunks[0]);
        assertEquals(2, acc.getContentItems().size());
        assertTrue(acc.getContentItems().get(0).isThinking());
        assertEquals("", acc.getContentItems().get(0).getContent());
        assertEquals("第一步思考", acc.getContentItems().get(1).getThinking());

        // Chunk 2：中间思考增量
        acc.reset();
        parse(dialect, config, acc, chunks[1]);
        assertEquals(1, acc.getContentItems().size());
        assertEquals("第二步思考", acc.getContentItems().get(0).getThinking());
        assertTrue(acc.getContentItems().get(0).isThinking());

        // Chunk 3：思考结束 → 产出思考闭合信号帧（content 为空）+ 正文消息
        acc.reset();
        parse(dialect, config, acc, chunks[2]);
        assertEquals(2, acc.getContentItems().size());
        assertTrue(acc.getContentItems().get(0).isThinking());
        assertEquals("", acc.getContentItems().get(0).getContent());
        assertFalse(acc.getContentItems().get(1).isThinking());
        assertEquals("正文", acc.getContentItems().get(1).getContent());

        // Chunk 4：工具调用（取当帧分片消息）
        acc.reset();
        parse(dialect, config, acc, chunks[3]);
        AssistantMessage toolMsg = acc.snapshotFrame().getMessage();
        assertNotNull(toolMsg.getToolCalls());
        assertEquals("get_weather", toolMsg.getToolCalls().get(0).getName());
    }
}
