package features.ai.core.event;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.ChatAccumulator;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.ChatRequest;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.dialect.AbstractChatDialect;
import org.noear.solon.ai.chat.event.ChatEvent;
import org.noear.solon.ai.chat.event.ChatEventDefault;
import org.noear.solon.ai.chat.event.ChatEventType;
import org.noear.solon.ai.chat.event.ChatStreamContext;
import org.noear.solon.ai.chat.event.ChatStreamContextDefault;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.MessageProtocolState;
import org.noear.solon.ai.chat.message.MessageSemanticHasher;
import org.noear.solon.ai.chat.session.InMemoryChatSession;
import org.noear.solon.ai.chat.source.Citation;
import org.noear.solon.ai.chat.source.SearchResult;
import org.noear.solon.ai.chat.tool.ToolCall;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ChatAccumulatorEventTest {
    @Test
    public void textThinkingMediaAndUsageAggregateBeforeDelivery() {
        ChatAccumulator acc = newAccumulator(true);
        List<ChatEvent> delivered = new ArrayList<>();
        ChatStreamContext ctx = new ChatStreamContextDefault(null, acc.getRequest(), acc, null, 0, delivered::add);

        ctx.emit(ctx.event(ChatEventType.TEXT_DELTA).text("hello").build());
        ctx.emit(ctx.event(ChatEventType.THINKING_DELTA).text("think").build());
        assertEquals("hello", acc.getAggregationText());
        assertEquals("think", acc.getAggregationThinking());
        assertEquals(2, delivered.size());
    }

    @Test
    public void toolEventsUseIndexAndEndDoesNotDuplicateArguments() {
        ChatAccumulator acc = newAccumulator(true);
        ChatStreamContext ctx = ChatStreamContextDefault.ofNoEmit(acc);
        ToolCall head = new ToolCall("idx:0", "call-1", "weather", null, null);
        ToolCall done = new ToolCall("idx:0", "call-1", "weather", "{\"city\":\"杭州\"}", null);

        ctx.emit(ChatEventDefault.of(ChatEventType.TOOL_CALL_START).toolCall(head).toolCallId("call-1").build());
        ctx.emit(ChatEventDefault.of(ChatEventType.TOOL_CALL_ARGS_DELTA).toolCall(head).toolCallId("call-1").text("{\"city\":").build());
        ctx.emit(ChatEventDefault.of(ChatEventType.TOOL_CALL_ARGS_DELTA).toolCall(head).toolCallId("call-1").text("\"杭州\"}").build());
        ctx.emit(ChatEventDefault.of(ChatEventType.TOOL_CALL_END).toolCall(done).toolCallId("call-1").text(done.getArgumentsStr()).build());

        assertEquals(1, acc.getToolCallBuilders().size());
        assertEquals("call-1", acc.getToolCallBuilders().get("idx:0").idBuilder.toString());
        assertEquals("weather", acc.getToolCallBuilders().get("idx:0").nameBuilder.toString());
        assertEquals("{\"city\":\"杭州\"}", acc.getToolCallBuilders().get("idx:0").argumentsBuilder.toString());
        assertEquals(1, acc.snapshotTerminal().getToolCalls().size());
        assertSame(done, acc.snapshotTerminal().getToolCalls().get(0));
    }

    @Test
    public void idFallbackRemainsStableWhenIndexIsMissing() {
        ChatAccumulator acc = newAccumulator(true);
        ChatStreamContext ctx = ChatStreamContextDefault.ofNoEmit(acc);
        ToolCall call = new ToolCall(null, "call-2", "lookup", null, null);
        ctx.emit(ChatEventDefault.of(ChatEventType.TOOL_CALL_START).toolCall(call).build());
        ctx.emit(ChatEventDefault.of(ChatEventType.TOOL_CALL_ARGS_DELTA).toolCall(call).text("{}").build());
        assertTrue(acc.getToolCallBuilders().containsKey("call-2"));
        assertEquals("{}", acc.getToolCallBuilders().get("call-2").argumentsBuilder.toString());
    }

    @Test
    public void numericAndPrefixedToolIndexesShareOneBuilder() {
        ChatAccumulator acc = newAccumulator(true);
        ChatStreamContext ctx = ChatStreamContextDefault.ofNoEmit(acc);
        ToolCall head = new ToolCall("0", "call-3", "lookup", null, null);
        ToolCall delta = new ToolCall("idx:0", "call-3", "lookup", null, null);

        ctx.emit(ChatEventDefault.of(ChatEventType.TOOL_CALL_START).toolCall(head).build());
        ctx.emit(ChatEventDefault.of(ChatEventType.TOOL_CALL_ARGS_DELTA).toolCall(delta).text("{}").build());

        assertEquals(1, acc.getToolCallBuilders().size());
        assertEquals("{}", acc.getToolCallBuilders().get("idx:0").argumentsBuilder.toString());
    }

    @Test
    public void mergeAndReplaceShouldHonorProtocolStateContract() {
        ChatAccumulator acc = newAccumulator(true);
        acc.mergeTerminalMessage(AssistantMessage.snapshot("first", "", null, null, null, null,
                java.util.Collections.singletonMap("protocol.a", new MessageProtocolState(1))));
        acc.mergeTerminalMessage(AssistantMessage.snapshot("", "", null, null, null, null,
                java.util.Collections.singletonMap("protocol.b", new MessageProtocolState(2))));

        AssistantMessage merged = acc.snapshotTerminal().getMessage();
        assertTrue(merged.hasProtocolState("protocol.a"));
        assertTrue(merged.hasProtocolState("protocol.b"));

        acc.replaceTerminalMessage(AssistantMessage.snapshot("replacement", "", null, null, null, null,
                java.util.Collections.singletonMap("protocol.c", new MessageProtocolState(3))));
        AssistantMessage replaced = acc.snapshotTerminal().getMessage();
        assertFalse(replaced.hasProtocolState("protocol.a"));
        assertFalse(replaced.hasProtocolState("protocol.b"));
        assertTrue(replaced.hasProtocolState("protocol.c"));
    }

    @Test
    public void replaceTerminalMessageClearsPreviousStepAggregationAndTools() {
        ChatAccumulator acc = newAccumulator(true);
        ChatStreamContext ctx = ChatStreamContextDefault.ofNoEmit(acc);
        ToolCall call = new ToolCall("idx:0", "call-4", "lookup", "{}", null);

        ctx.emit(ChatEventDefault.of(ChatEventType.TEXT_DELTA).text("before tool").build());
        ctx.emit(ChatEventDefault.of(ChatEventType.THINKING_DELTA).text("thinking").build());
        ctx.emit(ChatEventDefault.of(ChatEventType.TOOL_CALL_START).toolCall(call).build());
        ctx.emit(ChatEventDefault.of(ChatEventType.TOOL_CALL_ARGS_DELTA).toolCall(call).text("{}").build());
        ctx.emit(ChatEventDefault.of(ChatEventType.TOOL_CALL_END).toolCall(call).build());

        acc.replaceTerminalMessage(new AssistantMessage("direct result"));

        AssistantMessage result = acc.snapshotTerminal().getMessage();
        assertNotNull(result);
        assertEquals("direct result", result.getText());
        assertEquals("", result.getThinking());
        assertTrue(result.getToolCalls() == null || result.getToolCalls().isEmpty());
        assertTrue(acc.getToolCallBuilders().isEmpty());
    }

    @Test
    public void errorEventBecomesAccumulatorError() {
        ChatAccumulator acc = newAccumulator(true);
        ChatStreamContext ctx = ChatStreamContextDefault.ofNoEmit(acc);
        org.noear.solon.ai.chat.ChatException error = new org.noear.solon.ai.chat.ChatException("failed");

        ctx.emit(ChatEventDefault.of(ChatEventType.ERROR).error(error).build());

        assertSame(error, acc.getError());
    }

    @Test
    public void errorEventWithoutPayloadStillBecomesAccumulatorError() {
        ChatAccumulator acc = newAccumulator(true);
        ChatStreamContext ctx = ChatStreamContextDefault.ofNoEmit(acc);

        ctx.emit(ChatEventDefault.of(ChatEventType.ERROR).build());

        assertNotNull(acc.getError());
    }

    @Test
    public void textAndMediaBlocksKeepEventOrder() {
        ChatAccumulator acc = newAccumulator(true);
        ChatStreamContext ctx = ChatStreamContextDefault.ofNoEmit(acc);
        ImageBlock image = ImageBlock.ofUrl("https://example.com/a.png");

        ctx.emit(ChatEventDefault.of(ChatEventType.MEDIA_DONE).block(image).index(0).build());
        ctx.emit(ChatEventDefault.of(ChatEventType.TEXT_DELTA).text("caption").index(1).build());

        AssistantMessage message = acc.snapshotTerminal().getMessage();
        assertNotNull(message);
        assertEquals(2, message.getBlocks().size());
        assertSame(image, message.getBlocks().get(0));
        assertTrue(message.getBlocks().get(1) instanceof TextBlock);
        assertEquals("caption", message.getBlocks().get(1).getContent());
    }

    @Test
    public void terminalBlocksKeepTextOnlyBlockAndOriginalOrder() {
        ChatAccumulator acc = newAccumulator(false);
        ImageBlock image = ImageBlock.ofUrl("https://example.com/a.png");
        AssistantMessage source = new AssistantMessage("", "", null,
                Arrays.asList(image, TextBlock.of("caption")));
        acc.setTerminalMessage(source);

        AssistantMessage message = acc.snapshotTerminal().getMessage();
        assertNotNull(message);
        assertEquals(2, message.getBlocks().size());
        assertSame(image, message.getBlocks().get(0));
        assertEquals("caption", message.getBlocks().get(1).getContent());
    }

    @Test
    public void sourceEventsShouldAggregateIntoTerminalMessageAndRemainOccurrences() {
        ChatAccumulator acc = newAccumulator(true);
        ChatStreamContext ctx = ChatStreamContextDefault.ofNoEmit(acc);
        SearchResult result = new SearchResult().index(0).title("Solon").url("https://solon.noear.org");
        Citation citation = new Citation().type("url_citation").url("https://solon.noear.org/docs");

        ctx.emit(ChatEventDefault.of(ChatEventType.SEARCH_RESULT).searchResult(result).build());
        ctx.emit(ChatEventDefault.of(ChatEventType.CITATION).citation(citation).build());
        ctx.emit(ChatEventDefault.of(ChatEventType.CITATION).citation(citation).build());

        AssistantMessage message = acc.snapshotTerminal().getMessage();
        assertNotNull(message);
        assertEquals(1, message.getSearchResults().size());
        assertSame(result, message.getSearchResults().get(0));
        assertEquals(2, message.getCitations().size(), "相同来源在答案不同位置出现时不得按 URL 去重");
        assertFalse(acc.snapshotTerminal().isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> acc.snapshotTerminal().getSearchResults().add(null));
        assertThrows(UnsupportedOperationException.class,
                () -> acc.snapshotTerminal().getCitations().add(null));
    }

    @Test
    public void terminalTypedSourcesShouldBeFallbackWhenNoEventsExist() {
        ChatAccumulator acc = newAccumulator(false);
        AssistantMessage source = AssistantMessage.snapshot("", "", null, null,
                Arrays.asList(new SearchResult().title("Fallback")),
                Arrays.asList(new Citation().type("page_location").citedText("quoted")), null);
        acc.setTerminalMessage(source);

        AssistantMessage message = acc.snapshotTerminal().getMessage();
        assertNotNull(message);
        assertEquals("Fallback", message.getSearchResults().get(0).getTitle());
        assertEquals("quoted", message.getCitations().get(0).getCitedText());
    }

    @Test
    public void nonStreamContextShouldCollectSemanticEvents() {
        ChatAccumulator acc = newAccumulator(false);
        ChatStreamContext ctx = ChatStreamContextDefault.ofNoEmit(acc);
        Citation citation = new Citation().type("url_citation").url("https://example.com");

        ctx.emit(ChatEventDefault.of(ChatEventType.CITATION).citation(citation).build());

        assertEquals(1, acc.snapshotTerminal().getEvents().size());
        assertSame(citation, acc.snapshotTerminal().getEvents().get(0).getCitation());
    }

    @Test
    public void terminalAggregationKeepsProtocolStateButDropsLegacyRaw() {
        ChatAccumulator acc = newAccumulator(false);
        String legacyJson = "{\"role\":\"assistant\",\"text\":\"answer\",\"thinking\":\"thought\"," +
                "\"contentRaw\":{\"thinkingSignature\":\"sig_old\"}}";
        MessageProtocolState state = new MessageProtocolState(1);
        state.setSemanticHash(MessageSemanticHasher.hash(
                (AssistantMessage) ChatMessage.fromJson(legacyJson)));
        org.noear.snack4.ONode historical = org.noear.snack4.ONode.ofJson(legacyJson);
        historical.set("protocolStates", java.util.Collections.singletonMap("openai.responses", state));
        AssistantMessage source = (AssistantMessage) ChatMessage.fromJson(historical.toJson());

        acc.setTerminalMessage(source);
        AssistantMessage restored = acc.snapshotTerminal().getMessage();

        assertNotNull(restored.getProtocolState("openai.responses"));
        assertNull(restored.getContentRaw(), "终态聚合不再生成 legacy raw 载体");
    }

    @Test
    public void compatibilityPublisherChecksThinkingAndTextIndependently() {
        NoopDialect dialect = new NoopDialect();
        ChatAccumulator acc = newAccumulator(true, dialect);
        List<ChatEvent> delivered = new ArrayList<>();
        ChatStreamContext ctx = new ChatStreamContextDefault(null, acc.getRequest(), acc, null, 0, delivered::add);
        AssistantMessage parsed = (AssistantMessage) ChatMessage.fromJson(
                "{\"role\":\"assistant\",\"text\":\"answer\",\"thinking\":\"reasoning\"," +
                        "\"reasoningFieldName\":\"reasoning_content\"," +
                        "\"metadata\":{\"carrier\":\"kept\"}}");

        dialect.publish(ctx, parsed);

        assertEquals(Arrays.asList(ChatEventType.THINKING_DELTA, ChatEventType.TEXT_DELTA),
                Arrays.asList(delivered.get(0).getType(), delivered.get(1).getType()));
        assertEquals("reasoning", acc.getAggregationThinking());
        assertEquals("answer", acc.getAggregationText());
        assertEquals("kept", acc.snapshotTerminal().getMessage().getMetadata().get("carrier"));
    }

    private static ChatAccumulator newAccumulator(boolean stream) {
        return newAccumulator(stream, new NoopDialect());
    }

    private static ChatAccumulator newAccumulator(boolean stream, NoopDialect dialect) {
        ChatRequest request = new ChatRequest(new ChatConfig(), dialect, ChatOptions.of(),
                InMemoryChatSession.builder().build(), null, null, stream);
        return new ChatAccumulator(request, stream);
    }

    static class NoopDialect extends AbstractChatDialect {
        public boolean matched(ChatConfig config) { return false; }
        public void parseResponseJson(ChatStreamContext ctx, String data) { }
        void publish(ChatStreamContext ctx, AssistantMessage message) {
            publishAssistantMessageEvents(ctx, message);
        }
    }
}
