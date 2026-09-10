/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package features.ai.ui.agui;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.content.BlobBlock;
import org.noear.solon.ai.chat.event.ChatEvent;
import org.noear.solon.ai.chat.event.ChatEventDefault;
import org.noear.solon.ai.chat.event.ChatEventType;
import org.noear.solon.ai.chat.source.Citation;
import org.noear.solon.ai.chat.source.SearchResult;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.ui.agui.AgUiStreamWrapper;
import org.noear.solon.ai.ui.agui.EventType;
import org.noear.solon.ai.ui.agui.event.Event;
import org.noear.solon.ai.ui.agui.event.CustomEvent;
import reactor.core.publisher.Flux;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AgUiStreamWrapperTest {
    @Test
    public void supervisorDeltaIsCustomAndNeverProducesContentEvents() {
        ChatEvent thinking = ChatEventDefault.of(ChatEventType.THINKING_DELTA).text("internal").build();
        List<Event> events = AgUiStreamWrapper.of("thread_1", "run_1")
                .toAgUiAgentStream(Flux.just(new SupervisorDeltaEvent(thinking, true)), "thread_1", "run_1")
                .collectList().block();
        assertEquals(1, events.stream().filter(e -> e.getType() == EventType.CUSTOM).count());
        assertTrue(events.stream().noneMatch(e -> e.getType() == EventType.REASONING_MESSAGE_CONTENT), events.toString());
        Map<?, ?> payload = (Map<?, ?>) events.stream().filter(e -> e.getType() == EventType.CUSTOM)
                .findFirst().get().getRawEvent();
        assertEquals("SupervisorDeltaEvent", payload.get("agentEventType"));
        assertEquals("run_1", payload.get("runId"));
        assertEquals("internal", payload.get("text"));
    }

    @Test
    public void contentIdsAreScopedByResponseIdAndStep() {
        List<Event> events = AgUiStreamWrapper.of("thread_1", "run_1").toAgUiStream(Flux.just(
                ChatEventDefault.of(ChatEventType.TEXT_DELTA).responseId("r1").step(0).itemId("same").text("a").build(),
                ChatEventDefault.of(ChatEventType.TEXT_END).responseId("r1").step(0).itemId("same").build(),
                ChatEventDefault.of(ChatEventType.TEXT_DELTA).responseId("r1").step(1).itemId("same").text("b").build(),
                ChatEventDefault.of(ChatEventType.TEXT_END).responseId("r1").step(1).itemId("same").build(),
                ChatEventDefault.of(ChatEventType.TEXT_DELTA).responseId("r2").step(0).itemId("same").text("c").build(),
                ChatEventDefault.of(ChatEventType.TEXT_END).responseId("r2").step(0).itemId("same").build())).collectList().block();
        List<String> ids = events.stream().filter(e -> e.getType() == EventType.TEXT_MESSAGE_START)
                .map(e -> ((org.noear.solon.ai.ui.agui.event.TextMessageStartEvent) e).getMessageId())
                .collect(Collectors.toList());
        assertEquals(3, ids.size());
        assertEquals(3, ids.stream().distinct().count());
    }

    @Test
    public void mediaDoneCustomPayloadKeepsBlockAndIdentity() {
        Event custom = AgUiStreamWrapper.of("thread_1", "run_1").toAgUiStream(Flux.just(
                ChatEventDefault.of(ChatEventType.MEDIA_DONE).rawType("media.done").subType("image")
                        .responseId("r1").providerResponseId("p1").step(2).itemId("image_1").index(3)
                        .block(BlobBlock.of("aGVsbG8=", "image/png")).build())).collectList().block().stream()
                .filter(e -> e.getType() == EventType.CUSTOM).findFirst().get();
        Map<?, ?> payload = (Map<?, ?>) custom.getRawEvent();
        Map<?, ?> block = (Map<?, ?>) payload.get("block");
        assertEquals("MEDIA_DONE", payload.get("eventType"));
        assertEquals("r1", payload.get("responseId"));
        assertEquals(2, payload.get("step"));
        assertEquals("image_1", payload.get("itemId"));
        assertEquals(3, payload.get("index"));
        assertEquals("BlobBlock", block.get("type"));
        assertEquals("aGVsbG8=", block.get("content"));
        assertEquals("image/png", block.get("mimeType"));
    }


    @Test
    public void typedSourcesAreRetainedInStandardCustomNameAndValue() {
        SearchResult search = new SearchResult().index(2).id("s1").title("Solon")
                .url("https://solon.noear.org").snippet("framework");
        Citation citation = new Citation().type("url_citation").title("Docs")
                .url("https://solon.noear.org/docs").citedText("Solon AI");

        List<CustomEvent> custom = AgUiStreamWrapper.of("thread_1", "run_1")
                .toAgUiStream(Flux.just(
                        ChatEventDefault.of(ChatEventType.SEARCH_RESULT).searchResult(search).build(),
                        ChatEventDefault.of(ChatEventType.CITATION).citation(citation).build()))
                .filter(e -> e.getType() == EventType.CUSTOM)
                .map(e -> (CustomEvent) e)
                .collectList().block();

        assertEquals("search_result", custom.get(0).getName());
        Map<?, ?> searchPayload = (Map<?, ?>) custom.get(0).getValue();
        assertEquals("https://solon.noear.org", ((Map<?, ?>) searchPayload.get("searchResult")).get("url"));
        assertEquals("citation", custom.get(1).getName());
        Map<?, ?> citationPayload = (Map<?, ?>) custom.get(1).getValue();
        assertEquals("Solon AI", ((Map<?, ?>) citationPayload.get("citation")).get("citedText"));
        assertEquals(custom.get(0).getValue(), custom.get(0).getRawEvent(), "兼容旧 rawEvent payload");
    }

    @Test
    public void mapsLifecycleTextReasoningAndToolEvents() {
        ToolCall call = new ToolCall("0", "call_1", "weather", "{\"city\":\"杭州\"}", new LinkedHashMap<>());
        Flux<ChatEvent> source = Flux.fromIterable(Arrays.asList(
                ChatEventDefault.of(ChatEventType.RESPONSE_START).build(),
                ChatEventDefault.of(ChatEventType.STEP_START).step(0).build(),
                ChatEventDefault.of(ChatEventType.THINKING_START).itemId("think_1").build(),
                ChatEventDefault.of(ChatEventType.THINKING_DELTA).itemId("think_1").text("先分析").build(),
                ChatEventDefault.of(ChatEventType.THINKING_END).itemId("think_1").build(),
                ChatEventDefault.of(ChatEventType.TEXT_START).itemId("text_1").build(),
                ChatEventDefault.of(ChatEventType.TEXT_DELTA).itemId("text_1").text("答案").build(),
                ChatEventDefault.of(ChatEventType.TEXT_END).itemId("text_1").build(),
                ChatEventDefault.of(ChatEventType.TOOL_CALL_START).toolCallId("call_1").toolCall(call).build(),
                ChatEventDefault.of(ChatEventType.TOOL_CALL_ARGS_DELTA).toolCallId("call_1").text("{\"city\"").build(),
                ChatEventDefault.of(ChatEventType.TOOL_CALL_END).toolCallId("call_1").toolCall(call).build(),
                ChatEventDefault.of(ChatEventType.STEP_END).step(0).build(),
                ChatEventDefault.of(ChatEventType.RESPONSE_END).build()));

        List<EventType> types = AgUiStreamWrapper.of("thread_1", "run_1")
                .toAgUiStream(source)
                .map(Event::getType)
                .collectList().block();

        assertEquals(EventType.RUN_STARTED, types.get(0));
        assertTrue(types.contains(EventType.REASONING_START));
        assertTrue(types.contains(EventType.REASONING_MESSAGE_START));
        assertTrue(types.contains(EventType.REASONING_MESSAGE_CONTENT));
        assertTrue(types.contains(EventType.REASONING_MESSAGE_END));
        assertTrue(types.contains(EventType.REASONING_END));
        assertTrue(types.contains(EventType.TEXT_MESSAGE_START));
        assertTrue(types.contains(EventType.TEXT_MESSAGE_CONTENT));
        assertTrue(types.contains(EventType.TEXT_MESSAGE_END));
        assertEquals(1, types.stream().filter(EventType.TOOL_CALL_START::equals).count());
        assertEquals(1, types.stream().filter(EventType.TOOL_CALL_ARGS::equals).count());
        assertEquals(1, types.stream().filter(EventType.TOOL_CALL_END::equals).count());
        assertEquals(EventType.STEP_FINISHED, types.get(types.size() - 2));
        assertEquals(EventType.RUN_FINISHED, types.get(types.size() - 1));
    }

    @Test
    public void abnormalRunEndMapsToRunError() {
        List<Event> events = AgUiStreamWrapper.of("thread_1", "run_1")
                .toAgUiAgentStream(Flux.just(new RunEndEvent()), "thread_1", "run_1")
                .collectList().block();
        assertTrue(events.stream().anyMatch(e -> e.getType() == EventType.RUN_ERROR), events.toString());
        assertTrue(events.stream().noneMatch(e -> e.getType() == EventType.RUN_FINISHED), events.toString());
    }

    @Test
    public void unsupportedCoreEventsAreRetainedAsCustomEvents() {
        List<Event> events = AgUiStreamWrapper.of("thread_1", "run_1")
                .toAgUiStream(Flux.just(
                        ChatEventDefault.of(ChatEventType.SERVER_TOOL_START).subType("web_search").build(),
                        ChatEventDefault.of(ChatEventType.MEDIA_PARTIAL).text("chunk").build(),
                        ChatEventDefault.of(ChatEventType.REFUSAL_DELTA).text("不能回答").build()))
                .collectList().block();

        List<Event> custom = events.stream()
                .filter(e -> e.getType() == EventType.CUSTOM)
                .collect(Collectors.toList());
        assertEquals(3, custom.size());
        assertTrue(custom.stream().allMatch(e -> e.getRawEvent() != null));
    }

    public static class RunEndEvent {
        public boolean isAbnormal() { return true; }
        public String getText() { return "agent failed"; }
        public Object getResponse() { return null; }
    }

    public static class SupervisorDeltaEvent {
        private final ChatEvent chatEvent;
        private final boolean thinking;

        public SupervisorDeltaEvent(ChatEvent chatEvent, boolean thinking) {
            this.chatEvent = chatEvent;
            this.thinking = thinking;
        }

        public ChatEvent getChatEvent() { return chatEvent; }
        public String getRunId() { return "run_1"; }
        public String getAgentName() { return "supervisor"; }
        public String getText() { return chatEvent.getText(); }
        public boolean isThinking() { return thinking; }
    }
}
