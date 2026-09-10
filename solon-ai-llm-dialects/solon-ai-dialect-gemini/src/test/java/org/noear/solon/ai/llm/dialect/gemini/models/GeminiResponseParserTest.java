package org.noear.solon.ai.llm.dialect.gemini.models;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.*;
import org.noear.solon.ai.chat.event.*;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.session.InMemoryChatSession;
import org.noear.solon.ai.llm.dialect.gemini.GeminiChatDialect;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class GeminiResponseParserTest {
    private final List<ChatEvent> events = new ArrayList<>();

    private ChatStreamContext context(boolean stream) {
        events.clear();
        ChatConfig config = new ChatConfig();
        config.setModel("gemini-2.5-flash");
        ChatRequest req = new ChatRequest(config, GeminiChatDialect.getInstance(), ChatOptions.of(),
                InMemoryChatSession.builder().build(), ChatMessage.ofSystem("test"), null, stream);
        return new ChatStreamContextDefault(config, req, new ChatAccumulator(req, stream),
                new ChatStreamSession(), 0, events::add);
    }

    @Test
    public void nonStreamConsumesOnlyFirstCandidate() {
        ChatStreamContext ctx = context(false);
        GeminiChatDialect.getInstance().parseResponseJson(ctx, "{\"candidates\":["
                + "{\"content\":{\"parts\":[{\"text\":\"first\"}]},\"finishReason\":\"STOP\"},"
                + "{\"content\":{\"parts\":[{\"text\":\"second\"}]},\"finishReason\":\"MAX_TOKENS\"}]}" );
        assertEquals("first", ctx.getAccumulator().snapshotTerminal().getText());
        assertEquals("STOP", ctx.getAccumulator().lastFinishReason);
    }

    @Test
    public void streamConsumesOnlyFirstCandidate() {
        ChatStreamContext ctx = context(true);
        GeminiChatDialect.getInstance().parseResponseJson(ctx, "{\"candidates\":["
                + "{\"content\":{\"parts\":[{\"text\":\"first\"}]}},"
                + "{\"content\":{\"parts\":[{\"text\":\"second\"}]}}]}" );
        assertEquals("first", ctx.getAccumulator().getAggregationText());
        assertFalse(ctx.getAccumulator().getAggregationText().contains("second"));
        assertEquals(1, count(ChatEventType.TEXT_DELTA));
    }

    @Test
    public void sidecarEventsConsumeOnlyFirstCandidate() {
        ChatStreamContext ctx = context(true);
        GeminiChatDialect.getInstance().parseResponseJson(ctx, "{\"candidates\":["
                + "{\"content\":{\"parts\":[{\"text\":\"first\"}]}},"
                + "{\"content\":{\"parts\":[{\"text\":\"second\",\"executableCode\":{\"code\":\"bad\"}}]},"
                + "\"groundingMetadata\":{\"groundingChunks\":[{\"web\":{\"uri\":\"https://second.example\"}}]}}]}" );
        assertEquals(0, count(ChatEventType.CITATION));
        assertEquals(0, count(ChatEventType.SERVER_TOOL_START));
    }

    @Test
    public void abnormalFinishReasonIsNotSilentSuccess() {
        ChatStreamContext ctx = context(false);
        GeminiChatDialect.getInstance().parseResponseJson(ctx,
                "{\"candidates\":[{\"finishReason\":\"SAFETY\",\"finishMessage\":\"blocked\"}]}");
        assertNotNull(ctx.getAccumulator().getError());
        assertTrue(ctx.getAccumulator().getError().getMessage().contains("SAFETY"));
    }

    @Test
    public void usageIncludesToolUsePromptTokens() {
        ChatStreamContext ctx = context(false);
        GeminiChatDialect.getInstance().parseResponseJson(ctx,
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]},\"finishReason\":\"STOP\"}],"
                        + "\"usageMetadata\":{\"promptTokenCount\":10,\"toolUsePromptTokenCount\":3,"
                        + "\"candidatesTokenCount\":2,\"thoughtsTokenCount\":1,\"totalTokenCount\":16}}");
        assertEquals(13, ctx.getAccumulator().getUsage().promptTokens());
        assertEquals(16, ctx.getAccumulator().getUsage().totalTokens());
    }

    private long count(ChatEventType type) {
        long count = 0;
        for (ChatEvent event : events) if (event.getType() == type) count++;
        return count;
    }
}
