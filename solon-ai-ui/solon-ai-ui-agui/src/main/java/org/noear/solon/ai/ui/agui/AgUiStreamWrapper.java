/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package org.noear.solon.ai.ui.agui;

import org.noear.solon.ai.chat.event.ChatEvent;
import org.noear.solon.ai.chat.event.ChatEventType;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.ui.agui.event.*;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 将核心 {@link ChatEvent} 流转换为 AG-UI 事件流。
 *
 * <p>这是协议适配层，不把 AG-UI 的 run/thread 字段混入核心事件。核心事件
 * 的文本、推理和工具边界被一一转换；AG-UI 未定义的媒体、安全、用量和服务端
 * 工具事件通过 {@link CustomEvent} 保留，避免静默丢失。</p>
 */
public class AgUiStreamWrapper {
    private final String defaultThreadId;
    private final String defaultRunId;

    public AgUiStreamWrapper() {
        this(null, null);
    }

    public AgUiStreamWrapper(String threadId, String runId) {
        this.defaultThreadId = empty(threadId) ? UUID.randomUUID().toString() : threadId;
        this.defaultRunId = empty(runId) ? UUID.randomUUID().toString() : runId;
    }

    public static AgUiStreamWrapper of() {
        return new AgUiStreamWrapper();
    }

    public static AgUiStreamWrapper of(String threadId, String runId) {
        return new AgUiStreamWrapper(threadId, runId);
    }

    /** 将核心事件流转换为 AG-UI 事件流。 */
    public Flux<Event> toAgUiStream(Flux<ChatEvent> source) {
        return toAgUiStream(source, defaultThreadId, defaultRunId);
    }

    /**
     * 将核心事件流转换为 AG-UI 事件流，并显式指定 AG-UI 运行上下文。
     *
     * <p>适合从 HTTP 请求或 Agent 会话中传入稳定的 threadId/runId。</p>
     */
    public Flux<Event> toAgUiStream(Flux<ChatEvent> source, String threadId, String runId) {
        final String actualThreadId = empty(threadId) ? defaultThreadId : threadId;
        final String actualRunId = empty(runId) ? defaultRunId : runId;

        return Flux.create(sink -> {
            State state = new State();
            RunStartedEvent started = new RunStartedEvent();
            started.setThreadId(actualThreadId);
            started.setRunId(actualRunId);
            sink.next(started);

            Disposable upstream = source.subscribe(
                    event -> emitEvent(sink, event, state, actualThreadId, actualRunId),
                    error -> finishError(sink, state, actualThreadId, actualRunId, error),
                    () -> finishSuccess(sink, state, actualThreadId, actualRunId));
            sink.onDispose(upstream);
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    private void emitEvent(FluxSink<Event> sink, ChatEvent event, State state,
                           String threadId, String runId) {
        if (event == null || sink.isCancelled()) return;
        List<Event> mapped = map(event, state, threadId, runId);
        for (Event output : mapped) {
            if (!sink.isCancelled()) sink.next(output);
        }
    }

    private List<Event> map(ChatEvent event, State state, String threadId, String runId) {
        List<Event> result = new ArrayList<>();
        ChatEventType type = event.getType();
        switch (type) {
            case RESPONSE_START:
                //RUN_STARTED 已由包装器发出，避免一条流出现两个运行开始事件。
                break;
            case RESPONSE_END:
                if (event.getResponse() != null) {
                    state.result = event.getResponse();
                }
                break;
            case STEP_START: {
                StepStartedEvent e = new StepStartedEvent();
                e.setStepName(stepName(event));
                result.add(copy(e, event));
                break;
            }
            case STEP_END: {
                closeContents(result, state);
                StepFinishedEvent e = new StepFinishedEvent();
                e.setStepName(stepName(event));
                result.add(copy(e, event));
                break;
            }
            case TEXT_START: {
                String id = openText(result, state, event);
                //openText 已输出事件；id 仅用于保持分支结构清晰。
                if (id == null) result.clear();
                break;
            }
            case TEXT_DELTA: {
                if (!event.hasText()) break;
                String id = openText(result, state, event);
                TextMessageContentEvent e = new TextMessageContentEvent();
                e.setMessageId(id);
                e.setDelta(event.getText());
                result.add(copy(e, event));
                break;
            }
            case TEXT_END: {
                String key = contentKey(event);
                String id = state.textIds.get(key);
                if (id != null && state.openText.remove(key)) {
                    TextMessageEndEvent e = new TextMessageEndEvent();
                    e.setMessageId(id);
                    result.add(copy(e, event));
                }
                break;
            }
            case THINKING_START: {
                openReasoning(result, state, event);
                break;
            }
            case THINKING_DELTA: {
                if (!event.hasText()) break;
                String id = openReasoning(result, state, event);
                ReasoningMessageContentEvent e = new ReasoningMessageContentEvent();
                e.setMessageId(id);
                e.setDelta(event.getText());
                result.add(copy(e, event));
                break;
            }
            case THINKING_END: {
                closeReasoning(result, state, event);
                break;
            }
            case THINKING_SIGNATURE: {
                ReasoningEncryptedValueEvent e = new ReasoningEncryptedValueEvent();
                e.setSubtype(event.getSubType());
                e.setEntityId(entityId(event));
                e.setEncryptedValue(event.getText());
                result.add(copy(e, event));
                break;
            }
            case THINKING_REDACTED:
                result.add(custom(event, "thinking-redacted"));
                break;
            case TOOL_CALL_START: {
                ToolCallStartEvent e = new ToolCallStartEvent();
                e.setToolCallId(toolId(event, state));
                e.setToolCallName(toolName(event));
                e.setParentMessageId(event.getItemId());
                result.add(copy(e, event));
                state.openTools.add(e.getToolCallId());
                break;
            }
            case TOOL_CALL_ARGS_DELTA: {
                ToolCallArgsEvent e = new ToolCallArgsEvent();
                e.setToolCallId(toolId(event, state));
                e.setDelta(event.getText());
                result.add(copy(e, event));
                break;
            }
            case TOOL_CALL_END: {
                ToolCallEndEvent e = new ToolCallEndEvent();
                e.setToolCallId(toolId(event, state));
                result.add(copy(e, event));
                state.openTools.remove(e.getToolCallId());
                break;
            }
            case TOOL_RESULT: {
                ToolCallResultEvent e = new ToolCallResultEvent();
                e.setToolCallId(toolId(event, state));
                e.setContent(event.getText());
                e.setRole(Role.TOOL);
                e.setMessageId(event.getItemId());
                result.add(copy(e, event));
                break;
            }
            case ABORT: {
                closeContents(result, state);
                RunFinishedEvent e = runFinished(threadId, runId, state.result);
                RunOutcome outcome = new RunOutcome();
                outcome.setType("interrupt");
                e.setOutcome(outcome);
                result.add(copy(e, event));
                state.terminal = true;
                break;
            }
            case ERROR: {
                closeContents(result, state);
                RunErrorEvent e = new RunErrorEvent();
                e.setError(event.getError() == null ? "Stream error" : event.getError().getMessage());
                result.add(copy(e, event));
                state.terminal = true;
                break;
            }
            case RAW: {
                RawEvent e = new RawEvent();
                e.setRawEvent(event.getRaw());
                result.add(copy(e, event));
                break;
            }
            case CUSTOM:
                result.add(custom(event, empty(event.getSubType()) ? "custom" : event.getSubType()));
                break;
            default:
                //AG-UI 没有 usage、媒体、安全和服务端工具的标准事件，用 CUSTOM 保留负载。
                result.add(custom(event, type.name().toLowerCase()));
                break;
        }
        return result;
    }

    private void mapToolChunk(List<Event> result, State state, ChatEvent event) {
        String id = toolId(event, state);
        ToolCallStartEvent start = new ToolCallStartEvent();
        start.setToolCallId(id);
        start.setToolCallName(toolName(event));
        start.setParentMessageId(event.getItemId());
        result.add(copy(start, event));
        state.openTools.add(id);

        ToolCall call = event.getToolCall();
        String args = call == null ? event.getText() : call.getArgumentsStr();
        if (!empty(args)) {
            ToolCallArgsEvent argsEvent = new ToolCallArgsEvent();
            argsEvent.setToolCallId(id);
            argsEvent.setDelta(args);
            result.add(copy(argsEvent, event));
        }
        ToolCallEndEvent end = new ToolCallEndEvent();
        end.setToolCallId(id);
        result.add(copy(end, event));
        state.openTools.remove(id);
    }

    private String openText(List<Event> result, State state, ChatEvent event) {
        String key = contentKey(event);
        String id = state.textIds.get(key);
        if (id == null) {
            id = UUID.randomUUID().toString();
            state.textIds.put(key, id);
        }
        if (state.openText.add(key)) {
            TextMessageStartEvent start = new TextMessageStartEvent();
            start.setMessageId(id);
            start.setRole(Role.ASSISTANT.getCode());
            result.add(copy(start, event));
        }
        return id;
    }

    private String openReasoning(List<Event> result, State state, ChatEvent event) {
        String key = contentKey(event);
        String id = state.reasoningIds.get(key);
        if (id == null) {
            id = UUID.randomUUID().toString();
            state.reasoningIds.put(key, id);
        }
        if (state.openReasoning.add(key)) {
            ReasoningStartEvent start = new ReasoningStartEvent();
            start.setMessageId(id);
            result.add(copy(start, event));
            ReasoningMessageStartEvent messageStart = new ReasoningMessageStartEvent(id);
            messageStart.setRole("reasoning");
            result.add(copy(messageStart, event));
        }
        return id;
    }

    private void closeReasoning(List<Event> result, State state, ChatEvent event) {
        String key = contentKey(event);
        String id = state.reasoningIds.get(key);
        if (id != null && state.openReasoning.remove(key)) {
            ReasoningMessageEndEvent messageEnd = new ReasoningMessageEndEvent();
            messageEnd.setMessageId(id);
            result.add(copy(messageEnd, event));
            ReasoningEndEvent end = new ReasoningEndEvent();
            end.setMessageId(id);
            result.add(copy(end, event));
        }
    }

    private void closeContents(List<Event> result, State state) {
        for (String key : new LinkedHashSet<>(state.openReasoning)) {
            ChatEvent synthetic = null;
            String id = state.reasoningIds.get(key);
            if (id != null) {
                ReasoningMessageEndEvent messageEnd = new ReasoningMessageEndEvent();
                messageEnd.setMessageId(id);
                result.add(messageEnd);
                ReasoningEndEvent end = new ReasoningEndEvent();
                end.setMessageId(id);
                result.add(end);
            }
        }
        state.openReasoning.clear();
        for (String key : new LinkedHashSet<>(state.openText)) {
            String id = state.textIds.get(key);
            if (id != null) {
                TextMessageEndEvent end = new TextMessageEndEvent();
                end.setMessageId(id);
                result.add(end);
            }
        }
        state.openText.clear();
    }

    private void finishSuccess(FluxSink<Event> sink, State state, String threadId, String runId) {
        if (state.terminal) {
            sink.complete();
            return;
        }
        List<Event> tail = new ArrayList<>();
        closeContents(tail, state);
        for (Event event : tail) sink.next(event);
        sink.next(runFinished(threadId, runId, state.result));
        sink.complete();
    }

    private void finishError(FluxSink<Event> sink, State state, String threadId, String runId, Throwable error) {
        if (state.terminal) {
            sink.complete();
            return;
        }
        List<Event> tail = new ArrayList<>();
        closeContents(tail, state);
        for (Event event : tail) sink.next(event);
        RunErrorEvent runError = new RunErrorEvent();
        runError.setError(error == null || error.getMessage() == null ? "Stream error" : error.getMessage());
        sink.next(runError);
        sink.complete();
    }

    private static RunFinishedEvent runFinished(String threadId, String runId, Object result) {
        RunFinishedEvent event = new RunFinishedEvent();
        event.setThreadId(threadId);
        event.setRunId(runId);
        event.setResult(result);
        return event;
    }

    private static <T extends Event> T copy(T target, ChatEvent source) {
        target.setRawEvent(source.getRaw());
        return target;
    }

    private static CustomEvent custom(ChatEvent source, String subtype) {
        CustomEvent event = new CustomEvent();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", source.getType().name());
        if (source.getSubType() != null) payload.put("subType", source.getSubType());
        if (source.getText() != null) payload.put("text", source.getText());
        if (source.getAttrs() != null && !source.getAttrs().isEmpty()) payload.put("attrs", source.getAttrs());
        payload.put("raw", source.getRaw());
        event.setRawEvent(payload);
        return event;
    }

    private static String toolId(ChatEvent event, State state) {
        if (!empty(event.getToolCallId())) return event.getToolCallId();
        ToolCall call = event.getToolCall();
        if (call != null && !empty(call.getId())) return call.getId();
        String key = contentKey(event);
        String id = state.toolIds.get(key);
        if (id == null) {
            id = UUID.randomUUID().toString();
            state.toolIds.put(key, id);
        }
        return id;
    }

    private static String toolName(ChatEvent event) {
        ToolCall call = event.getToolCall();
        return call == null ? null : call.getName();
    }

    private static String entityId(ChatEvent event) {
        return empty(event.getItemId()) ? event.getToolCallId() : event.getItemId();
    }

    private static String stepName(ChatEvent event) {
        return "step-" + event.getStep();
    }

    private static String contentKey(ChatEvent event) {
        if (!empty(event.getItemId())) return "item:" + event.getItemId();
        if (event.getIndex() >= 0) return "index:" + event.getIndex();
        return "default";
    }

    private static boolean empty(String value) {
        return value == null || value.length() == 0;
    }

    private static final class State {
        final Map<String, String> textIds = new LinkedHashMap<>();
        final Map<String, String> reasoningIds = new LinkedHashMap<>();
        final Map<String, String> toolIds = new LinkedHashMap<>();
        final Set<String> openText = new LinkedHashSet<>();
        final Set<String> openReasoning = new LinkedHashSet<>();
        final Set<String> openTools = new LinkedHashSet<>();
        Object result;
        boolean terminal;
    }
}
