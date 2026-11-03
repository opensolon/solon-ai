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

    @Test
    public void usageFallsBackToResponseTokenCount() {
        // 新版 API/模型：candidatesTokenCount 由 responseTokenCount 替代，缺失时需回退，否则 completionTokens 恒为 0
        ChatStreamContext ctx = context(false);
        GeminiChatDialect.getInstance().parseResponseJson(ctx,
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]},\"finishReason\":\"STOP\"}],"
                        + "\"usageMetadata\":{\"promptTokenCount\":10,\"responseTokenCount\":7,"
                        + "\"thoughtsTokenCount\":1,\"totalTokenCount\":18,\"cachedContentTokenCount\":4}}");
        assertEquals(7, ctx.getAccumulator().getUsage().completionTokens());
        assertEquals(1, ctx.getAccumulator().getUsage().thinkTokens());
        assertEquals(4, ctx.getAccumulator().getUsage().cacheReadInputTokens());
        assertEquals(10, ctx.getAccumulator().getUsage().promptTokens());
    }

    @Test
    public void streamUsagePrefersCandidatesTokenCount() {
        // 两个字段同时存在时以 candidatesTokenCount 为准
        ChatStreamContext ctx = context(true);
        GeminiChatDialect.getInstance().parseResponseJson(ctx, "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]},\"finishReason\":\"STOP\"}],"
                + "\"usageMetadata\":{\"candidatesTokenCount\":5,\"responseTokenCount\":9,\"totalTokenCount\":20}}");
        assertEquals(5, ctx.getAccumulator().getUsage().completionTokens());
    }

    @Test
    public void promptFeedbackBlockIsError() {
        // 非流式：prompt 被安全策略拦截时无 candidates，必须显式报错而非静默空响应
        ChatStreamContext ctx = context(false);
        GeminiChatDialect.getInstance().parseResponseJson(ctx,
                "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}");
        assertNotNull(ctx.getAccumulator().getError());
        assertTrue(ctx.getAccumulator().getError().getMessage().contains("SAFETY"));
        assertEquals(1, count(ChatEventType.ERROR));
    }

    @Test
    public void streamPromptFeedbackBlockIsError() {
        // 流式分支同样需报错
        ChatStreamContext ctx = context(true);
        GeminiChatDialect.getInstance().parseResponseJson(ctx,
                "data: {\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}");
        assertNotNull(ctx.getAccumulator().getError());
        assertEquals(1, count(ChatEventType.ERROR));
    }

    @Test
    public void snakeCasePromptFeedbackBlockIsError() {
        // 兼容网关可能转发 snake_case
        ChatStreamContext ctx = context(true);
        GeminiChatDialect.getInstance().parseResponseJson(ctx,
                "data: {\"prompt_feedback\":{\"block_reason\":\"OTHER\"}}");
        assertNotNull(ctx.getAccumulator().getError());
        assertTrue(ctx.getAccumulator().getError().getMessage().contains("OTHER"));
    }

    @Test
    public void doneMarkerFlushesPendingFunctionCall() {
        // OpenAI 兼容网关转 Gemini：functionCall 帧后仅用 [DONE] 收尾（无 finishReason 帧），
        // 曾发生工具调用静默丢失，[DONE] 时必须 flush
        ChatStreamContext ctx = context(true);
        GeminiChatDialect.getInstance().parseResponseJson(ctx,
                "data: {\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":{\"name\":\"getWeather\","
                        + "\"args\":{\"city\":\"hz\"},\"id\":\"call-1\"}}]}}]}");
        GeminiChatDialect.getInstance().parseResponseJson(ctx, "data: [DONE]");

        assertTrue(ctx.getAccumulator().isFinished(), "[DONE] 应置 finished");
        assertEquals(1, count(ChatEventType.TOOL_CALL_START), "未 flush 的 functionCall 不得丢失");
        assertTrue(events.stream().anyMatch(e -> e.getType() == ChatEventType.TOOL_CALL_ARGS_DELTA
                        && e.getText() != null && e.getText().contains("\"city\":\"hz\"")),
                "[DONE] 时应交付最终参数");
    }

    @Test
    public void doneMarkerAfterFinishReasonDoesNotDuplicateToolCall() {
        // 官方 API：最后一帧带 finishReason 已 flush，随后的 [DONE] 不得重复发射
        ChatStreamContext ctx = context(true);
        GeminiChatDialect.getInstance().parseResponseJson(ctx,
                "data: {\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":{\"name\":\"getWeather\","
                        + "\"args\":{\"city\":\"hz\"},\"id\":\"call-1\"}}]},\"finishReason\":\"STOP\"}]}");
        GeminiChatDialect.getInstance().parseResponseJson(ctx, "data: [DONE]");

        assertEquals(1, count(ChatEventType.TOOL_CALL_START));
        assertEquals(1, count(ChatEventType.TOOL_CALL_ARGS_DELTA));
    }

    private long count(ChatEventType type) {
        long count = 0;
        for (ChatEvent event : events) if (event.getType() == type) count++;
        return count;
    }
}
