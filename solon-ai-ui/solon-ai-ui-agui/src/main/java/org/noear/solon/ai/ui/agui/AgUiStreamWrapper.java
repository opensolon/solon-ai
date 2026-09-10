/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package org.noear.solon.ai.ui.agui;

import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.event.ChatEvent;
import org.noear.solon.ai.chat.event.ChatEventType;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.ui.agui.event.*;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.lang.reflect.Method;
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

    /**
     * 将 Agent 事件流转换为 AG-UI 事件流（薄适配，反射解耦）。
     *
     * <p>本模块不依赖 solon-ai-agent（分层：UI 只依赖 core），Agent 事件以 Object 传入：
     * 有 getChatEvent() 的（三个 Delta 事件）直接委托核心状态机；ToolCallStart/End、RunEnd
     * 按方法名识别映射 AG-UI 工具三段式与终态；其余降级 CustomEvent 保留负载。</p>
     *
     * @since 4.1
     */
    public Flux<Event> toAgUiAgentStream(Flux<?> source, String threadId, String runId) {
        final String actualThreadId = empty(threadId) ? defaultThreadId : threadId;
        final String actualRunId = empty(runId) ? defaultRunId : runId;

        return Flux.create(sink -> {
            State state = new State();
            RunStartedEvent started = new RunStartedEvent();
            started.setThreadId(actualThreadId);
            started.setRunId(actualRunId);
            sink.next(started);

            Disposable upstream = source.subscribe(
                    event -> emitAgentEvent(sink, event, state, actualThreadId, actualRunId),
                    error -> finishError(sink, state, actualThreadId, actualRunId, error),
                    () -> finishSuccess(sink, state, actualThreadId, actualRunId));
            sink.onDispose(upstream);
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    private void emitAgentEvent(FluxSink<Event> sink, Object event, State state,
                                String threadId, String runId) {
        if (event == null || sink.isCancelled()) return;

        String simpleName = event.getClass().getSimpleName();

        // Supervisor 的内部决策即使携带 ChatEvent，也只能作为 CUSTOM，不能污染正文或推理。
        if ("SupervisorDeltaEvent".equals(simpleName)) {
            CustomEvent custom = new CustomEvent();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("agentEventType", simpleName);
            payload.put("runId", invoke(event, "getRunId"));
            payload.put("agentName", invoke(event, "getAgentName"));
            payload.put("thinking", invoke(event, "isThinking"));
            String text = str(invoke(event, "getText"));
            if (text != null && !text.isEmpty()) payload.put("text", text);
            ChatEvent supervisorChatEvent = invokeChatEvent(event);
            if (supervisorChatEvent != null) payload.put("chatEvent", chatEventPayload(supervisorChatEvent));
            custom.setName("supervisor-delta");
            custom.setValue(payload);
            custom.setRawEvent(payload);
            sink.next(custom);
            return;
        }

        // 内嵌 ChatEvent 委托核心状态机，复用多块、lazy-open 与幂等 close 逻辑。
        ChatEvent chatEvent = invokeChatEvent(event);
        if (chatEvent != null) {
            for (Event out : map(chatEvent, state, threadId, runId)) {
                if (!sink.isCancelled()) sink.next(out);
            }
            return;
        }


        //Agent 工具事件：ToolCallStart → TOOL_CALL_START；ToolCallEnd → END + RESULT
        if ("ToolCallStartEvent".equals(simpleName)) {
            org.noear.solon.ai.ui.agui.event.ToolCallStartEvent out =
                    new org.noear.solon.ai.ui.agui.event.ToolCallStartEvent();
            String callId = str(invoke(event, "getCallId"));
            out.setToolCallId(callId);
            out.setToolCallName(str(invoke(event, "getToolName")));
            sink.next(out);
            state.openTools.add(callId);
            return;
        }
        if ("ToolCallEndEvent".equals(simpleName)) {
            String callId = str(invoke(event, "getCallId"));
            if (state.openTools.remove(callId)) {
                org.noear.solon.ai.ui.agui.event.ToolCallEndEvent end =
                        new org.noear.solon.ai.ui.agui.event.ToolCallEndEvent();
                end.setToolCallId(callId);
                sink.next(end);
            }
            org.noear.solon.ai.ui.agui.event.ToolCallResultEvent result =
                    new org.noear.solon.ai.ui.agui.event.ToolCallResultEvent();
            result.setToolCallId(callId);
            result.setContent(str(invoke(event, "getText")));
            result.setRole(Role.TOOL);
            sink.next(result);
            return;
        }
        //RunEnd：记录终态，由 finishSuccess 统一收口（关块 + RunFinished/RunError）
        if ("RunEndEvent".equals(simpleName) || "SimpleEndEvent".equals(simpleName)
                || "TeamEndEvent".equals(simpleName)) {
            Object resp = invoke(event, "getResponse");
            if (resp != null) state.result = resp;
            if (Boolean.TRUE.equals(invoke(event, "isAbnormal"))) {
                String text = str(invoke(event, "getText"));
                state.agentError = text == null || text.isEmpty() ? "Agent run failed" : text;
            }
            return;
        }

        //其余 Agent 事件（Plan/HITL/Node/Supervisor/Context/Run/Reason/Action/Simple...）降级 CustomEvent
        CustomEvent custom = new CustomEvent();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("agentEventType", simpleName);
        payload.put("runId", invoke(event, "getRunId"));
        payload.put("agentName", invoke(event, "getAgentName"));
        String text = str(invoke(event, "getText"));
        if (text != null && !text.isEmpty()) {
            payload.put("text", text);
        }
        custom.setName(simpleName);
        custom.setValue(payload);
        custom.setRawEvent(payload);
        sink.next(custom);
    }

    /** 反射取内嵌 ChatEvent（三个 Delta 事件）；无此方法返回 null */
    private static ChatEvent invokeChatEvent(Object event) {
        Object v = invoke(event, "getChatEvent");
        return v instanceof ChatEvent ? (ChatEvent) v : null;
    }

    private static Object invoke(Object target, String methodName) {
        try {
            Method m = target.getClass().getMethod(methodName);
            return m.invoke(target);
        } catch (NoSuchMethodException e) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
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
        if (state.agentError != null) {
            finishError(sink, state, threadId, runId, new IllegalStateException(state.agentError));
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
        Map<String, Object> payload = chatEventPayload(source);
        event.setName(subtype);
        event.setValue(payload);
        // 兼容旧客户端继续从 rawEvent 读取 payload；标准客户端使用 name/value。
        event.setRawEvent(payload);
        return event;
    }

    private static Map<String, Object> chatEventPayload(ChatEvent source) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", source.getType().name());
        if (source.getRawType() != null) payload.put("rawType", source.getRawType());
        if (source.getSubType() != null) payload.put("subType", source.getSubType());
        if (source.getResponseId() != null) payload.put("responseId", source.getResponseId());
        if (source.getProviderResponseId() != null) payload.put("providerResponseId", source.getProviderResponseId());
        payload.put("step", source.getStep());
        if (source.getItemId() != null) payload.put("itemId", source.getItemId());
        if (source.getToolCallId() != null) payload.put("toolCallId", source.getToolCallId());
        if (source.getIndex() >= 0) payload.put("index", source.getIndex());
        if (source.getText() != null) payload.put("text", source.getText());
        if (source.getSearchResult() != null) {
            org.noear.solon.ai.chat.source.SearchResult item = source.getSearchResult();
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("index", item.getIndex());
            value.put("id", item.getId());
            value.put("title", item.getTitle());
            value.put("url", item.getUrl());
            value.put("snippet", item.getSnippet());
            payload.put("searchResult", value);
        }
        if (source.getCitation() != null) {
            org.noear.solon.ai.chat.source.Citation item = source.getCitation();
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("type", item.getType());
            value.put("title", item.getTitle());
            value.put("url", item.getUrl());
            value.put("citedText", item.getCitedText());
            payload.put("citation", value);
        }
        if (source.getAttrs() != null && !source.getAttrs().isEmpty()) payload.put("attrs", source.getAttrs());
        ContentBlock block = source.getBlock();
        if (block != null) {
            Map<String, Object> blockPayload = new LinkedHashMap<>();
            blockPayload.put("type", block.getClass().getSimpleName());
            blockPayload.put("content", block.getContent());
            blockPayload.put("mimeType", block.getMimeType());
            payload.put("block", blockPayload);
        }
        payload.put("raw", source.getRaw());
        return payload;
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
        String prefix = "response:" + (empty(event.getResponseId()) ? "default" : event.getResponseId())
                + ":step:" + event.getStep() + ":";
        if (!empty(event.getItemId())) return prefix + "item:" + event.getItemId();
        if (event.getIndex() >= 0) return prefix + "index:" + event.getIndex();
        return prefix + "default";
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
        String agentError;
        boolean terminal;
    }
}
