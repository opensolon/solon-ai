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
package org.noear.solon.ai.llm.dialect.openai;

import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.chat.ChatException;
import org.noear.solon.ai.chat.ChatAccumulator;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.event.ChatEventDefault;
import org.noear.solon.ai.chat.event.ChatEventType;
import org.noear.solon.ai.chat.event.ChatStreamContext;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.chat.tool.ToolCallJsonSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * OpenAI Responses API 响应解析器
 * @author oisin lu
 * @date 2026年1月28日
 */
public class OpenaiResponsesResponseParser {
    private static final Logger log = LoggerFactory.getLogger(OpenaiResponsesResponseParser.class);

    /**
     * 流式工具调用的按请求隔离状态
     */
    private static class StreamState {
        String currentItemId;
        String currentItemType;
        String currentPhase;
        SnapshotDeltaNormalizer currentTextContent;
        SnapshotDeltaNormalizer currentReasoningContent;
        final Map<String, SnapshotDeltaNormalizer> textContents = new LinkedHashMap<>();
        final Map<String, SnapshotDeltaNormalizer> reasoningContents = new LinkedHashMap<>();
        final Map<String, StringBuilder> deliveredTextContents = new LinkedHashMap<>();
        final Map<String, StringBuilder> deliveredReasoningContents = new LinkedHashMap<>();
        final Set<String> textDeltaItems = new HashSet<>();
        final Set<String> reasoningDeltaItems = new HashSet<>();
        final Set<String> emittedFunctionCalls = new HashSet<>();
        final Set<String> emittedMediaItems = new HashSet<>();
        final Set<String> emittedPartialImages = new HashSet<>();
        String currentFunctionCallId;
        String currentFunctionName;
        StringBuilder currentFunctionArguments;
        String currentReasoningId;
        String currentReasoningEncryptedContent;
        String emittedReasoningId;
        String emittedReasoningEncryptedContent;
        final Map<String, ItemSnapshot> itemStates = new LinkedHashMap<>();
        String activeStateKey;
    }

    private static class ItemSnapshot {
        String itemId;
        String itemType;
        String phase;
        SnapshotDeltaNormalizer textContent;
        SnapshotDeltaNormalizer reasoningContent;
        final Map<String, SnapshotDeltaNormalizer> textContents = new LinkedHashMap<>();
        final Map<String, SnapshotDeltaNormalizer> reasoningContents = new LinkedHashMap<>();
        final Map<String, StringBuilder> deliveredTextContents = new LinkedHashMap<>();
        final Map<String, StringBuilder> deliveredReasoningContents = new LinkedHashMap<>();
        final Set<String> textDeltaItems = new HashSet<>();
        final Set<String> reasoningDeltaItems = new HashSet<>();
        final Set<String> emittedFunctionCalls = new HashSet<>();
        final Set<String> emittedMediaItems = new HashSet<>();
        final Set<String> emittedPartialImages = new HashSet<>();
        String functionCallId;
        String functionName;
        StringBuilder functionArguments;
        String reasoningId;
        String reasoningEncryptedContent;
        String emittedReasoningId;
        String emittedReasoningEncryptedContent;
    }

    private static final String STREAM_STATE_KEY = "StreamState";
    private static final String FINAL_OUTPUT_KEYS_KEY = "ResponsesFinalOutputKeys";
    private static final String DEFAULT_STATE_KEY = "__default__";

    public OpenaiResponsesResponseParser() {
    }

    /**
     * 获取或创建流式状态
     */
    private StreamState getOrCreateState(ChatAccumulator acc) {
        return acc.attrIfAbsent(STREAM_STATE_KEY, k -> new StreamState());
    }

    private void activateItemState(StreamState state, String itemId, int outputIndex) {
        if (state == null) return;
        String key = Utils.isNotEmpty(itemId) ? "id:" + itemId
                : (outputIndex >= 0 ? "index:" + outputIndex
                : (state.activeStateKey == null ? DEFAULT_STATE_KEY : state.activeStateKey));
        if (key.equals(state.activeStateKey)) return;
        saveActiveItemState(state);
        state.activeStateKey = key;
        ItemSnapshot saved = state.itemStates.get(key);
        if (saved == null) {
            state.currentItemId = Utils.isEmpty(itemId) ? null : itemId;
            state.currentItemType = null;
            state.currentPhase = null;
            state.currentTextContent = null;
            state.currentReasoningContent = null;
            state.textContents.clear();
            state.reasoningContents.clear();
            state.deliveredTextContents.clear();
            state.deliveredReasoningContents.clear();
            state.textDeltaItems.clear();
            state.reasoningDeltaItems.clear();
            state.emittedFunctionCalls.clear();
            state.emittedMediaItems.clear();
            state.currentFunctionCallId = null;
            state.currentFunctionName = null;
            state.currentFunctionArguments = null;
            state.currentReasoningId = null;
            state.currentReasoningEncryptedContent = null;
            state.emittedReasoningId = null;
            state.emittedReasoningEncryptedContent = null;
            return;
        }
        state.currentItemId = saved.itemId;
        state.currentItemType = saved.itemType;
        state.currentPhase = saved.phase;
        state.currentTextContent = saved.textContent;
        state.currentReasoningContent = saved.reasoningContent;
        state.textContents.clear();
        state.textContents.putAll(saved.textContents);
        state.reasoningContents.clear();
        state.reasoningContents.putAll(saved.reasoningContents);
        state.deliveredTextContents.clear();
        state.deliveredTextContents.putAll(saved.deliveredTextContents);
        state.deliveredReasoningContents.clear();
        state.deliveredReasoningContents.putAll(saved.deliveredReasoningContents);
        state.textDeltaItems.clear();
        state.textDeltaItems.addAll(saved.textDeltaItems);
        state.reasoningDeltaItems.clear();
        state.reasoningDeltaItems.addAll(saved.reasoningDeltaItems);
        state.emittedFunctionCalls.clear();
        state.emittedFunctionCalls.addAll(saved.emittedFunctionCalls);
        state.emittedMediaItems.clear();
        state.emittedMediaItems.addAll(saved.emittedMediaItems);
        state.emittedPartialImages.clear();
        state.emittedPartialImages.addAll(saved.emittedPartialImages);
        state.currentFunctionCallId = saved.functionCallId;
        state.currentFunctionName = saved.functionName;
        state.currentFunctionArguments = saved.functionArguments;
        state.currentReasoningId = saved.reasoningId;
        state.currentReasoningEncryptedContent = saved.reasoningEncryptedContent;
        state.emittedReasoningId = saved.emittedReasoningId;
        state.emittedReasoningEncryptedContent = saved.emittedReasoningEncryptedContent;
    }

    private void saveActiveItemState(StreamState state) {
        if (state == null || state.activeStateKey == null) return;
        ItemSnapshot saved = new ItemSnapshot();
        saved.itemId = state.currentItemId;
        saved.itemType = state.currentItemType;
        saved.phase = state.currentPhase;
        saved.textContent = state.currentTextContent;
        saved.reasoningContent = state.currentReasoningContent;
        saved.textContents.putAll(state.textContents);
        saved.reasoningContents.putAll(state.reasoningContents);
        saved.deliveredTextContents.putAll(state.deliveredTextContents);
        saved.deliveredReasoningContents.putAll(state.deliveredReasoningContents);
        saved.textDeltaItems.addAll(state.textDeltaItems);
        saved.reasoningDeltaItems.addAll(state.reasoningDeltaItems);
        saved.emittedFunctionCalls.addAll(state.emittedFunctionCalls);
        saved.emittedMediaItems.addAll(state.emittedMediaItems);
        saved.emittedPartialImages.addAll(state.emittedPartialImages);
        saved.functionCallId = state.currentFunctionCallId;
        saved.functionName = state.currentFunctionName;
        saved.functionArguments = state.currentFunctionArguments;
        saved.reasoningId = state.currentReasoningId;
        saved.reasoningEncryptedContent = state.currentReasoningEncryptedContent;
        saved.emittedReasoningId = state.emittedReasoningId;
        saved.emittedReasoningEncryptedContent = state.emittedReasoningEncryptedContent;
        state.itemStates.put(state.activeStateKey, saved);
    }

    private SnapshotDeltaNormalizer textNormalizer(StreamState state, ONode event) {
        int contentIndex = optionalIndex(event, "content_index");
        String key = contentIndex >= 0 ? "content:" + contentIndex : "content:-1";
        SnapshotDeltaNormalizer normalizer = state.textContents.get(key);
        if (normalizer == null && contentIndex >= 0) {
            // 只在同一 content 命名空间内兼容“索引后到”的网关帧；summary_index 不得接管匿名 content 状态。
            SnapshotDeltaNormalizer initial = state.textContents.get("content:-1");
            if (initial != null && state.textContents.size() == 1) {
                normalizer = initial;
                state.textContents.remove("content:-1");
                state.textContents.put(key, normalizer);
            }
        }
        if (normalizer == null) {
            normalizer = new SnapshotDeltaNormalizer();
            state.textContents.put(key, normalizer);
        }
        state.currentTextContent = normalizer;
        return normalizer;
    }

    private SnapshotDeltaNormalizer reasoningNormalizer(StreamState state, ONode event) {
        int summaryIndex = optionalIndex(event, "summary_index");
        int contentIndex = optionalIndex(event, "content_index");
        String key = summaryIndex >= 0 ? "summary:" + summaryIndex
                : (contentIndex >= 0 ? "content:" + contentIndex : "content:-1");
        SnapshotDeltaNormalizer normalizer = state.reasoningContents.get(key);
        if (normalizer == null && contentIndex >= 0) {
            // summary_index 与 content_index 是不同的官方命名空间，禁止把匿名 content 状态迁移到 summary。
            SnapshotDeltaNormalizer initial = state.reasoningContents.get("content:-1");
            if (initial != null && state.reasoningContents.size() == 1) {
                normalizer = initial;
                state.reasoningContents.remove("content:-1");
                state.reasoningContents.put(key, normalizer);
            }
        }
        if (normalizer == null) {
            normalizer = new SnapshotDeltaNormalizer();
            state.reasoningContents.put(key, normalizer);
        }
        state.currentReasoningContent = normalizer;
        return normalizer;
    }

    private String eventItemKey(StreamState state, ONode event, String partName) {
        String itemId = event == null ? null : event.get("item_id").getString();
        if (Utils.isEmpty(itemId) && state != null) itemId = state.currentItemId;
        int outputIndex = optionalIndex(event, "output_index");
        String base = Utils.isNotEmpty(itemId) ? "id:" + itemId
                : (outputIndex >= 0 ? "index:" + outputIndex : DEFAULT_STATE_KEY);
        int partIndex = optionalIndex(event, partName);
        return base + ":" + partName + ":" + partIndex;
    }

    private String outputItemKey(ONode item, ONode event) {
        String itemId = item == null ? null : item.get("id").getString();
        if (Utils.isEmpty(itemId) && event != null) itemId = event.get("item_id").getString();
        int outputIndex = optionalIndex(event, "output_index");
        return Utils.isNotEmpty(itemId) ? "id:" + itemId
                : (outputIndex >= 0 ? "index:" + outputIndex : DEFAULT_STATE_KEY);
    }

    private String outputItemKey(ONode item, ONode event, int fallbackIndex) {
        String key = outputItemKey(item, event);
        return DEFAULT_STATE_KEY.equals(key) && fallbackIndex >= 0 ? "index:" + fallbackIndex : key;
    }

    private String itemKeyForEvent(StreamState state, ONode event) {
        String itemId = event == null ? null : event.get("item_id").getString();
        if (Utils.isEmpty(itemId) && state != null) itemId = state.currentItemId;
        int outputIndex = optionalIndex(event, "output_index");
        return Utils.isNotEmpty(itemId) ? "id:" + itemId
                : (outputIndex >= 0 ? "index:" + outputIndex : DEFAULT_STATE_KEY);
    }

    private String deliveredPartKey(StreamState state, ONode event, String partName) {
        return eventItemKey(state, event, partName);
    }

    /** 记录真正交付给 ChatAccumulator 的官方正文增量。 */
    private void recordDeliveredText(StreamState state, ONode event, String value) {
        if (state == null || Utils.isEmpty(value)) return;
        String key = deliveredPartKey(state, event, "content_index");
        StringBuilder buf = state.deliveredTextContents.get(key);
        if (buf == null) {
            buf = new StringBuilder();
            state.deliveredTextContents.put(key, buf);
        }
        buf.append(value);
        state.textDeltaItems.add(key);
    }

    /** 记录真正交付给 ChatAccumulator 的 reasoning 增量。 */
    private void recordDeliveredReasoning(StreamState state, ONode event, String partName, String value) {
        if (state == null || Utils.isEmpty(value)) return;
        String key = deliveredPartKey(state, event, partName);
        StringBuilder buf = state.deliveredReasoningContents.get(key);
        if (buf == null) {
            buf = new StringBuilder();
            state.deliveredReasoningContents.put(key, buf);
        }
        buf.append(value);
        state.reasoningDeltaItems.add(key);
    }

    private String deliveredText(StreamState state, String itemKey, String partKey) {
        if (state == null) return null;
        if (itemKey.equals(state.activeStateKey)) {
            StringBuilder value = state.deliveredTextContents.get(partKey);
            return value == null ? null : value.toString();
        }
        ItemSnapshot saved = state.itemStates.get(itemKey);
        if (saved == null) return null;
        StringBuilder value = saved.deliveredTextContents.get(partKey);
        return value == null ? null : value.toString();
    }

    private String deliveredReasoning(StreamState state, String itemKey, String partKey) {
        if (state == null) return null;
        if (itemKey.equals(state.activeStateKey)) {
            StringBuilder value = state.deliveredReasoningContents.get(partKey);
            return value == null ? null : value.toString();
        }
        ItemSnapshot saved = state.itemStates.get(itemKey);
        if (saved == null) return null;
        StringBuilder value = saved.deliveredReasoningContents.get(partKey);
        return value == null ? null : value.toString();
    }

    /** 将最终快照与已经交付的官方 delta 合并，只返回尚未交付的后缀。 */
    private String missingSuffix(String finalValue, String deliveredValue) {
        if (Utils.isEmpty(finalValue)) return null;
        if (Utils.isEmpty(deliveredValue)) return finalValue;
        if (finalValue.equals(deliveredValue)) return null;
        if (finalValue.startsWith(deliveredValue)) return finalValue.substring(deliveredValue.length());
        // 官方 done/final 与已交付 delta 不一致时，不能盲目重复追加；保留 raw 供诊断。
        log.warn("OpenAI Responses final part is not prefixed by delivered delta; skip duplicate final text");
        return null;
    }

    private boolean appendStreamFinalText(ChatAccumulator acc, StreamState state, ONode event,
                                           String value, boolean reasoning, String partName) {
        if (Utils.isEmpty(value)) return false;
        String itemKey = itemKeyForEvent(state, event);
        String partKey = eventItemKey(state, event, partName);
        String delivered = reasoning
                ? deliveredReasoning(state, itemKey, partKey)
                : deliveredText(state, itemKey, partKey);
        String missing = missingSuffix(value, delivered);
        if (Utils.isEmpty(missing)) return false;
        if (reasoning) {
            AssistantMessage message = new AssistantMessage("", missing, true);
            attachReasoningMetadata(message, state);
            acc.addContentItem(message);
            recordDeliveredReasoning(state, event, partName, missing);
        } else {
            acc.addContentItem(new AssistantMessage(missing));
            recordDeliveredText(state, event, missing);
        }
        return true;
    }

    private ChatEventDefault.Builder withResponseEventAttrs(ChatEventDefault.Builder event, ONode node) {
        if (node == null) return event;
        if (node.hasKey("sequence_number")) event.attr("sequence_number", node.get("sequence_number").getLong());
        if (node.hasKey("content_index")) event.attr("content_index", node.get("content_index").getInt());
        if (node.hasKey("summary_index")) event.attr("summary_index", node.get("summary_index").getInt());
        if (node.hasKey("command_index")) event.attr("command_index", node.get("command_index").getInt());
        return event;
    }
    private StreamState stateForFinalOutput(ChatAccumulator acc) {
        StreamState state = getOrCreateState(acc);
        if (state.activeStateKey == null) {
            state.activeStateKey = DEFAULT_STATE_KEY;
        }
        return state;
    }

    /**
     * 解析响应 JSON
     *
     * @param ctx  流上下文
     * @param json 响应 JSON 字符串
     * @return 是否有有效的选择
     * @since 4.1
     */
    public boolean parseResponse(ChatStreamContext ctx, String json) {
        if (ctx.getAccumulator().isStream()) {
            return parseStreamResponse(ctx, json);
        } else {
            return parseNonStreamResponse(ctx, json);
        }
    }

    /**
     * 解析流式响应
     *
     * <p>内容主干（正文 / 思考 / 工具调用）仍以内容项表达，由核心统一转成事件与边界；
     * 本方法只额外发射「旧实现下只能被丢弃或降级成文本」的事件：生命周期、服务端工具、
     * 思考签名、拒答、媒体渐进帧等。</p>
     *
     * @since 4.1
     */
    public boolean parseStreamResponse(ChatStreamContext ctx, String json) {
        ChatAccumulator acc = ctx.getAccumulator();

        if (json == null || json.isEmpty()) {
            return false;
        }
        if (log.isDebugEnabled()) {
            log.debug("OpenAI Responses stream raw response: {}", json);
        }
        String[] lines = json.split("\n");
        boolean hasContent = false;
        boolean hasMedia = false;
        boolean hasTypedEvent = false;
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            // 处理 SSE 格式：event: xxx 和 data: {...}
            String jsonData = line;
            if (line.startsWith("data:")) {
                jsonData = line.substring(5).trim();
            } else if (line.startsWith("event:")) {
                // 事件类型行，跳过（数据在下一行）
                continue;
            }
            if (jsonData.isEmpty() || "[DONE]".equals(jsonData)) {
                if ("[DONE]".equals(jsonData)) {
                    acc.attrRemove(STREAM_STATE_KEY);
                    if (acc.isFinished() == false) {
                        acc.addContentItem(new AssistantMessage(""));
                        acc.setFinished(true);
                    }
                    return true;
                }
                continue;
            }
            ONode oResp;
            try {
                oResp = ONode.ofJson(jsonData);
            } catch (Exception e) {
                // 单帧损坏不阻断整个流：跳过并告警
                log.warn("OpenAI Responses stream: skip malformed frame: {}", jsonData, e);
                continue;
            }
            if (!oResp.isObject()) {
                continue;
            }

            if (oResp.hasKey("error")) {
                // 顶层 error 帧：流已终止，先清理流式状态再返回
                // 规范提取：error 为对象（{message,type,code}）时不能整体 getString（会得到 null）
                acc.attrRemove(STREAM_STATE_KEY);
                acc.setError(new ChatException(
                        OpenaiDialectSupport.extractErrorMessage(oResp.get("error"))));
                ctx.emit(ctx.event(ChatEventType.ERROR)
                        .rawType("error")
                        .error(acc.getError())
                        .raw(oResp)
                        .build());
                return true;
            }

            String eventType = oResp.get("type").getString();
            if (Utils.isNotEmpty(eventType)) {
                hasTypedEvent = true;
            }
            StreamState itemState = acc.attrAs(STREAM_STATE_KEY);
            if (itemState != null) {
                String itemId = oResp.get("item_id").getString();
                int outputIndex = optionalIndex(oResp, "output_index");
                if (Utils.isNotEmpty(itemId) || outputIndex >= 0) {
                    activateItemState(itemState, itemId, outputIndex);
                }
            }
            if ("keepalive".equals(eventType)) {
                // SDK 对 keepalive 事件只做流保活，不应被当成模型内容或 RAW。
                ctx.emit(ctx.event(ChatEventType.HEARTBEAT)
                        .rawType(eventType).raw(oResp).build());
                hasContent = true;
            } else if ("error".equals(eventType)) {
                acc.attrRemove(STREAM_STATE_KEY);
                // error 事件可能把 message/type/code 平链在帧顶层（无 error 子对象）
                ONode oError = oResp.hasKey("error") ? oResp.get("error") : oResp;
                acc.setError(new ChatException(OpenaiDialectSupport.extractErrorMessage(oError)));
                ctx.emit(ctx.event(ChatEventType.ERROR)
                        .rawType(eventType)
                        .error(acc.getError())
                        .raw(oResp)
                        .build());
                return true;
            } else if ("response.created".equals(eventType) || "response.in_progress".equals(eventType)
                    || "response.queued".equals(eventType)) {
                // 响应创建/进行中/排队，可以设置模型信息
                ONode response = oResp.get("response");
                if (response != null) {
                    acc.setModel(response.get("model").getString());
                    // 供应商响应标识：记录一次，本步后续事件自动预填（关联供应商日志排障用）
                    ctx.setProviderResponseId(response.get("id").getString());
                }

                // 旧实现下这三类帧被整帧丢弃，订阅方无法感知服务端状态推进
                ctx.emit(ctx.event(ChatEventType.STATUS)
                        .rawType(eventType)
                        .itemId(response == null ? null : response.get("id").getString())
                        .raw(oResp)
                        .build());
            } else if ("response.audio.delta".equals(eventType)
                    || "response.audio.done".equals(eventType)
                    || "response.audio.transcript.delta".equals(eventType)
                    || "response.audio.transcript.done".equals(eventType)) {
                // openai-java ResponseStreamEvent 已建模的音频事件；音频本体/转写均保留在媒体事件中。
                boolean transcript = eventType.startsWith("response.audio.transcript.");
                boolean done = eventType.endsWith(".done");
                String delta = transcript
                        ? firstNonEmpty(oResp, "delta", "transcript", "text")
                        : oResp.get("delta").getString();
                ChatEventType mediaType = done ? ChatEventType.MEDIA_DONE : ChatEventType.MEDIA_PARTIAL;
                ChatEventDefault.Builder event = ctx.event(mediaType).rawType(eventType)
                        .subType(transcript ? "audio_transcript" : "audio")
                        .text(delta).raw(oResp);
                if (oResp.hasKey("sequence_number")) event.attr("sequence_number", oResp.get("sequence_number").getLong());
                ctx.emit(event.build());
                hasMedia = true;
            } else if ("response.output_text.annotation.added".equals(eventType)) {
                // 引用（联网搜索/文件检索的来源标注）：旧实现无分支，整帧丢弃
                ONode annotation = oResp.getOrNull("annotation");
                ctx.emit(ctx.event(ChatEventType.CITATION)
                        .rawType(eventType)
                        .itemId(oResp.get("item_id").getString())
                        .index(optionalIndex(oResp, "annotation_index"))
                        .text(extractAnnotationText(annotation))
                        .raw(oResp)
                        .build());
            } else if (eventType != null && isServerToolEvent(eventType)) {
                // 服务端工具事件（联网搜索 / 代码执行 / MCP / 文件检索 / 图像生成）。
                if ("response.image_generation_call.partial_image".equals(eventType)) {
                    StreamState partialState = getOrCreateState(acc);
                    int partialIndex = optionalIndex(oResp, "partial_image_index");
                    String partialKey = itemKeyForEvent(partialState, oResp) + ":partial_image:" + partialIndex;
                    if (partialIndex < 0 || partialState.emittedPartialImages.add(partialKey)) {
                        hasContent |= emitServerToolEvent(ctx, eventType, oResp);
                        hasMedia = true;
                    }
                } else {
                    hasContent |= emitServerToolEvent(ctx, eventType, oResp);
                }
            } else if ("response.refusal.done".equals(eventType)) {
                // refusal.done 携带的是最终文本：既交付正文投影，又发终态安全事件；按 part 幂等。
                StreamState state = getOrCreateState(acc);
                boolean appended = appendStreamFinalText(acc, state, oResp,
                        firstNonEmpty(oResp, "refusal", "text"), false, "content_index");
                ctx.emit(withResponseEventAttrs(ctx.event(ChatEventType.CONTENT_FILTER)
                                .rawType(eventType)
                                .subType("refusal")
                                .itemId(oResp.get("item_id").getString())
                                .index(optionalIndex(oResp, "output_index"))
                                .text(firstNonEmpty(oResp, "refusal", "text"))
                                .raw(oResp), oResp)
                        .build());
                hasContent |= appended;
            } else if ("response.output_item.added".equals(eventType)) {
                // 新输出项添加
                ONode item = oResp.get("item");
                if (item != null) {
                    StreamState state = getOrCreateState(acc);
                    activateItemState(state, item.get("id").getString(), optionalIndex(oResp, "output_index"));
                    state.currentItemId = item.get("id").getString();
                    state.currentItemType = item.get("type").getString();
                    state.currentPhase = item.get("phase").getString();
                    if (Utils.isNotEmpty(state.currentPhase)) {
                        acc.getAggregationMetadata().put("phase", state.currentPhase);
                    }

                    if ("message".equals(state.currentItemType)) {
                        state.currentTextContent = new SnapshotDeltaNormalizer();
                        state.textContents.put("content:-1", state.currentTextContent);
                    } else if ("reasoning".equals(state.currentItemType)) {
                        state.currentReasoningContent = new SnapshotDeltaNormalizer();
                        state.reasoningContents.put("content:-1", state.currentReasoningContent);
                        String addedId = item.get("id").getString();
                        String addedEncrypted = item.get("encrypted_content").getString();
                        state.currentReasoningId = Utils.isEmpty(addedId) ? null : addedId;
                        state.currentReasoningEncryptedContent = Utils.isEmpty(addedEncrypted) ? null : addedEncrypted;
                        state.emittedReasoningId = null;
                        state.emittedReasoningEncryptedContent = null;
                    } else if ("function_call".equals(state.currentItemType)) {
                        state.currentFunctionCallId = item.get("call_id").getString();
                        state.currentFunctionName = item.get("name").getString();
                        state.currentFunctionArguments = new StringBuilder();
                    }
                }
            } else if ("response.output_item.done".equals(eventType)) {
                ONode item = oResp.get("item");
                if (item != null && "image_generation_call".equals(item.get("type").getString())) {
                    ContentBlock imageBlock = parseImageGenerationCall(item);
                    if (imageBlock != null) {
                        String imageKey = outputItemKey(item, oResp);
                        if (stateForFinalOutput(acc).emittedMediaItems.add(imageKey)) {
                            acc.addMediaBlocks(Collections.singletonList(imageBlock));
                            hasMedia = true;
                        }
                    }
                }
                if (item != null && "message".equals(item.get("type").getString())) {
                    ONode doneOutput = new ONode().asArray();
                    doneOutput.add(item);
                    hasContent |= appendFinalOutput(ctx, acc, doneOutput, stateForFinalOutput(acc));
                }
                StreamState state = acc.attrAs(STREAM_STATE_KEY);
                if (state != null) {
                    if (item != null) {
                        activateItemState(state, item.get("id").getString(), optionalIndex(oResp, "output_index"));
                    }
                    if (item != null && "reasoning".equals(item.get("type").getString())) {
                        String doneId = item.get("id").getString();
                        String doneEncrypted = item.get("encrypted_content").getString();
                        if (Utils.isNotEmpty(doneId)) state.currentReasoningId = doneId;
                        if (Utils.isNotEmpty(doneEncrypted)) state.currentReasoningEncryptedContent = doneEncrypted;
                        if (isReasoningMetadataPending(state)) {
                            AssistantMessage metaMsg = new AssistantMessage("", "", true);
                            attachReasoningMetadata(metaMsg, state);
                            acc.addContentItem(metaMsg);
                            hasContent = true;
                        }
                        if (Utils.isNotEmpty(doneEncrypted)) {
                            ctx.emit(ctx.event(ChatEventType.THINKING_SIGNATURE)
                                    .rawType(eventType).itemId(state.currentReasoningId)
                                    .text(doneEncrypted).raw(oResp).build());
                        }
                    }
                    if (item != null && "function_call".equals(item.get("type").getString())) {
                        String callId = firstNonEmpty(item, "call_id", "id");
                        String name = item.get("name").getString();
                        if (Utils.isNotEmpty(callId) && Utils.isNotEmpty(name)) {
                            state.currentFunctionCallId = callId;
                            state.currentFunctionName = name;
                            hasContent |= flushFunctionCall(acc, state, item.get("arguments").getString());
                        }
                    }
                    if (item != null && Utils.isNotEmpty(item.get("phase").getString())) {
                        state.currentPhase = item.get("phase").getString();
                        acc.getAggregationMetadata().put("phase", state.currentPhase);
                    }
                    state.currentItemId = null;
                    state.currentItemType = null;
                    state.currentPhase = null;
                    state.currentTextContent = null;
                    state.currentReasoningContent = null;
                    state.currentReasoningId = null;
                    state.currentReasoningEncryptedContent = null;
                    state.emittedReasoningId = null;
                    state.emittedReasoningEncryptedContent = null;
                }
            } else if ("response.content_part.added".equals(eventType)) {
                // 内容部分添加
                ONode part = oResp.get("part");
                if (part != null) {
                    String partType = part.get("type").getString();
                    if ("output_text".equals(partType)) {
                        StreamState state = getOrCreateState(acc);
                        state.currentTextContent = new SnapshotDeltaNormalizer();
                        state.textContents.put("content:" + optionalIndex(oResp, "content_index"), state.currentTextContent);
                    } else if ("reasoning_text".equals(partType)) {
                        StreamState state = getOrCreateState(acc);
                        state.currentReasoningContent = new SnapshotDeltaNormalizer();
                        state.reasoningContents.put("content:" + optionalIndex(oResp, "content_index"), state.currentReasoningContent);
                    }
                }
            } else if ("response.reasoning_summary_part.added".equals(eventType)) {
                // 官方 OpenAI o 系列思维链摘要：part 类型为 reasoning_summary_text
                // （SDK ResponseStreamEvent.kt: response.reasoning_summary_part.added）
                StreamState state = getOrCreateState(acc);
                if (state.currentReasoningContent == null) {
                    state.currentReasoningContent = new SnapshotDeltaNormalizer();
                }
            } else if ("response.reasoning_summary_text.delta".equals(eventType)) {
                // 官方 OpenAI o 系列思维链摘要增量
                // （SDK ResponseStreamEvent.kt: response.reasoning_summary_text.delta）
                // 该事件仅官方实现下发，不存在累计快照形态，故不做快照归一（避免无收益的误判风险）
                String delta = oResp.get("delta").getString();
                if (Utils.isNotEmpty(delta)) {
                    StreamState state = getOrCreateState(acc);
                    SnapshotDeltaNormalizer normalizer = reasoningNormalizer(state, oResp);
                    normalizer.append(delta);
                    recordDeliveredReasoning(state, oResp, "summary_index", delta);
                    AssistantMessage thinkingMsg = new AssistantMessage("", delta, true);
                    attachReasoningMetadata(thinkingMsg, state);
                    acc.addContentItem(thinkingMsg);
                    hasContent = true;
                }
            } else if ("response.reasoning_summary_text.done".equals(eventType)) {
                StreamState state = getOrCreateState(acc);
                hasContent |= appendStreamFinalText(acc, state, oResp,
                        oResp.get("text").getString(), true, "summary_index");
            } else if ("response.reasoning_summary_part.done".equals(eventType)) {
                StreamState state = getOrCreateState(acc);
                ONode part = oResp.getOrNull("part");
                String value = part == null ? null : firstNonEmpty(part, "text", "summary_text");
                hasContent |= appendStreamFinalText(acc, state, oResp, value, true, "summary_index");
            } else if ("response.refusal.delta".equals(eventType)) {
                // 官方拒答流式增量（SDK ResponseStreamEvent.kt: response.refusal.delta）：
                // 按普通文本输出，避免拒答内容丢失。同为官方独有事件，不做快照归一
                String delta = oResp.get("delta").getString();
                if (Utils.isNotEmpty(delta)) {
                    StreamState state = getOrCreateState(acc);
                    if (state.currentTextContent == null) {
                        state.currentTextContent = new SnapshotDeltaNormalizer();
                        state.textContents.put("content:-1", state.currentTextContent);
                    }
                    recordDeliveredText(state, oResp, delta);
                    acc.addContentItem(new AssistantMessage(delta));
                    hasContent = true;

                    // 旧实现下拒答被当普通正文输出，订阅方无法识别；
                    // 此处另发专用事件，文本降级同时保留（不破坏现有 UI）
                    ctx.emit(withResponseEventAttrs(ctx.event(ChatEventType.REFUSAL_DELTA)
                                    .rawType(eventType)
                                    .itemId(oResp.get("item_id").getString())
                                    .index(optionalIndex(oResp, "output_index"))
                                    .text(delta)
                                    .raw(oResp), oResp)
                            .build());
                }
            } else if ("response.reasoning_text.delta".equals(eventType)) {
                // 思考增量（DeepSeek Responses：思维链回传为 thinking 消息）
                String delta = oResp.get("delta").getString();
                if (Utils.isNotEmpty(delta)) {
                    StreamState state = acc.attrAs(STREAM_STATE_KEY);
                    if (state != null) {
                        delta = reasoningNormalizer(state, oResp).normalize(delta);
                        if (Utils.isNotEmpty(delta)) {
                            recordDeliveredReasoning(state, oResp, "content_index", delta);
                            AssistantMessage thinkingMsg = new AssistantMessage("", delta, true);
                            attachReasoningMetadata(thinkingMsg, state);
                            acc.addContentItem(thinkingMsg);
                            hasContent = true;
                        }
                    } else {
                        // 未处于 reasoning item 上下文（缺 output_item.added / content_part.added 前置事件）：
                        // 拒绝输出为 thinking 消息，防止非思考内容被误标
                        log.warn("OpenAI Responses stream: ignored reasoning_text.delta without reasoning context: {}", delta);
                    }
                }
            } else if ("response.reasoning_text.done".equals(eventType)) {
                StreamState state = getOrCreateState(acc);
                hasContent |= appendStreamFinalText(acc, state, oResp,
                        oResp.get("text").getString(), true, "content_index");
            } else if ("response.output_text.done".equals(eventType)) {
                StreamState state = getOrCreateState(acc);
                hasContent |= appendStreamFinalText(acc, state, oResp,
                        oResp.get("text").getString(), false, "content_index");
            } else if ("response.content_part.done".equals(eventType)) {
                StreamState state = getOrCreateState(acc);
                ONode part = oResp.getOrNull("part");
                String partType = part == null ? null : part.get("type").getString();
                String value = part == null ? null : firstNonEmpty(part, "text", "refusal");
                boolean refusal = "refusal".equals(partType);
                boolean reasoning = "reasoning_text".equals(partType);
                hasContent |= appendStreamFinalText(acc, state, oResp, value, reasoning, "content_index");
                if (refusal && Utils.isNotEmpty(value)) {
                    ctx.emit(withResponseEventAttrs(ctx.event(ChatEventType.CONTENT_FILTER)
                                    .rawType(eventType).subType("refusal")
                                    .itemId(oResp.get("item_id").getString())
                                    .index(optionalIndex(oResp, "output_index"))
                                    .text(value).raw(oResp), oResp).build());
                }
            } else if ("response.completed".equals(eventType)) {
                ONode response = oResp.get("response");
                StreamState completedState = stateForFinalOutput(acc);
                if (response != null) {
                    acc.setModel(response.get("model").getString());
                    AiUsage usage = parseUsage(response.getOrNull("usage"));
                    if (usage != null) {
                        acc.setUsage(usage);
                    }
                    hasContent |= appendFinalOutput(ctx, acc, response.getOrNull("output"), completedState);
                }
                acc.attrRemove(STREAM_STATE_KEY);
                if (acc.hasContentItems() == false) {
                    acc.addContentItem(new AssistantMessage(""));
                }
                acc.setFinished(true);
                hasContent = true;
            } else if ("response.output_text.delta".equals(eventType)) {
                String delta = oResp.get("delta").getString();
                if (Utils.isNotEmpty(delta)) {
                    StreamState state = getOrCreateState(acc);
                    recordDeliveredText(state, oResp, delta);
                    acc.addContentItem(new AssistantMessage(delta));
                    hasContent = true;
                }
            } else if ("response.content_part.delta".equals(eventType)) {
                ONode delta = oResp.get("delta");
                if (delta != null) {
                    String text = delta.get("text").getString();
                    if (Utils.isNotEmpty(text)) {
                        StreamState state = getOrCreateState(acc);
                        if ("reasoning_text".equals(delta.get("type").getString())) {
                            if (state != null) {
                                recordDeliveredReasoning(state, oResp, "content_index", text);
                                AssistantMessage thinkingMsg = new AssistantMessage("", text, true);
                                attachReasoningMetadata(thinkingMsg, state);
                                acc.addContentItem(thinkingMsg);
                                hasContent = true;
                            }
                        } else {
                            if (state != null) recordDeliveredText(state, oResp, text);
                            acc.addContentItem(new AssistantMessage(text));
                            hasContent = true;
                        }
                    }
                }
            } else if ("response.function_call_arguments.delta".equals(eventType)) {
                String delta = oResp.get("delta").getString();
                if (Utils.isNotEmpty(delta)) {
                    StreamState state = getOrCreateState(acc);
                    if (state.currentFunctionArguments == null) state.currentFunctionArguments = new StringBuilder();
                    state.currentFunctionArguments.append(delta);
                }
            } else if ("response.function_call_arguments.done".equals(eventType)) {
                StreamState state = acc.attrAs(STREAM_STATE_KEY);
                if (state != null && state.currentFunctionCallId != null && state.currentFunctionName != null) {
                    hasContent |= flushFunctionCall(acc, state, oResp.get("arguments").getString());
                }
            } else if ("response.incomplete".equals(eventType)) {
                // 响应未完成（如 max_output_tokens 截断等）：同样结束流，避免悬挂
                ONode response = oResp.get("response");
                StreamState incompleteState = stateForFinalOutput(acc);
                if (response != null) {
                    acc.setModel(response.get("model").getString());
                    AiUsage usage = parseUsage(response.getOrNull("usage"));
                    if (usage != null) {
                        acc.setUsage(usage);
                    }
                    hasContent |= appendFinalOutput(ctx, acc, response.getOrNull("output"), incompleteState);
                    // 回填 finishReason：incomplete_details.reason（如 max_output_tokens → length）
                    ONode incompleteDetails = response.getOrNull("incomplete_details");
                    if (incompleteDetails != null) {
                        String reason = incompleteDetails.get("reason").getString();
                        if (Utils.isNotEmpty(reason)) {
                            acc.lastFinishReason = "max_output_tokens".equals(reason) ? "length" : reason;
                        }
                    }
                }
                acc.attrRemove(STREAM_STATE_KEY);

                if (acc.hasContentItems() == false) {
                    acc.addContentItem(new AssistantMessage(""));
                }

                acc.setFinished(true);
                ctx.emit(ctx.event(ChatEventType.ABORT)
                        .rawType(eventType)
                        .text(acc.getLastFinishReasonNormalized())
                        .raw(oResp)
                        .build());
                hasContent = true;
            } else if ("response.failed".equals(eventType)) {
                // 响应失败，清理状态
                acc.attrRemove(STREAM_STATE_KEY);
                ONode response = oResp.get("response");
                if (response != null) {
                    acc.setModel(response.get("model").getString());
                    AiUsage usage = parseUsage(response.getOrNull("usage"));
                    if (usage != null) {
                        acc.setUsage(usage);
                    }
                    ONode error = response.get("error");
                    // 与非流式 error 提取保持一致：格式化为 [type/code] message
                    acc.setError(new ChatException(
                            OpenaiDialectSupport.extractErrorMessage(error != null ? error : response)));
                } else {
                    acc.setError(new ChatException("Response failed"));
                }
                ctx.emit(ctx.event(ChatEventType.ERROR)
                        .rawType(eventType)
                        .error(acc.getError())
                        .raw(oResp)
                        .build());
                acc.setFinished(true);
                // 失败也视为有效处理帧，防止错误被当作解析失败吞掉
                hasContent = true;
            } else {
                // 未建模事件：旧实现静默丢弃，现在以 RAW 透出（默认不投递，需显式开启）
                ctx.emit(ctx.event(ChatEventType.RAW)
                        .rawType(eventType)
                        .raw(oResp)
                        .build());
                //RAW 是已消费的合法模型帧，不能让调用方误判为不可识别响应。
                hasContent = true;
            }
        }
        // 有效处理过内容项 或 media 均视为解析成功（纯 media 事件无 choice 时也要 true）
        return hasContent || hasMedia || hasTypedEvent;
    }

    /**
     * 服务端工具事件前缀（由模型服务方执行，不经本地工具链）
     *
     * @since 4.1
     */
    private static final String[] SERVER_TOOL_PREFIXES = {
            "response.web_search_call",
            "response.file_search_call",
            "response.code_interpreter_call",
            "response.computer_call",
            "response.mcp_call",
            "response.mcp_call_arguments",
            "response.mcp_list_tools",
            "response.image_generation_call",
            "response.shell_call",
            "response.local_shell_call",
            "response.custom_tool_call_input"
    };

    /**
     * 是否服务端工具事件
     *
     * @since 4.1
     */
    private static boolean isServerToolEvent(String eventType) {
        for (String prefix : SERVER_TOOL_PREFIXES) {
            if (eventType.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private String extractEventText(ONode node) {
        if (node == null) return null;
        String value = firstNonEmpty(node, "delta", "arguments", "input", "code", "command", "text", "transcript");
        if (Utils.isNotEmpty(value)) return value;
        ONode delta = node.getOrNull("delta");
        if (delta != null && delta.isObject()) {
            String stdout = delta.get("stdout").getString();
            String stderr = delta.get("stderr").getString();
            if (Utils.isNotEmpty(stdout) && Utils.isNotEmpty(stderr)) return stdout + stderr;
            return Utils.isNotEmpty(stdout) ? stdout : stderr;
        }
        ONode output = node.getOrNull("output");
        if (output != null && !output.isNull()) return output.isValue() ? output.getString() : output.toJson();
        return null;
    }

    private String firstNonEmpty(ONode node, String... names) {
        if (node == null) return null;
        for (String name : names) {
            String value = node.get(name).getString();
            if (Utils.isNotEmpty(value)) return value;
        }
        return null;
    }

    private int optionalIndex(ONode node, String name) {
        return node != null && node.hasKey(name) && !node.get(name).isNull()
                ? node.get(name).getInt() : -1;
    }

    /**
     * 发射服务端工具事件
     * <p>供应商能力差异走 {@code subType}，不膨胀事件类型枚举。</p>
     *
     * @since 4.1
     */
    private boolean emitServerToolEvent(ChatStreamContext ctx, String eventType, ONode oResp) {
        String subType = eventType.substring("response.".length());
        int dot = subType.indexOf('.');
        String phase = dot < 0 ? "" : subType.substring(dot + 1);
        subType = dot < 0 ? subType : subType.substring(0, dot);

        ChatEventType type;
        if ("partial_image".equals(phase)) {
            // 图像渐进帧：属媒体而非工具语义
            type = ChatEventType.MEDIA_PARTIAL;
        } else if (phase.endsWith("delta")) {
            type = ChatEventType.SERVER_TOOL_ARGS_DELTA;
        } else if ("completed".equals(phase) || "done".equals(phase) || "failed".equals(phase)
                || phase.endsWith(".done")) {
            type = ChatEventType.SERVER_TOOL_RESULT;
        } else {
            // in_progress / searching / generating / interpreting 等
            type = ChatEventType.SERVER_TOOL_START;
        }

        ChatEventDefault.Builder event = ctx.event(type)
                .rawType(eventType)
                .subType(subType)
                .itemId(oResp.get("item_id").getString())
                .index(optionalIndex(oResp, "output_index"))
                .text(extractEventText(oResp))
                .raw(oResp);
        if (oResp.hasKey("sequence_number")) event.attr("sequence_number", oResp.get("sequence_number").getLong());
        if (oResp.hasKey("content_index")) event.attr("content_index", oResp.get("content_index").getInt());
        if (oResp.hasKey("summary_index")) event.attr("summary_index", oResp.get("summary_index").getInt());
        if (oResp.hasKey("command_index")) event.attr("command_index", oResp.get("command_index").getInt());
        if (oResp.hasKey("partial_image_index")) event.attr("partial_image_index", oResp.get("partial_image_index").getInt());
        if (oResp.hasKey("partial_image_b64")) {
            String b64 = oResp.get("partial_image_b64").getString();
            if (Utils.isNotEmpty(b64)) event.block(ImageBlock.ofBase64(b64));
        }
        ctx.emit(event.build());
        return true;
    }

    /**
     * 是否服务端工具输出项（非流式 {@code output[].type}）
     *
     * <p>image_generation_call 不在内：它已作为媒体块收入消息。</p>
     *
     * @since 4.1
     */
    private static boolean isServerToolItem(String itemType) {
        if (itemType == null) {
            return false;
        }

        return "web_search_call".equals(itemType)
                || "file_search_call".equals(itemType)
                || "code_interpreter_call".equals(itemType)
                || "computer_call".equals(itemType)
                || "mcp_call".equals(itemType)
                || "mcp_list_tools".equals(itemType)
                || "mcp_approval_request".equals(itemType)
                || "mcp_approval_response".equals(itemType)
                || "shell_call".equals(itemType)
                || "shell_call_output".equals(itemType)
                || "local_shell_call".equals(itemType)
                || "local_shell_call_output".equals(itemType)
                || "apply_patch_call".equals(itemType)
                || "apply_patch_call_output".equals(itemType)
                || "custom_tool_call".equals(itemType)
                || "custom_tool_call_output".equals(itemType)
                || "tool_search_call".equals(itemType)
                || "tool_search_output".equals(itemType)
                || "program".equals(itemType)
                || "program_output".equals(itemType)
                || "compaction".equals(itemType)
                || "additional_tools".equals(itemType);
    }

    private String extractAnnotationText(ONode annotation) {
        if (annotation == null) return null;
        String value = annotation.get("url").getString();
        if (Utils.isNotEmpty(value)) return value;
        value = annotation.get("file_id").getString();
        if (Utils.isNotEmpty(value)) return value;
        value = annotation.get("filename").getString();
        return Utils.isNotEmpty(value) ? value : annotation.toJson();
    }

    /**
     * 发射引用事件（非流式 {@code content[].annotations[]}）
     *
     * @since 4.1
     */
    private void emitAnnotations(ChatStreamContext ctx, ONode contentItem) {
        ONode annotations = contentItem.getOrNull("annotations");
        if (annotations == null || annotations.isArray() == false) {
            return;
        }

        for (ONode annotation : annotations.getArray()) {
            ctx.emit(ctx.event(ChatEventType.CITATION)
                    .rawType("response.output_text.annotation")
                    .subType(annotation.get("type").getString())
                    .text(extractAnnotationText(annotation))
                    .raw(annotation)
                    .build());
        }
    }

    /**
     * 解析非流式响应
     *
     * <p>非流式同样要给出扩展语义事件：错误、中止（cancelled / incomplete）、拒答、引用、
     * 服务端工具结果——这些语义并非流式独有。</p>
     *
     * @since 4.1
     */
    public boolean parseNonStreamResponse(ChatStreamContext ctx, String json) {
        ChatAccumulator acc = ctx.getAccumulator();

        if ("[DONE]".equals(json)) {
            if (acc.isFinished() == false) {
                acc.addContentItem(new AssistantMessage(""));
                acc.setFinished(true);
            }
            return true;
        }
        ONode oResp = ONode.ofJson(json);
        if (!oResp.isObject()) {
            return false;
        }
        // 检查错误（规范提取：error 为对象时不能整体 getString）
        if (oResp.hasKey("error") && !oResp.get("error").isNull()) {
            acc.setError(new ChatException(
                    OpenaiDialectSupport.extractErrorMessage(oResp.get("error"))));
            ctx.emit(ctx.event(ChatEventType.ERROR)
                    .rawType("error")
                    .error(acc.getError())
                    .raw(oResp)
                    .build());
            acc.setFinished(true);
            return true;
        }
        // 检查状态
        String status = oResp.get("status").getString();
        if ("failed".equals(status)) {
            ONode error = oResp.getOrNull("error");
            acc.setError(new ChatException(OpenaiDialectSupport.extractErrorMessage(
                    error != null ? error : oResp)));
            ctx.emit(ctx.event(ChatEventType.ERROR)
                    .rawType("response.failed")
                    .error(acc.getError())
                    .raw(oResp)
                    .build());
            acc.setFinished(true);
            if (!acc.hasContentItems()) {
                acc.addContentItem(new AssistantMessage(""));
            }
            return true;
        }
        if ("cancelled".equals(status)) {
            acc.lastFinishReason = "cancelled";
            // 与流式 response.incomplete 的 ABORT 对称：服务端明确中止，不是正常 completed
            ctx.emit(ctx.event(ChatEventType.ABORT)
                    .rawType("response.cancelled")
                    .subType("cancelled")
                    .raw(oResp)
                    .build());
        } else if ("incomplete".equals(status)) {
            // 输出被截断（如 max_output_tokens）：回填 finishReason，与流式 response.incomplete 对齐
            ONode incompleteDetails = oResp.getOrNull("incomplete_details");
            String reason = incompleteDetails == null ? null : incompleteDetails.get("reason").getString();
            if (Utils.isNotEmpty(reason)) {
                acc.lastFinishReason = "max_output_tokens".equals(reason) ? "length" : reason;
            } else {
                acc.lastFinishReason = "length";
            }
            ctx.emit(ctx.event(ChatEventType.ABORT)
                    .rawType("response.incomplete")
                    .subType(reason)
                    .raw(oResp)
                    .build());
        }
        // 设置模型信息
        acc.setModel(oResp.get("model").getString());
        // 解析 output 数组
        ONode outputArray = oResp.getOrNull("output");
        if (outputArray != null && outputArray.isArray()) {
            StringBuilder textContent = new StringBuilder();
            StringBuilder reasoningContent = new StringBuilder();
            String reasoningItemId = null;
            String reasoningEncryptedContent = null;
            List<ContentBlock> mediaBlocks = new ArrayList<>();
            List<ToolCall> allToolCalls = new ArrayList<>();
            List<Map> allToolCallsRaw = new ArrayList<>();
            boolean hasRefusal = false;
            String assistantPhase = null;
            for (ONode outputItem : outputArray.getArray()) {
                String itemType = outputItem.get("type").getString();
                if ("reasoning".equals(itemType)) {
                    // 思考内容：官方 OpenAI 的 reasoning item（多轮回放需携带 id/encrypted_content）；
                    // DeepSeek Responses 为 reasoning_text 私有扩展
                    String itemId = outputItem.get("id").getString();
                    String encrypted = outputItem.get("encrypted_content").getString();
                    if (Utils.isNotEmpty(itemId)) {
                        reasoningItemId = itemId;
                    }
                    if (Utils.isNotEmpty(encrypted)) {
                        reasoningEncryptedContent = encrypted;
                    }
                    ONode contentArray = outputItem.getOrNull("content");
                    if (contentArray != null && contentArray.isArray()) {
                        for (ONode contentItem : contentArray.getArray()) {
                            String contentType = contentItem.get("type").getString();
                            if ("reasoning_text".equals(contentType) || "text".equals(contentType)) {
                                String text = contentItem.get("text").getString();
                                if (Utils.isNotEmpty(text)) {
//                                    if (reasoningContent.length() > 0) {
//                                        reasoningContent.append("\n");
//                                    }
                                    reasoningContent.append(text);
                                }
                            }
                        }
                    }
                    // 兼容仅 summary 的响应（DeepSeek 可能只给摘要）
                    if (reasoningContent.length() == 0) {
                        ONode summaryArray = outputItem.getOrNull("summary");
                        if (summaryArray != null && summaryArray.isArray()) {
                            for (ONode summaryItem : summaryArray.getArray()) {
                                String text = summaryItem.get("text").getString();
                                if (Utils.isNotEmpty(text)) {
//                                    if (reasoningContent.length() > 0) {
//                                        reasoningContent.append("\n");
//                                    }
                                    reasoningContent.append(text);
                                }
                            }
                        }
                    }
                } else if ("message".equals(itemType)) {
                    // 解析消息内容：output_text / refusal / 兼容 image
                    if (Utils.isNotEmpty(outputItem.get("phase").getString())) {
                        assistantPhase = outputItem.get("phase").getString();
                    }
                    ONode contentArray = outputItem.getOrNull("content");
                    if (contentArray != null && contentArray.isArray()) {
                        for (ONode contentItem : contentArray.getArray()) {
                            String contentType = contentItem.get("type").getString();
                            if ("output_text".equals(contentType) || "text".equals(contentType)) {
                                String text = contentItem.get("text").getString();
                                if (Utils.isNotEmpty(text)) {
                                    textContent.append(text);
                                }
                                // 引用（联网搜索 / 文件检索的来源标注）：与流式
                                // response.output_text.annotation.added 对称，非流式此前整块丢弃
                                emitAnnotations(ctx, contentItem);
                            } else if ("refusal".equals(contentType)) {
                                String refusal = contentItem.get("refusal").getString();
                                if (Utils.isEmpty(refusal)) {
                                    refusal = contentItem.get("text").getString();
                                }
                                if (Utils.isNotEmpty(refusal)) {
                                    textContent.append(refusal);
                                    // 旧实现下拒答被当普通正文输出，订阅方无法识别；
                                    // 与流式一致：专用事件 + 文本降级并存
                                    ctx.emit(ctx.event(ChatEventType.REFUSAL_DELTA)
                                            .rawType("response.refusal")
                                            .text(refusal)
                                            .raw(contentItem)
                                            .build());
                                    hasRefusal = true;
                                }
                            } else if ("output_image".equals(contentType) || "image".equals(contentType)
                                    || contentItem.hasKey("image_url")) {
                                ContentBlock imageBlock = parseMessageImageContent(contentItem);
                                if (imageBlock != null) {
                                    mediaBlocks.add(imageBlock);
                                }
                            }
                        }
                    }
                } else if ("image_generation_call".equals(itemType)) {
                    ContentBlock imageBlock = parseImageGenerationCall(outputItem);
                    if (imageBlock != null) {
                        mediaBlocks.add(imageBlock);
                    }
                } else if ("function_call".equals(itemType)) {
                    String callId = outputItem.get("call_id").getString();
                    String functionName = outputItem.get("name").getString();
                    // 非流式解析出口净化：截断损坏的 arguments 禁止入历史（会毒化会话）
                    String arguments = ToolCallJsonSanitizer.sanitizeArguments(
                            outputItem.get("arguments").getString(), functionName);
                    Map<String, Object> argMap = new HashMap<>();
                    if (Utils.isNotEmpty(arguments)) {
                        try {
                            ONode argsNode = ONode.ofJson(arguments);
                            if (argsNode.isObject()) {
                                argMap = argsNode.toBean(Map.class);
                            }
                        } catch (Exception ignored) {
                        }
                    }
                    allToolCalls.add(new ToolCall(callId, callId, functionName, arguments, argMap));

                    Map<String, Object> toolCallRaw = new HashMap<>();
                    toolCallRaw.put("id", callId);
                    toolCallRaw.put("type", "function");
                    Map<String, Object> functionData = new HashMap<>();
                    functionData.put("name", functionName);
                    functionData.put("arguments", arguments);
                    toolCallRaw.put("function", functionData);
                    allToolCallsRaw.add(toolCallRaw);
                } else if (isServerToolItem(itemType)) {
                    // 服务端工具项（联网搜索 / 代码执行 / MCP / 文件检索）：旧实现在非流式下整项丢弃，
                    // 订阅方无从知道模型调用过服务端工具；与流式 SERVER_TOOL_RESULT 对称
                    ctx.emit(ctx.event(ChatEventType.SERVER_TOOL_RESULT)
                            .rawType("response.output_item")
                            .subType(itemType)
                            .itemId(outputItem.get("id").getString())
                            .raw(outputItem)
                            .build());
                }
            }

            if (hasRefusal) {
                // 与流式 response.refusal.done 对称：拒答终态只发一次
                ctx.emit(ctx.event(ChatEventType.CONTENT_FILTER)
                        .rawType("response.refusal.done")
                        .raw(oResp)
                        .build());
            }

            List<ContentBlock> blocksForMsg = null;
            if (!mediaBlocks.isEmpty()) {
                blocksForMsg = new ArrayList<>();
                if (textContent.length() > 0) {
                    blocksForMsg.add(TextBlock.of(textContent.toString()));
                }
                blocksForMsg.addAll(mediaBlocks);
                acc.addMediaBlocks(mediaBlocks);
            }

            // 非流式：与 AbstractChatDialect 对齐——思考与正文合并为单条消息（AssistantMessage 已分离 text/thinking），
            // 不再拆成两个内容项，以保证 getText()/getThinking() 在同一条最终消息上都可用
            String thinkingOut = reasoningContent.toString();
            String textOut = textContent.toString();
            String finishReason = allToolCalls.isEmpty()
                    ? (Utils.isEmpty(acc.lastFinishReason) ? "stop" : acc.lastFinishReason)
                    : "tool_calls";
            // 非流式 status=completed 时上面的状态分支不会写 lastFinishReason，工具调用的终态原因
            // 只在这里算得出来，必须回写累积器：它是框架判定「要不要进下一轮工具调用」的唯一来源
            acc.lastFinishReason = finishReason;

            if (Utils.isEmpty(textOut) && Utils.isEmpty(thinkingOut)
                    && allToolCalls.isEmpty() && blocksForMsg == null) {
                // 回退到 output_text 便捷字段
                textOut = oResp.get("output_text").getString();
            }

            if (Utils.isNotEmpty(textOut) || Utils.isNotEmpty(thinkingOut)
                    || !allToolCalls.isEmpty() || blocksForMsg != null) {
                AssistantMessage msg = new AssistantMessage(
                        textOut == null ? "" : textOut,
                        thinkingOut,
                        false,
                        null,
                        allToolCallsRaw.isEmpty() ? null : allToolCallsRaw,
                        allToolCalls.isEmpty() ? null : allToolCalls,
                        null,
                        blocksForMsg);

                // 官方 OpenAI 多轮回放 reasoning 项需要 id / encrypted_content
                if (Utils.isNotEmpty(reasoningItemId)) {
                    msg.getMetadata().put("reasoning_item_id", reasoningItemId);
                }
                if (Utils.isNotEmpty(reasoningEncryptedContent)) {
                    msg.getMetadata().put("reasoning_encrypted_content", reasoningEncryptedContent);
                }
                    if (Utils.isNotEmpty(assistantPhase)) {
                        msg.getMetadata().put("phase", assistantPhase);
                    }

                acc.addContentItem(msg);
            }
        } else {
            // 如果没有 output 数组，尝试使用便捷字段 output_text / reasoning_text（DeepSeek 顶层字段）
            String reasoningText = oResp.get("reasoning_text").getString();
            String outputText = oResp.get("output_text").getString();
            if (Utils.isNotEmpty(reasoningText) || Utils.isNotEmpty(outputText)) {
                String finishReason = Utils.isEmpty(acc.lastFinishReason) ? "stop" : acc.lastFinishReason;
                acc.addContentItem(new AssistantMessage(
                        outputText == null ? "" : outputText,
                        reasoningText == null ? "" : reasoningText,
                        false));
            }
        }
        // 解析用量信息
        AiUsage usage = parseUsage(oResp.getOrNull("usage"));
        if (usage != null) {
            acc.setUsage(usage);
        }

        // 与 OpenaiChatDialect 对齐：output 全是未识别项（web_search_call / mcp_call 等）且无 output_text 时，
        // 也要补一条空消息内容项，避免上层 getMessage() 拿到 null
        if (acc.hasContentItems() == false) {
            acc.addContentItem(new AssistantMessage(""));
        }

        acc.setFinished(true);
        return true;
    }

    /**
     * 解析 image_generation_call 输出项为 ImageBlock。
     *
     * @since 3.9
     */
    private ContentBlock parseImageGenerationCall(ONode outputItem) {
        if (outputItem == null) {
            return null;
        }
        String result = outputItem.get("result").getString();
        String id = outputItem.get("id").getString();
        String revisedPrompt = outputItem.get("revised_prompt").getString();
        String status = outputItem.get("status").getString();

        ImageBlock block = null;
        if (Utils.isNotEmpty(result)) {
            // result 为 base64（可能带 data: 前缀）
            if (result.startsWith("data:") && result.contains(";base64,")) {
                int comma = result.indexOf(',');
                String header = result.substring(5, result.indexOf(';'));
                String b64 = result.substring(comma + 1);
                block = ImageBlock.ofBase64(b64, header);
            } else if (result.startsWith("http://") || result.startsWith("https://")) {
                block = ImageBlock.ofUrl(result);
            } else {
                block = ImageBlock.ofBase64(result);
            }
        } else if (Utils.isNotEmpty(id)) {
            // 仅 id：占位，便于多轮回传 image_generation_call
            block = ImageBlock.ofUrl("image-generation://" + id);
        }

        if (block == null) {
            return null;
        }

        if (Utils.isNotEmpty(id)) {
            block.metaAdd("id", id);
            block.metaAdd("image_generation_id", id);
        }
        block.metaAdd("source_type", "image_generation_call");
        if (Utils.isNotEmpty(status)) {
            block.metaAdd("status", status);
        }
        if (Utils.isNotEmpty(revisedPrompt)) {
            block.metaAdd("revised_prompt", revisedPrompt);
        }
        return block;
    }

    /**
     * 解析 message content 中的图片项。
     *
     * @since 3.9
     */
    private ContentBlock parseMessageImageContent(ONode contentItem) {
        if (contentItem == null) {
            return null;
        }
        String url = null;
        String data = null;
        if (contentItem.hasKey("image_url")) {
            ONode imageUrl = contentItem.get("image_url");
            if (imageUrl.isValue()) {
                url = imageUrl.getString();
            } else if (imageUrl.isObject()) {
                url = imageUrl.get("url").getString();
                data = imageUrl.get("data").getString();
                if (data == null) {
                    data = imageUrl.get("b64_json").getString();
                }
            }
        }
        if (url == null && contentItem.hasKey("url")) {
            url = contentItem.get("url").getString();
        }
        if (data == null && contentItem.hasKey("data")) {
            data = contentItem.get("data").getString();
        }
        if (data == null && contentItem.hasKey("result")) {
            data = contentItem.get("result").getString();
        }

        if (Utils.isNotEmpty(data)) {
            if (data.startsWith("data:") && data.contains(";base64,")) {
                int comma = data.indexOf(',');
                String header = data.substring(5, data.indexOf(';'));
                String b64 = data.substring(comma + 1);
                return ImageBlock.ofBase64(b64, header);
            }
            return ImageBlock.ofBase64(data);
        }
        if (Utils.isNotEmpty(url)) {
            return ImageBlock.ofUrl(url);
        }
        return null;
    }

    /**
     * 给 thinking 消息挂上 reasoning 元数据，并记录已交付状态。
     */
    private void attachReasoningMetadata(AssistantMessage thinkingMsg, StreamState state) {
        if (state == null) {
            return;
        }
        if (Utils.isNotEmpty(state.currentReasoningId)) {
            thinkingMsg.getMetadata().put("reasoning_item_id", state.currentReasoningId);
            state.emittedReasoningId = state.currentReasoningId;
        }
        if (Utils.isNotEmpty(state.currentReasoningEncryptedContent)) {
            thinkingMsg.getMetadata().put("reasoning_encrypted_content", state.currentReasoningEncryptedContent);
            state.emittedReasoningEncryptedContent = state.currentReasoningEncryptedContent;
        }
    }

    /**
     * reasoning 元数据是否尚未随任何消息交付给会话。
     */
    private boolean isReasoningMetadataPending(StreamState state) {
        if (Utils.isNotEmpty(state.currentReasoningId)
                && !state.currentReasoningId.equals(state.emittedReasoningId)) {
            return true;
        }

        return Utils.isNotEmpty(state.currentReasoningEncryptedContent)
                && !state.currentReasoningEncryptedContent.equals(state.emittedReasoningEncryptedContent);
    }

    /**
     * 流式 function_call 落地：构建 ToolCall 内容项 并重置状态（供 arguments.done 与 output_item.done 兜底共用）。
     *
     * @return 是否添加了内容项
     */
    private boolean flushFunctionCall(ChatAccumulator acc, StreamState state, String arguments) {
        if (Utils.isEmpty(arguments) && state.currentFunctionArguments != null) {
            arguments = state.currentFunctionArguments.toString();
        }
        // 流式解析出口净化：截断损坏的 arguments 禁止入历史（会毒化会话）
        arguments = ToolCallJsonSanitizer.sanitizeArguments(arguments, state.currentFunctionName);
        try {
            Map<String, Object> argMap = new HashMap<>();
            if (Utils.isNotEmpty(arguments)) {
                ONode argsNode = ONode.ofJson(arguments);
                if (argsNode.isObject()) {
                    argMap = argsNode.toBean(Map.class);
                }
            }
            // 已经由 arguments.done 或 output_item.done 落地的调用不能重复添加。
            if (state.currentFunctionCallId != null && !state.emittedFunctionCalls.add(state.currentFunctionCallId)) {
                return false;
            }
            ToolCall toolCall = new ToolCall(state.currentFunctionCallId, state.currentFunctionCallId,
                    state.currentFunctionName, arguments, argMap);
            List<Map> toolCallsRaw = new ArrayList<>();
            Map<String, Object> toolCallRaw = new HashMap<>();
            toolCallRaw.put("id", state.currentFunctionCallId);
            toolCallRaw.put("type", "function");
            Map<String, Object> functionData = new HashMap<>();
            functionData.put("name", state.currentFunctionName);
            functionData.put("arguments", arguments);
            toolCallRaw.put("function", functionData);
            toolCallsRaw.add(toolCallRaw);
            List<ToolCall> toolCalls = new ArrayList<>();
            toolCalls.add(toolCall);
            AssistantMessage assistantMessage = new AssistantMessage("", "",
                    false, null,
                    toolCallsRaw, toolCalls, null);
            acc.addContentItem(assistantMessage);
            return true;
        } catch (Exception e) {
            log.warn("Failed to parse function call in stream mode", e);
            return false;
        } finally {
            // 重置函数调用状态
            state.currentFunctionCallId = null;
            state.currentFunctionName = null;
            state.currentFunctionArguments = null;
        }
    }

    /**
     * 从 response.completed/incomplete 携带的最终 output 补齐没有收到分片的内容。
     * <p>该路径必须幂等：正常流式分片已经交付的 item 不再重复追加。</p>
     */
    private boolean appendFinalOutput(ChatStreamContext ctx, ChatAccumulator acc, ONode output, StreamState state) {
        if (output == null || !output.isArray()) {
            return false;
        }
        boolean added = false;
        Set<String> finalOutputKeys = acc.attrIfAbsent(FINAL_OUTPUT_KEYS_KEY, k -> new HashSet<String>());
        for (int outputPosition = 0; outputPosition < output.size(); outputPosition++) {
            ONode item = output.get(outputPosition);
            if (item == null || !item.isObject()) {
                continue;
            }
            String itemKey = outputItemKey(item, null, outputPosition);
            if (!finalOutputKeys.add(itemKey)) {
                continue;
            }
            String itemType = item.get("type").getString();
            if ("message".equals(itemType)) {
                String text = null;
                List<ContentBlock> media = new ArrayList<>();
                Set<String> itemTextDeltaItems = textDeltaItems(state, itemKey);
                ONode content = item.getOrNull("content");
                if (content != null && content.isArray()) {
                    StringBuilder textBuf = new StringBuilder();
                    for (int partIndex = 0; partIndex < content.size(); partIndex++) {
                        ONode part = content.get(partIndex);
                        String type = part.get("type").getString();
                        if ("output_text".equals(type) || "text".equals(type)) {
                            String value = part.get("text").getString();
                            String partKey = itemKey + ":content_index:" + partIndex;
                            String delivered = deliveredText(state, itemKey, partKey);
                            String missing = missingSuffix(value, delivered);
                            if (Utils.isNotEmpty(missing)) {
                                textBuf.append(missing);
                            }
                            emitAnnotations(ctx, part);
                        } else if ("refusal".equals(type)) {
                            String value = firstNonEmpty(part, "refusal", "text");
                            String partKey = itemKey + ":content_index:" + partIndex;
                            String missing = missingSuffix(value, deliveredText(state, itemKey, partKey));
                            if (Utils.isNotEmpty(missing)) textBuf.append(missing);
                        } else if ("output_image".equals(type) || "image".equals(type) || part.hasKey("image_url")) {
                            ContentBlock block = parseMessageImageContent(part);
                            if (block != null) media.add(block);
                        }
                    }
                    text = textBuf.toString();
                }
                if (Utils.isNotEmpty(text) || !media.isEmpty()) {
                    if (Utils.isNotEmpty(text)) {
                        acc.appendText(text);
                    }
                    AssistantMessage msg = new AssistantMessage(text == null ? "" : text, "", false,
                            null, null, null, null, media.isEmpty() ? null : media);
                    if (Utils.isNotEmpty(item.get("phase").getString())) {
                        msg.getMetadata().put("phase", item.get("phase").getString());
                    }
                    acc.addContentItem(msg);
                    added = true;
                }
                if (!media.isEmpty()) {
                    acc.addMediaBlocks(media);
                    added = true;
                }
            } else if ("reasoning".equals(itemType)) {
                StringBuilder thinking = new StringBuilder();
                ONode content = item.getOrNull("content");
                if (content != null && content.isArray()) {
                    for (int partIndex = 0; partIndex < content.size(); partIndex++) {
                        ONode part = content.get(partIndex);
                        String value = part.get("text").getString();
                        String partKey = itemKey + ":content_index:" + partIndex;
                        String missing = missingSuffix(value, deliveredReasoning(state, itemKey, partKey));
                        if (Utils.isNotEmpty(missing)) thinking.append(missing);
                    }
                }
                ONode summary = item.getOrNull("summary");
                if (summary != null && summary.isArray()) {
                    for (int summaryIndex = 0; summaryIndex < summary.size(); summaryIndex++) {
                        ONode part = summary.get(summaryIndex);
                        String value = part.get("text").getString();
                        String partKey = itemKey + ":summary_index:" + summaryIndex;
                        String missing = missingSuffix(value, deliveredReasoning(state, itemKey, partKey));
                        if (Utils.isNotEmpty(missing)) thinking.append(missing);
                    }
                }
                String id = item.get("id").getString();
                String encrypted = item.get("encrypted_content").getString();
                String phase = item.get("phase").getString();
                AssistantMessage msg = new AssistantMessage("", thinking.toString(), true);
                if (Utils.isNotEmpty(id)) msg.getMetadata().put("reasoning_item_id", id);
                if (Utils.isNotEmpty(encrypted)) msg.getMetadata().put("reasoning_encrypted_content", encrypted);
                if (Utils.isNotEmpty(phase)) msg.getMetadata().put("phase", phase);
                if (Utils.isNotEmpty(thinking.toString()) || Utils.isNotEmpty(id) || Utils.isNotEmpty(encrypted)) {
                    acc.appendThinking(thinking.toString());
                    acc.addContentItem(msg);
                    added = true;
                }
            } else if ("function_call".equals(itemType)) {
                String callId = item.get("call_id").getString();
                if (Utils.isEmpty(callId)) callId = item.get("id").getString();
                if (Utils.isNotEmpty(callId) && !functionAlreadyEmitted(state, itemKey, callId)) {
                    activateItemState(state, item.get("id").getString(), -1);
                    state.currentFunctionCallId = callId;
                    state.currentFunctionName = item.get("name").getString();
                    state.currentFunctionArguments = new StringBuilder();
                    added |= flushFunctionCall(acc, state, item.get("arguments").getString());
                }
            } else if ("image_generation_call".equals(itemType)
                    && !mediaAlreadyEmitted(state, itemKey)) {
                ContentBlock image = parseImageGenerationCall(item);
                if (image != null) {
                    acc.addMediaBlocks(Collections.singletonList(image));
                    added = true;
                }
            }
        }
        return added;
    }

    private Set<String> textDeltaItems(StreamState state, String itemKey) {
        if (itemKey.equals(state.activeStateKey)) return state.textDeltaItems;
        ItemSnapshot saved = state.itemStates.get(itemKey);
        return saved == null ? Collections.<String>emptySet() : saved.textDeltaItems;
    }

    private Set<String> reasoningDeltaItems(StreamState state, String itemKey) {
        if (itemKey.equals(state.activeStateKey)) return state.reasoningDeltaItems;
        ItemSnapshot saved = state.itemStates.get(itemKey);
        return saved == null ? Collections.<String>emptySet() : saved.reasoningDeltaItems;
    }

    private boolean functionAlreadyEmitted(StreamState state, String itemKey, String callId) {
        if (itemKey.equals(state.activeStateKey)) return state.emittedFunctionCalls.contains(callId);
        ItemSnapshot saved = state.itemStates.get(itemKey);
        return saved != null && saved.emittedFunctionCalls.contains(callId);
    }

    private boolean mediaAlreadyEmitted(StreamState state, String itemKey) {
        if (itemKey.equals(state.activeStateKey)) return state.emittedMediaItems.contains(itemKey);
        ItemSnapshot saved = state.itemStates.get(itemKey);
        return saved != null && saved.emittedMediaItems.contains(itemKey);
    }

    private boolean hasItemPart(Set<String> values, String itemKey, int partIndex) {
        String explicit = itemKey + ":content_index:" + partIndex;
        String defaultPart = itemKey + ":content_index:-1";
        return values.contains(explicit) || values.contains(defaultPart);
    }


    private boolean hasItemPrefix(Set<String> values, String prefix) {
        for (String value : values) {
            if (value.startsWith(prefix)) return true;
        }
        return false;
    }


    /**
     * 解析 usage 信息
     *
     * @author oisin lu
     * @date 2026年1月28日
     */
    private AiUsage parseUsage(ONode usageNode) {
        if (usageNode == null) {
            return null;
        }
        // Responses API 使用 input_tokens 和 output_tokens
        long inputTokens = usageNode.hasKey("input_tokens") ? usageNode.get("input_tokens").getLong() : 0L;
        long outputTokens = usageNode.hasKey("output_tokens") ? usageNode.get("output_tokens").getLong() : 0L;
        long totalTokens = usageNode.hasKey("total_tokens") ? usageNode.get("total_tokens").getLong() : (inputTokens + outputTokens);

        // 读取缓存 token 统计（官方 ResponseUsage.InputTokensDetails：cached_tokens / cache_write_tokens）
        long cacheReadInputTokens = 0L;
        long cacheCreationInputTokens = 0L;
        ONode inputTokensDetails = usageNode.getOrNull("input_tokens_details");
        if (inputTokensDetails != null) {
            if (inputTokensDetails.hasKey("cached_tokens")) {
                cacheReadInputTokens = inputTokensDetails.get("cached_tokens").getLong();
            }
            if (inputTokensDetails.hasKey("cache_write_tokens")) {
                cacheCreationInputTokens = inputTokensDetails.get("cache_write_tokens").getLong();
            }
        }

        // 读取思考 token 统计（output_tokens_details.reasoning_tokens）
        long thinkTokens = 0L;
        ONode outputTokensDetails = usageNode.getOrNull("output_tokens_details");
        if (outputTokensDetails != null && outputTokensDetails.hasKey("reasoning_tokens")) {
            thinkTokens = outputTokensDetails.get("reasoning_tokens").getLong();
        } else if (usageNode.hasKey("reasoning_tokens")) {
            thinkTokens = usageNode.get("reasoning_tokens").getLong();
        }

        // 兼容部分 Responses 网关沿用 Chat Completions 的顶层缓存命中字段。
        if ((inputTokensDetails == null || !inputTokensDetails.hasKey("cached_tokens"))
                && usageNode.hasKey("prompt_cache_hit_tokens")) {
            cacheReadInputTokens = usageNode.get("prompt_cache_hit_tokens").getLong();
        }

        // usage 节点存在且结构合法时，即使全部为 0 也必须保留；0 token 不是“usage 不存在”。
        if (usageNode.isObject()) {
            return new AiUsage(inputTokens, thinkTokens, outputTokens, totalTokens,
                    cacheCreationInputTokens, cacheReadInputTokens, usageNode);
        }

        return null;
    }
}