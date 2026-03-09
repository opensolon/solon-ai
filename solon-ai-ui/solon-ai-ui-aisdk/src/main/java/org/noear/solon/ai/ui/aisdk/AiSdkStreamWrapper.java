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
import org.noear.solon.ai.chat.ChatChoice;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.ChatResponseDefault;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.ui.aisdk.part.*;
import org.noear.solon.ai.ui.aisdk.part.reasoning.*;
import org.noear.solon.ai.ui.aisdk.part.source.*;
import org.noear.solon.ai.ui.aisdk.part.text.*;
import org.noear.solon.ai.ui.aisdk.util.AiSdkIdGenerator;
import org.noear.solon.web.sse.SseEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Solon AI 流式响应到 Vercel AI SDK UI Message Stream Protocol v1 的包装器
 * <p>
 * 将 {@code ChatModel.prompt().stream()} 返回的 {@code Flux<ChatResponse>}
 * 转换为遵循 Vercel AI SDK 协议的 {@code Flux<SseEvent>}，可直接作为 SSE 端点返回值。
 * <p>
 * 兼容 {@code @ai-sdk/vue} 的 {@code useChat} 和 {@code @ai-sdk/react} 的 {@code useChat}。
 *
 * <pre>{@code
 * // 流式调用
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
                String reasoning = message.getReasoning();
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
                String content = message.getResultContent();
                if (content != null && !content.isEmpty()) {
                    emit(sink, new TextStartPart(textId));
                    emit(sink, new TextDeltaPart(textId, content));
                    emit(sink, new TextEndPart(textId));
                }
            }

            // 7. finish
            String finishReason = "stop";
            ChatChoice choice = response.lastChoice();
            if (choice != null && choice.getFinishReason() != null
                    && !choice.getFinishReason().isEmpty()) {
                finishReason = choice.getFinishReason();
            }
            emit(sink, new FinishPart(finishReason, response.getUsage()));

            // 8. [DONE]
            sink.next(new SseEvent().data("[DONE]"));
            sink.complete();
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    /**
     * 将 ChatModel 流式响应转换为 Vercel AI SDK 协议格式的 SSE 事件流
     *
     * @param source ChatModel.prompt().stream() 返回的 Flux
     * @return 符合 AI SDK 协议的 SSE 事件流
     */
    public Flux<SseEvent> toAiSdkStream(Flux<ChatResponse> source) {
        return toAiSdkStream(source, null);
    }

    /**
     * 将 ChatModel 流式响应转换为 Vercel AI SDK 协议格式的 SSE 事件流（附带元数据）
     *
     * @param source   ChatModel.prompt().stream() 返回的 Flux
     * @param metadata 可选的消息元数据（如 sessionId），将在 start 之后发送给前端
     * @return 符合 AI SDK 协议的 SSE 事件流
     */
    public Flux<SseEvent> toAiSdkStream(Flux<ChatResponse> source, Map<String, Object> metadata) {
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

            source.subscribe(
                    chatResponse -> onNext(sink, chatResponse, lastResponse,
                            reasoningStarted, textStarted, reasoningId, textId),
                    error -> onError(sink, error),
                    () -> onComplete(sink, lastResponse, reasoningStarted, textStarted,
                            reasoningId, textId)
            );
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

    private void onError(FluxSink<SseEvent> sink, Throwable error) {
        emit(sink, new ErrorPart(error.getMessage() != null ? error.getMessage() : "Stream error"));
        emit(sink, new FinishPart());
        sink.next(new SseEvent().data("[DONE]"));
        sink.complete();
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

        // 提取 finishReason 和 usage
        String finishReason = "stop";
        ChatResponse last = lastResponse.get();
        if (last != null) {
            ChatChoice choice = last.lastChoice();
            if (choice != null && choice.getFinishReason() != null
                    && !choice.getFinishReason().isEmpty()) {
                finishReason = choice.getFinishReason();
            }
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
