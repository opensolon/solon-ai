/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.ui.aisdk;

import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.event.ChatEvent;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.ui.aisdk.part.*;
import org.noear.solon.ai.ui.aisdk.part.reasoning.*;
import org.noear.solon.ai.ui.aisdk.part.source.*;
import org.noear.solon.ai.ui.aisdk.part.text.*;
import org.noear.solon.ai.ui.aisdk.util.AiSdkIdGenerator;
import org.noear.solon.web.sse.SseEvent;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Solon AI 流式响应到 Vercel AI SDK UI Message Stream Protocol v1 的包装器
 * <p>
 * 将 {@code ChatModel.prompt().stream()} 返回的 {@code Flux<ChatEvent>}
 * 转换为遵循 Vercel AI SDK 协议的 {@code Flux<SseEvent>}，可直接作为 SSE 端点返回值。
 * <p>
 * 兼容 {@code @ai-sdk/vue} 的 {@code useChat} 和 {@code @ai-sdk/react} 的 {@code useChat}。
 *
 * <pre>{@code
 * // 流式调用（事件流，推荐）
 * AiSdkStreamWrapper.of().toAiSdkStream(chatModel.prompt(prompt).stream());
 *
 * // 阻塞式调用
 * AiSdkStreamWrapper.of().toAiSdkStream(chatModel.prompt(prompt).call());
 *
 * // 使用自定义 ID 策略（如雪花算法）
 * AiSdkStreamWrapper.of(prefix -> prefix + snowflake.nextId())
 *                    .toAiSdkStream(source, metadata);
 * }</pre>
 *
 * @author shaoerkuai
 * @see <a href="https://ai-sdk.dev/docs/ai-sdk-ui/stream-protocol">UI Message Stream Protocol</a>
 * @since 3.9.5
 */
public class AiSdkStreamWrapper {

    private final AiSdkIdGenerator idGenerator;

    /**
     * 使用默认 ID 策略构造
     */
    public AiSdkStreamWrapper() {
        this(AiSdkIdGenerator.DEFAULT);
    }
    /**
     * 使用自定义 ID 策略构造（策略模式）
     *
     * @param idGenerator ID 生成策略
     */
    public AiSdkStreamWrapper(AiSdkIdGenerator idGenerator) {
        this.idGenerator = idGenerator != null ? idGenerator : AiSdkIdGenerator.DEFAULT;
    }

    // ==================== 静态工厂 ====================

    /**
     * 创建默认实例
     */
    public static AiSdkStreamWrapper of() {
        return new AiSdkStreamWrapper();
    }

    /**
     * 创建指定 ID 策略的实例
     *
     * @param idGenerator ID 生成策略
     */
    public static AiSdkStreamWrapper of(AiSdkIdGenerator idGenerator) {
        return new AiSdkStreamWrapper(idGenerator);
    }

    // ==================== 核心转换 ====================

    /**
     * 将 ChatModel 阻塞式响应转换为 Vercel AI SDK 协议格式的 SSE 事件流
     * <p>
     * 适用于 {@code chatModel.prompt(prompt).call()} 返回的单个 {@code ChatResponse}，
     * 将完整结果一次性转换为协议格式的事件序列。
     *
     * <pre>{@code
     * ChatResponse resp = chatModel.prompt(prompt).call();
     * return wrapper.toAiSdkStream(resp);
     * }</pre>
     *
     * @param response ChatModel.prompt().call() 返回的 ChatResponse
     * @return 符合 AI SDK 协议的 SSE 事件流
     */
    public Flux<SseEvent> toAiSdkStream(ChatResponse response) {
        return toAiSdkStream(response, null);
    }

    /**
     * 将 ChatModel 阻塞式响应转换为 Vercel AI SDK 协议格式的 SSE 事件流（附带元数据）
     *
     * @param response ChatModel.prompt().call() 返回的 ChatResponse
     * @param metadata 可选的消息元数据（如 sessionId），将在 start 之后发送给前端
     * @return 符合 AI SDK 协议的 SSE 事件流
     */
    public Flux<SseEvent> toAiSdkStream(ChatResponse response, Map<String, Object> metadata) {
        return Flux.create(sink -> {
            String messageId = idGenerator.ofMessage();
            String reasoningId = idGenerator.ofReasoning();
            String textId = idGenerator.ofText();

            // 1. start
            emit(sink, new StartPart(messageId));

            // 2. metadata（如有）
            if (metadata != null && !metadata.isEmpty()) {
                emit(sink, new MetadataPart(metadata));
            }

            AssistantMessage message = response.getMessage();
            if (message != null) {
                // 3. reasoning（如有）
                String reasoning = message.getThinking();
                if (reasoning != null && !reasoning.isEmpty()) {
                    emit(sink, new ReasoningStartPart(reasoningId));
                    emit(sink, new ReasoningDeltaPart(reasoningId, reasoning));
                    emit(sink, new ReasoningEndPart(reasoningId));
                }

                // 4. 工具调用（如有）
                List<ToolCall> toolCalls = message.getToolCalls();
                if (toolCalls != null && !toolCalls.isEmpty()) {
                    for (ToolCall tc : toolCalls) {
                        String tcId = tc.getId() != null ? tc.getId()
                                : idGenerator.ofToolCall();
                        emit(sink, new ToolInputStartPart(tcId, tc.getName()));
                        if (tc.getArgumentsStr() != null && !tc.getArgumentsStr().isEmpty()) {
                            emit(sink, new ToolInputDeltaPart(tcId, tc.getArgumentsStr()));
                        }
                        emit(sink, new ToolInputAvailablePart(tcId, tc.getName(), tc.getArguments()));
                    }
                }

                // 5. 搜索结果引用（如有）
                List<Map> searchResults = message.getSearchResultsRaw();
                if (searchResults != null && !searchResults.isEmpty()) {
                    for (Map<?, ?> sr : searchResults) {
                        Object url = sr.get("url");
                        if (url != null) {
                            String title = sr.get("title") != null ? sr.get("title").toString() : url.toString();
                            emit(sink, new SourceUrlPart(url.toString(), url.toString(), title));
                        }
                    }
                }

                // 6. 正文内容（使用 getResultContent 获取去除思考标签的纯文本）
                String content = message.getText();
                if (content != null && !content.isEmpty()) {
                    emit(sink, new TextStartPart(textId));
                    emit(sink, new TextDeltaPart(textId, content));
                    emit(sink, new TextEndPart(textId));
                }
            }

            // 7. finish（getFinishReason 已归一化，且兼容只在 choice 上给出原始值的端点）
            String finishReason = response.getFinishReason();
            if (finishReason == null || finishReason.isEmpty()) {
                finishReason = "stop";
            }
            emit(sink, new FinishPart(finishReason, response.getUsage()));

            // 8. [DONE]
            sink.next(new SseEvent().data("[DONE]"));
            sink.complete();
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    /**
     * 将 ChatModel 流式响应转换为 Vercel AI SDK 协议格式的 SSE 事件流（附带元数据）
     *
     * @param source   ChatModel.prompt().stream() 返回的 Flux
     * @param metadata 可选的消息元数据（如 sessionId），将在 start 之后发送给前端
     * @return 符合 AI SDK 协议的 SSE 事件流
     */
    @Deprecated
    public Flux<SseEvent> toAiSdkStreamOfResponses(Flux<ChatResponse> source, Map<String, Object> metadata) {
        return Flux.create(sink -> {
            // 通过策略生成唯一标识
            String messageId = idGenerator.ofMessage();
            String reasoningId = idGenerator.ofReasoning();
            String textId = idGenerator.ofText();

            // 状态跟踪
            AtomicBoolean reasoningStarted = new AtomicBoolean(false);
            AtomicBoolean textStarted = new AtomicBoolean(false);
            AtomicReference<ChatResponse> lastResponse = new AtomicReference<>();

            // 1. start part
            emit(sink, new StartPart(messageId));

            // 2. metadata part（如有）
            if (metadata != null && !metadata.isEmpty()) {
                emit(sink, new MetadataPart(metadata));
            }

            Disposable upstream = source.subscribe(
                    chatResponse -> onNext(sink, chatResponse, lastResponse,
                            reasoningStarted, textStarted, reasoningId, textId),
                    error -> onError(sink, error),
                    () -> onComplete(sink, lastResponse, reasoningStarted, textStarted,
                            reasoningId, textId)
            );
            // 兼容投影也必须遵守取消传播：浏览器断开后停止模型请求，避免后台继续耗费 token。
            sink.onDispose(upstream);
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    // ==================== 流处理回调 ====================

    private void onNext(FluxSink<SseEvent> sink, ChatResponse chatResponse,
                        AtomicReference<ChatResponse> lastResponse,
                        AtomicBoolean reasoningStarted, AtomicBoolean textStarted,
                        String reasoningId, String textId) {
        lastResponse.set(chatResponse);
        AssistantMessage message = chatResponse.getMessage();
        if (message == null) {
            return;
        }

        // --- 推理/思考内容 ---
        if (message.isThinking()) {
            String content = message.getContent();
            if (content != null && !content.isEmpty()) {
                if (!reasoningStarted.get()) {
                    emit(sink, new ReasoningStartPart(reasoningId));
                    reasoningStarted.set(true);
                }
                emit(sink, new ReasoningDeltaPart(reasoningId, content));
            }
            return;
        }

        // 从思考切换到正文：关闭推理阶段
        if (reasoningStarted.get()) {
            emit(sink, new ReasoningEndPart(reasoningId));
            reasoningStarted.set(false);
        }

        // --- 工具调用 ---
        List<ToolCall> toolCalls = message.getToolCalls();
        if (toolCalls != null && !toolCalls.isEmpty()) {
            for (ToolCall tc : toolCalls) {
                String tcId = tc.getId() != null ? tc.getId()
                        : idGenerator.ofToolCall();
                emit(sink, new ToolInputStartPart(tcId, tc.getName()));
                if (tc.getArgumentsStr() != null && !tc.getArgumentsStr().isEmpty()) {
                    emit(sink, new ToolInputDeltaPart(tcId, tc.getArgumentsStr()));
                }
                emit(sink, new ToolInputAvailablePart(tcId, tc.getName(), tc.getArguments()));
            }
        }

        // --- 搜索结果 ---
        List<Map> searchResults = message.getSearchResultsRaw();
        if (searchResults != null && !searchResults.isEmpty()) {
            for (Map<?, ?> sr : searchResults) {
                Object url = sr.get("url");
                if (url != null) {
                    String title = sr.get("title") != null ? sr.get("title").toString() : url.toString();
                    emit(sink, new SourceUrlPart(url.toString(), url.toString(), title));
                }
            }
        }

        // --- 正文内容 ---
        // 使用 getContent() 而非 getResultContent()：后者 trim() 会丢失空白 chunk，破坏 Markdown 格式
        String resultContent = message.getContent();
        if (resultContent != null && !resultContent.isEmpty()) {
            if (!textStarted.get()) {
                emit(sink, new TextStartPart(textId));
                textStarted.set(true);
            }
            emit(sink, new TextDeltaPart(textId, resultContent));
        }
    }

    /**
     * 将 ChatModel 事件流转换为 Vercel AI SDK 协议格式的 SSE 事件流
     *
     * @param source {@code ChatModel.prompt().stream()} 返回的事件流
     * @since 4.1
     */
    public Flux<SseEvent> toAiSdkStream(Flux<ChatEvent> source) {
        return toAiSdkStream(source, null);
    }

    /**
     * 将 ChatModel 事件流转换为 Vercel AI SDK 协议格式的 SSE 事件流（附带元数据）
     *
     * <p>相比旧的帧流实现，事件流可直接映射出此前无发射点的 part：
     * StartStepPart / FinishStepPart（多轮工具调用的步骤边界）、ToolOutputAvailablePart（工具与
     * 服务端工具的执行结果）、SourceUrlPart / SourceDocumentPart（引用）、FilePart（媒体）、
     * AbortPart（上游中止）、DataPart（未建模原始事件）。同时思考/正文边界由核心的
     * 归一化器保证，此处不再重复维护状态机。</p>
     *
     * <p><b>DataPart（RAW 事件）可达性</b>：核心默认事件过滤器（{@code ChatEventFilter.DEFAULT}）
     * 会挡掉 {@code RAW} 与 {@code HEARTBEAT}，因此默认配置下本包装器收不到 RAW 事件，
     * {@code data-*} part 不会发射。需要透传未建模原始帧时，调用方必须在构建流时显式开启：
     * {@code chatModel.prompt().eventFilter(ChatEventFilter.all()).stream()}。</p>
     *
     * @param source   事件流
     * @param metadata 可选的消息元数据（如 sessionId），将在 start 之后发送给前端
     * @since 4.1
     */
    public Flux<SseEvent> toAiSdkStream(Flux<ChatEvent> source, Map<String, Object> metadata) {
        return Flux.create(sink -> {
            String messageId = idGenerator.ofMessage();
            EventState state = new EventState(idGenerator.ofReasoning(), idGenerator.ofText());

            // 1. start part
            emit(sink, new StartPart(messageId));

            // 2. metadata part（如有）
            if (metadata != null && !metadata.isEmpty()) {
                emit(sink, new MetadataPart(metadata));
            }

            Disposable upstream = source.subscribe(
                    event -> onEvent(sink, event, state),
                    error -> onError(sink, error, state),
                    () -> onEventComplete(sink, state)
            );
            // 下游 SSE 断开时必须释放 ChatEvent 上游订阅，否则模型请求会继续运行。
            sink.onDispose(upstream);
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    /**
     * 事件流的跨帧状态（仅记录未闭合的 part 与终态信息）
     *
     * @since 4.1
     */
    private static final class EventState {
        final String reasoningId;
        final String textId;

        boolean textOpen;
        boolean reasoningOpen;

        /**
         * 已发过 tool-input-start 的工具调用（核心逐片发 ARGS_DELTA 时用于去重）
         *
         * @since 4.1
         */
        final java.util.Set<String> toolInputStarted = new java.util.LinkedHashSet<>();

        String finishReason;
        AiUsage usage;

        /**
         * 是否收到过终态 ERROR 事件（核心失败路径：先发 ERROR 再 Flux.error）
         *
         * @since 4.1
         */
        boolean errorSeen;

        /**
         * ERROR 事件携带的错误文案（比 onError 的 Throwable 更完整，优先使用）
         *
         * @since 4.1
         */
        String errorText;

        EventState(String reasoningId, String textId) {
            this.reasoningId = reasoningId;
            this.textId = textId;
        }
    }

    private void onEvent(FluxSink<SseEvent> sink, ChatEvent event, EventState state) {
        switch (event.getType()) {
            case STEP_START:
                emit(sink, new StartStepPart());
                break;

            case STEP_END:
                //步骤内未闭合的内容 part 先收口，避免跨步骤悬挂
                closeOpenParts(sink, state);
                emit(sink, new FinishStepPart());
                break;

            case TEXT_START:
                openText(sink, state);
                break;

            case TEXT_DELTA:
                if (isEmpty(event.getText())) {
                    break;
                }
                openText(sink, state);
                emit(sink, new TextDeltaPart(state.textId, event.getText()));
                break;

            case TEXT_END:
                if (state.textOpen) {
                    emit(sink, new TextEndPart(state.textId));
                    state.textOpen = false;
                }
                break;

            case THINKING_START:
                openReasoning(sink, state);
                break;

            case THINKING_DELTA:
                if (isEmpty(event.getText())) {
                    break;
                }
                openReasoning(sink, state);
                emit(sink, new ReasoningDeltaPart(state.reasoningId, event.getText()));
                break;

            case THINKING_END:
                if (state.reasoningOpen) {
                    emit(sink, new ReasoningEndPart(state.reasoningId));
                    state.reasoningOpen = false;
                }
                break;

            case TOOL_CALL_START:
                //工具输入开始（参数尚未完整）：AI SDK 协议对应 tool-input-start
                emitToolInputStart(sink, event, state);
                break;

            case TOOL_CALL_ARGS_DELTA:
                //工具参数逐片流式：此前无此通道，前端只能等参数拼完才看到
                emitToolInputDelta(sink, event, state);
                break;

            case TOOL_CALL_CHUNK:
            case TOOL_CALL_END:
                emitToolCall(sink, event, state);
                break;

            case TOOL_RESULT:
                //本地工具执行结果：旧实现下对流不可见（执行完直接递归）
                emit(sink, new ToolOutputAvailablePart(event.getToolCallId(), event.getText()));
                break;

            case SERVER_TOOL_RESULT:
                //服务端工具结果（联网搜索 / 代码执行 / MCP）：旧实现下被拍平进正文
                emit(sink, new ToolOutputAvailablePart(
                        event.getItemId() != null ? event.getItemId() : event.getSubType(),
                        event.getText()));
                break;

            case CITATION:
                emitCitation(sink, event);
                break;

            case MEDIA_DONE:
                emitMedia(sink, event);
                break;

            case USAGE:
                if (event.getUsage() != null) {
                    state.usage = event.getUsage();
                }
                break;

            case ABORT:
                emit(sink, new AbortPart(event.getText()));
                break;

            case ERROR:
                //核心失败路径：先发终态 ERROR 事件（携带 error/response/usage）再 Flux.error。
                //这里只记录事件携带的信息，error/finish part 由 onError 兜底统一发射，
                //否则同一次失败会发两遍 error part
                state.errorSeen = true;
                state.errorText = errorMessageOf(event);
                if (event.getUsage() != null) {
                    state.usage = event.getUsage();
                }
                break;

            case RESPONSE_END:
                //终态由事件明确携带，不再依赖「最后一帧碰巧带 usage」
                if (event.getUsage() != null) {
                    state.usage = event.getUsage();
                }
                state.finishReason = finishReasonOf(event, state.finishReason);
                break;

            case RAW:
                //默认事件过滤器（ChatEventFilter.DEFAULT）会挡掉 RAW，只有调用方
                //显式 eventFilter(ChatEventFilter.all()) 才会收到，详见 toAiSdkStream javadoc
                emit(sink, new RawDataPart(event.getRawType(), event.getRaw() == null ? null : event.getRaw().toJson()));
                break;

            default:
                //STATUS / HEARTBEAT / THINKING_SIGNATURE 等：AI SDK 协议无对应 part
                break;
        }
    }

    private void onEventComplete(FluxSink<SseEvent> sink, EventState state) {
        closeOpenParts(sink, state);

        //防御：核心契约是失败必跟 Flux.error，但若方言直接 complete，已见错误也必须落地，
        //不能把失败流伪装成正常 stop
        if (state.errorSeen) {
            emit(sink, new ErrorPart(state.errorText));
            emit(sink, new FinishPart("error", state.usage));
        } else {
            emit(sink, new FinishPart(state.finishReason == null ? "stop" : state.finishReason, state.usage));
        }

        // [DONE] 终止标记
        sink.next(new SseEvent().data("[DONE]"));
        sink.complete();
    }

    private void closeOpenParts(FluxSink<SseEvent> sink, EventState state) {
        if (state.reasoningOpen) {
            emit(sink, new ReasoningEndPart(state.reasoningId));
            state.reasoningOpen = false;
        }

        if (state.textOpen) {
            emit(sink, new TextEndPart(state.textId));
            state.textOpen = false;
        }
    }

    private void openText(FluxSink<SseEvent> sink, EventState state) {
        if (state.textOpen == false) {
            emit(sink, new TextStartPart(state.textId));
            state.textOpen = true;
        }
    }

    private void openReasoning(FluxSink<SseEvent> sink, EventState state) {
        if (state.reasoningOpen == false) {
            emit(sink, new ReasoningStartPart(state.reasoningId));
            state.reasoningOpen = true;
        }
    }

    /**
     * 工具输入开始（去重：同一工具调用只发一次）
     */
    private String emitToolInputStart(FluxSink<SseEvent> sink, ChatEvent event, EventState state) {
        ToolCall tc = event.getToolCall();

        String tcId = event.getToolCallId();
        if (isEmpty(tcId) && tc != null) {
            tcId = tc.getId();
        }
        if (isEmpty(tcId)) {
            tcId = idGenerator.ofToolCall();
        }

        if (state.toolInputStarted.add(tcId)) {
            emit(sink, new ToolInputStartPart(tcId, tc == null ? null : tc.getName()));
        }

        return tcId;
    }

    /**
     * 工具参数增量
     */
    private void emitToolInputDelta(FluxSink<SseEvent> sink, ChatEvent event, EventState state) {
        String tcId = emitToolInputStart(sink, event, state);

        String delta = event.getText();
        if (isEmpty(delta) == false) {
            emit(sink, new ToolInputDeltaPart(tcId, delta));
        }
    }

    private void emitToolCall(FluxSink<SseEvent> sink, ChatEvent event, EventState state) {
        ToolCall tc = event.getToolCall();
        if (tc == null) {
            return;
        }

        String tcId = tc.getId() != null ? tc.getId() : idGenerator.ofToolCall();

        //参数未走增量通道时（整块方言），在此补齐 start + delta
        if (state.toolInputStarted.add(tcId)) {
            emit(sink, new ToolInputStartPart(tcId, tc.getName()));

            if (isEmpty(tc.getArgumentsStr()) == false) {
                emit(sink, new ToolInputDeltaPart(tcId, tc.getArgumentsStr()));
            }
        }

        emit(sink, new ToolInputAvailablePart(tcId, tc.getName(), tc.getArguments()));
    }

    private void emitCitation(FluxSink<SseEvent> sink, ChatEvent event) {
        String url = event.getText();
        String sourceId = event.getItemId() != null ? event.getItemId() : url;

        if (isEmpty(url) == false) {
            emit(sink, new SourceUrlPart(sourceId, url, url));
        } else if (isEmpty(sourceId) == false) {
            emit(sink, new SourceDocumentPart(sourceId, event.getSubType(), sourceId));
        }
    }

    private void emitMedia(FluxSink<SseEvent> sink, ChatEvent event) {
        ChatResponse resp = event.getResponse();
        if (resp == null) {
            return;
        }

        //媒体统一从消息取（终态即完整聚合，含流中累积的 mediaBlocks）
        AssistantMessage msg = resp.getMessage();
        if (msg == null || msg.hasMedia() == false) {
            return;
        }

        List<ContentBlock> blocks = msg.getBlocks();
        if (blocks == null) {
            return;
        }

        for (ContentBlock block : blocks) {
            String content = block.getContent();
            if (isEmpty(content) == false) {
                emit(sink, new FilePart(content, block.getMimeType()));
            }
        }
    }

    private static String finishReasonOf(ChatEvent event, String fallback) {
        ChatResponse resp = event.getResponse();
        if (resp != null && isEmpty(resp.getFinishReason()) == false) {
            return resp.getFinishReason();
        }
        return fallback;
    }

    private static boolean isEmpty(String str) {
        return str == null || str.isEmpty();
    }

    /**
     * 未建模原始事件的数据 part
     *
     * @since 4.1
     */
    private static final class RawDataPart extends DataPart {
        private final String dataType;
        private final Object data;

        RawDataPart(String dataType, Object data) {
            this.dataType = dataType == null ? "raw" : dataType;
            this.data = data;
        }

        @Override
        public String getDataType() {
            return dataType;
        }

        @Override
        public Object getData() {
            return data;
        }
    }

    private void onError(FluxSink<SseEvent> sink, Throwable error) {
        //旧帧流投影（deprecated）无 EventState 可复用，仅维持原收尾形态
        emit(sink, new ErrorPart(error.getMessage() != null ? error.getMessage() : "Stream error"));
        emit(sink, new FinishPart());
        sink.next(new SseEvent().data("[DONE]"));
        sink.complete();
    }

    private void onError(FluxSink<SseEvent> sink, Throwable error, EventState state) {
        //为什么：失败路径上已开启的 text/reasoning part 若不收口，前端会永久悬挂在
        //「等待 text-end/reasoning-end」状态，且部分 useChat 实现会丢弃未闭合块的内容。
        //复用正常完成路径的 closeOpenParts，不新造协议帧
        closeOpenParts(sink, state);

        //为什么优先 ERROR 事件的文案：核心失败路径先发携带 error/response/usage 的终态
        //ERROR 事件再 Flux.error，其信息比裸 Throwable 完整；仅当上游未发过 ERROR 事件时
        //才退回 Throwable 文案
        String errorText = state.errorSeen ? state.errorText
                : (error.getMessage() != null ? error.getMessage() : "Stream error");

        //为什么：错误形态跟随既有风格 —— error part + finish part + [DONE]；
        //finishReason 用 "error"（AI SDK 协议 finishReason 枚举中表失败终态的值），
        //并带上已知 usage，让前端出错时也能展示已消耗的 token 信息
        emit(sink, new ErrorPart(errorText));
        emit(sink, new FinishPart("error", state.usage));
        sink.next(new SseEvent().data("[DONE]"));
        sink.complete();
    }

    /**
     * 从终态 ERROR 事件提取错误文案（仅事件携带的 error，不重复读 response：
     * response 是部分聚合，对应内容已随增量事件发过，重发会重复）
     */
    private static String errorMessageOf(ChatEvent event) {
        if (event.getError() != null && event.getError().getMessage() != null) {
            return event.getError().getMessage();
        }
        return "Stream error";
    }

    private void onComplete(FluxSink<SseEvent> sink, AtomicReference<ChatResponse> lastResponse,
                            AtomicBoolean reasoningStarted, AtomicBoolean textStarted,
                            String reasoningId, String textId) {
        // 关闭未结束的 part
        if (reasoningStarted.get()) {
            emit(sink, new ReasoningEndPart(reasoningId));
        }
        if (textStarted.get()) {
            emit(sink, new TextEndPart(textId));
        }

        // 提取 finishReason 和 usage（getFinishReason 已归一化）
        String finishReason = "stop";
        ChatResponse last = lastResponse.get();
        if (last != null && isEmpty(last.getFinishReason()) == false) {
            finishReason = last.getFinishReason();
        }

        // finish part
        AiUsage usage = last != null ? last.getUsage() : null;
        emit(sink, new FinishPart(finishReason, usage));

        // [DONE] 终止标记
        sink.next(new SseEvent().data("[DONE]"));
        sink.complete();
    }

    // ==================== 内部工具 ====================

    private static void emit(FluxSink<SseEvent> sink, AiSdkStreamPart part) {
        if (!sink.isCancelled()) {
            sink.next(new SseEvent().data(part.toJson()));
        }
    }
}
