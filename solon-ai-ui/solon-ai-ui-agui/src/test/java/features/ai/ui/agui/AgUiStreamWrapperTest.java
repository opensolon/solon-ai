/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package features.ai.ui.agui;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.event.ChatEvent;
import org.noear.solon.ai.chat.event.ChatEventDefault;
import org.noear.solon.ai.chat.event.ChatEventType;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.ui.agui.AgUiStreamWrapper;
import org.noear.solon.ai.ui.agui.EventType;
import org.noear.solon.ai.ui.agui.event.Event;
import reactor.core.publisher.Flux;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AgUiStreamWrapperTest {
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
}
