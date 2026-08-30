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
import org.noear.solon.ai.chat.ChatChoice;
import org.noear.solon.ai.chat.ChatException;
import org.noear.solon.ai.chat.ChatResponseDefault;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.content.TextBlock;
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
        SnapshotDeltaNormalizer currentTextContent;
        SnapshotDeltaNormalizer currentReasoningContent;
        String currentFunctionCallId;
        String currentFunctionName;
        StringBuilder currentFunctionArguments;
        // 官方 OpenAI：reasoning 项的 id/encrypted_content，用于多轮回放
        String currentReasoningId;
        String currentReasoningEncryptedContent;
        // 已随消息交付给会话的 reasoning 元数据（用于判断 done 帧是否仍需补发）
        String emittedReasoningId;
        String emittedReasoningEncryptedContent;
    }

    private static final String STREAM_STATE_KEY = "StreamState";

    public OpenaiResponsesResponseParser() {
    }

    /**
     * 解析响应 JSON
     *
     * @param resp 聊天响应对象
     * @param json 响应 JSON 字符串
     * @return 是否有有效的选择
     * @author oisin lu
     * @date 2026年1月28日
     */
    public boolean parseResponse(ChatResponseDefault resp, String json) {
        if (resp.isStream()) {
            return parseStreamResponse(resp, json);
        } else {
            return parseNonStreamResponse(resp, json);
        }
    }

    /**
     * 获取或创建流式状态
     */
    private StreamState getOrCreateState(ChatResponseDefault resp) {
        return resp.attrIfAbsent(STREAM_STATE_KEY, k -> new StreamState());
    }

    /**
     * 解析流式响应
     *
     * @author oisin lu
     * @date 2026年1月28日
     */
    public boolean parseStreamResponse(ChatResponseDefault resp, String json) {
        if (json == null || json.isEmpty()) {
            return false;
        }
        if (log.isDebugEnabled()) {
            log.debug("OpenAI Responses stream raw response: {}", json);
        }
        String[] lines = json.split("\n");
        boolean hasChoices = false;
        boolean hasMedia = false;
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
                    resp.attrRemove(STREAM_STATE_KEY);
                    if (resp.isFinished() == false) {
                        resp.addChoice(new ChatChoice(0, new Date(), resp.getLastFinishReasonNormalized(), new AssistantMessage("")));
                        resp.setFinished(true);
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
                resp.attrRemove(STREAM_STATE_KEY);
                resp.setError(new ChatException(
                        OpenaiDialectSupport.extractErrorMessage(oResp.get("error"))));
                return true;
            }

            String eventType = oResp.get("type").getString();
            if ("error".equals(eventType)) {
                resp.attrRemove(STREAM_STATE_KEY);
                // error 事件可能把 message/type/code 平链在帧顶层（无 error 子对象）
                ONode oError = oResp.hasKey("error") ? oResp.get("error") : oResp;
                resp.setError(new ChatException(OpenaiDialectSupport.extractErrorMessage(oError)));
                return true;
            } else if ("response.created".equals(eventType) || "response.in_progress".equals(eventType)
                    || "response.queued".equals(eventType)) {
                // 响应创建/进行中/排队，可以设置模型信息
                ONode response = oResp.get("response");
                if (response != null) {
                    resp.setModel(response.get("model").getString());
                }
            } else if ("response.output_item.added".equals(eventType)) {
                // 新输出项添加
                ONode item = oResp.get("item");
                if (item != null) {
                    StreamState state = getOrCreateState(resp);
                    state.currentItemId = item.get("id").getString();
                    state.currentItemType = item.get("type").getString();

                    if ("message".equals(state.currentItemType)) {
                        state.currentTextContent = new SnapshotDeltaNormalizer();
                    } else if ("reasoning".equals(state.currentItemType)) {
                        state.currentReasoningContent = new SnapshotDeltaNormalizer();
                        // 官方 OpenAI：捕获服务端返回的 reasoning 项 id/encrypted_content（多轮回放需要）。
                        // added 帧的 encrypted_content 通常为空（仅 done 帧携带），交付状态在此一并重置
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
                // 输出项完成：image_generation_call 仅收入 media 聚合，不再推空文本 choice（避免流式侧多一条空消息）
                ONode item = oResp.get("item");
                if (item != null && "image_generation_call".equals(item.get("type").getString())) {
                    ContentBlock imageBlock = parseImageGenerationCall(item);
                    if (imageBlock != null) {
                        resp.addMediaBlocks(Collections.singletonList(imageBlock));
                        hasMedia = true;
                        // 不 addChoice：等待 response.completed / 文本 delta；media 由 getAggregationMessage 合并
                    }
                }
                StreamState state = resp.attrAs(STREAM_STATE_KEY);
                if (state != null) {
                    // 官方 OpenAI：reasoning 的 encrypted_content 只在 output_item.done 携带（added 帧通常为空）。
                    // 这里补捕获，并在元数据尚未随任何消息交付时补一条空 thinking 消息，供多轮回放使用。
                    if (item != null && "reasoning".equals(item.get("type").getString())) {
                        String doneId = item.get("id").getString();
                        String doneEncrypted = item.get("encrypted_content").getString();
                        if (Utils.isNotEmpty(doneId)) {
                            state.currentReasoningId = doneId;
                        }
                        if (Utils.isNotEmpty(doneEncrypted)) {
                            state.currentReasoningEncryptedContent = doneEncrypted;
                        }

                        // 判据是「是否已交付」而非「与 added 帧是否不同」：
                        // store=true 且未请求 reasoning.summary 时整个 reasoning 项没有任何 delta 帧，
                        // 而 done 帧的 id 与 added 帧相同，按差异判定会认为无新增 → reasoning_item_id 丢失、多轮回放断链
                        if (isReasoningMetadataPending(state)) {
                            AssistantMessage metaMsg = new AssistantMessage("", "", true);
                            attachReasoningMetadata(metaMsg, state);
                            resp.addChoice(new ChatChoice(0, new Date(), null, metaMsg));
                            hasChoices = true;
                        }
                    }
                    // 兼容只发 output_item.done（携带完整 item）而不发 function_call_arguments.done 的实现：
                    // 兜底 flush，避免工具调用静默丢失
                    if (item != null && "function_call".equals(item.get("type").getString())
                            && state.currentFunctionCallId != null && state.currentFunctionName != null) {
                        hasChoices |= flushFunctionCall(resp, state, item.get("arguments").getString());
                    }
                    state.currentItemId = null;
                    state.currentItemType = null;
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
                        StreamState state = getOrCreateState(resp);
                        state.currentTextContent = new SnapshotDeltaNormalizer();
                    } else if ("reasoning_text".equals(partType)) {
                        StreamState state = getOrCreateState(resp);
                        state.currentReasoningContent = new SnapshotDeltaNormalizer();
                    }
                }
            } else if ("response.reasoning_summary_part.added".equals(eventType)) {
                // 官方 OpenAI o 系列思维链摘要：part 类型为 reasoning_summary_text
                // （SDK ResponseStreamEvent.kt: response.reasoning_summary_part.added）
                StreamState state = getOrCreateState(resp);
                if (state.currentReasoningContent == null) {
                    state.currentReasoningContent = new SnapshotDeltaNormalizer();
                }
            } else if ("response.reasoning_summary_text.delta".equals(eventType)) {
                // 官方 OpenAI o 系列思维链摘要增量
                // （SDK ResponseStreamEvent.kt: response.reasoning_summary_text.delta）
                // 该事件仅官方实现下发，不存在累计快照形态，故不做快照归一（避免无收益的误判风险）
                String delta = oResp.get("delta").getString();
                if (Utils.isNotEmpty(delta)) {
                    StreamState state = getOrCreateState(resp);
                    if (state.currentReasoningContent == null) {
                        state.currentReasoningContent = new SnapshotDeltaNormalizer();
                    }
                    state.currentReasoningContent.append(delta);
                    AssistantMessage thinkingMsg = new AssistantMessage("", delta, true);
                    attachReasoningMetadata(thinkingMsg, state);
                    resp.addChoice(new ChatChoice(0, new Date(), null, thinkingMsg));
                    hasChoices = true;
                }
            } else if ("response.reasoning_summary_text.done".equals(eventType)
                    || "response.reasoning_summary_part.done".equals(eventType)) {
                // 思维链摘要完成：增量已通过 delta 事件推送，无需额外处理
            } else if ("response.refusal.delta".equals(eventType)) {
                // 官方拒答流式增量（SDK ResponseStreamEvent.kt: response.refusal.delta）：
                // 按普通文本输出，避免拒答内容丢失。同为官方独有事件，不做快照归一
                String delta = oResp.get("delta").getString();
                if (Utils.isNotEmpty(delta)) {
                    StreamState state = getOrCreateState(resp);
                    if (state.currentTextContent == null) {
                        state.currentTextContent = new SnapshotDeltaNormalizer();
                    }
                    state.currentTextContent.append(delta);
                    resp.addChoice(new ChatChoice(0, new Date(), null, new AssistantMessage(delta)));
                    hasChoices = true;
                }
            } else if ("response.reasoning_text.delta".equals(eventType)) {
                // 思考增量（DeepSeek Responses：思维链回传为 thinking 消息）
                String delta = oResp.get("delta").getString();
                if (Utils.isNotEmpty(delta)) {
                    StreamState state = resp.attrAs(STREAM_STATE_KEY);
                    if (state != null && state.currentReasoningContent != null) {
                        delta = state.currentReasoningContent.normalize(delta);
                        if (Utils.isEmpty(delta)) {
                            continue;
                        }
                        AssistantMessage thinkingMsg = new AssistantMessage("", delta, true);
                        attachReasoningMetadata(thinkingMsg, state);
                        resp.addChoice(new ChatChoice(0, new Date(), null, thinkingMsg));
                        hasChoices = true;
                    } else {
                        // 未处于 reasoning item 上下文（缺 output_item.added / content_part.added 前置事件）：
                        // 拒绝输出为 thinking 消息，防止非思考内容被误标
                        log.warn("OpenAI Responses stream: ignored reasoning_text.delta without reasoning context: {}", delta);
                    }
                }
            } else if ("response.reasoning_text.done".equals(eventType)) {
                // 思考完成：增量已通过 delta 事件推送，此处无需额外处理
            } else if ("response.output_text.delta".equals(eventType)) {
                // 文本增量
                String delta = oResp.get("delta").getString();
                if (Utils.isNotEmpty(delta)) {
                    StreamState state = resp.attrAs(STREAM_STATE_KEY);
                    if (state != null && state.currentTextContent != null) {
                        delta = state.currentTextContent.normalize(delta);
                        if (Utils.isEmpty(delta)) {
                            continue;
                        }
                    }
                    resp.addChoice(new ChatChoice(0, new Date(), null, new AssistantMessage(delta)));
                    hasChoices = true;
                }
            } else if ("response.content_part.delta".equals(eventType)) {
                // 内容部分增量（通用，按 part 类型分流：output_text 普通文本 / reasoning_text 思考）
                ONode delta = oResp.get("delta");
                if (delta != null) {
                    String text = delta.get("text").getString();
                    if (Utils.isNotEmpty(text)) {
                        StreamState state = resp.attrAs(STREAM_STATE_KEY);
                        if ("reasoning_text".equals(delta.get("type").getString())) {
                            // 思考增量经 content_part.delta 到达：按 thinking 消息处理，防止被当作普通文本输出
                            if (state != null && state.currentReasoningContent != null) {
                                text = state.currentReasoningContent.normalize(text);
                                if (Utils.isEmpty(text)) {
                                    continue;
                                }
                                AssistantMessage thinkingMsg = new AssistantMessage("", text, true);
                                attachReasoningMetadata(thinkingMsg, state);
                                resp.addChoice(new ChatChoice(0, new Date(), null, thinkingMsg));
                                hasChoices = true;
                            } else {
                                log.warn("OpenAI Responses stream: ignored content_part.delta(reasoning_text) without reasoning context: {}", text);
                            }
                        } else {
                            if (state != null && state.currentTextContent != null) {
                                text = state.currentTextContent.normalize(text);
                                if (Utils.isEmpty(text)) {
                                    continue;
                                }
                            }
                            resp.addChoice(new ChatChoice(0, new Date(), null, new AssistantMessage(text)));
                            hasChoices = true;
                        }
                    }
                }
            } else if ("response.function_call_arguments.delta".equals(eventType)) {
                // 函数调用参数增量
                String delta = oResp.get("delta").getString();
                if (Utils.isNotEmpty(delta)) {
                    StreamState state = resp.attrAs(STREAM_STATE_KEY);
                    if (state != null && state.currentFunctionArguments != null) {
                        state.currentFunctionArguments.append(delta);
                    }
                }
            } else if ("response.function_call_arguments.done".equals(eventType)) {
                // 函数调用参数完成
                StreamState state = resp.attrAs(STREAM_STATE_KEY);
                if (state != null && state.currentFunctionCallId != null && state.currentFunctionName != null) {
                    hasChoices |= flushFunctionCall(resp, state, oResp.get("arguments").getString());
                }
            } else if ("response.output_text.done".equals(eventType) || "response.content_part.done".equals(eventType)) {
                // 文本/内容部分完成
                // 不需要特殊处理
            } else if ("response.completed".equals(eventType)) {
                resp.attrRemove(STREAM_STATE_KEY);
                ONode response = oResp.get("response");
                if (response != null) {
                    resp.setModel(response.get("model").getString());
                    // 解析 usage
                    AiUsage usage = parseUsage(response.getOrNull("usage"));
                    if (usage != null) {
                        resp.setUsage(usage);
                    }
                }

                // 添加结束标记 choice，让框架能够将 isFinished=true 进行传递
                if (resp.hasChoices() == false) {
                    resp.addChoice(new ChatChoice(0, new Date(), resp.getLastFinishReasonNormalized(), new AssistantMessage("")));
                }

                resp.setFinished(true);
                hasChoices = true;
            } else if ("response.incomplete".equals(eventType)) {
                // 响应未完成（如 max_output_tokens 截断等）：同样结束流，避免悬挂
                resp.attrRemove(STREAM_STATE_KEY);
                ONode response = oResp.get("response");
                if (response != null) {
                    resp.setModel(response.get("model").getString());
                    AiUsage usage = parseUsage(response.getOrNull("usage"));
                    if (usage != null) {
                        resp.setUsage(usage);
                    }
                    // 回填 finishReason：incomplete_details.reason（如 max_output_tokens → length），便于上层区分截断
                    ONode incompleteDetails = response.getOrNull("incomplete_details");
                    if (incompleteDetails != null) {
                        String reason = incompleteDetails.get("reason").getString();
                        if (Utils.isNotEmpty(reason)) {
                            resp.lastFinishReason = "max_output_tokens".equals(reason) ? "length" : reason;
                        }
                    }
                }

                if (resp.hasChoices() == false) {
                    resp.addChoice(new ChatChoice(0, new Date(), resp.getLastFinishReasonNormalized(), new AssistantMessage("")));
                }

                resp.setFinished(true);
                hasChoices = true;
            } else if ("response.failed".equals(eventType)) {
                // 响应失败，清理状态
                resp.attrRemove(STREAM_STATE_KEY);
                ONode response = oResp.get("response");
                if (response != null) {
                    resp.setModel(response.get("model").getString());
                    AiUsage usage = parseUsage(response.getOrNull("usage"));
                    if (usage != null) {
                        resp.setUsage(usage);
                    }
                    ONode error = response.get("error");
                    // 与非流式 error 提取保持一致：格式化为 [type/code] message
                    resp.setError(new ChatException(
                            OpenaiDialectSupport.extractErrorMessage(error != null ? error : response)));
                } else {
                    resp.setError(new ChatException("Response failed"));
                }
                resp.setFinished(true);
                // 失败也视为有效处理帧，防止错误被当作解析失败吞掉
                hasChoices = true;
            }
        }
        // 有效处理过 choice 或 media 均视为解析成功（纯 media 事件无 choice 时也要 true）
        return hasChoices || hasMedia;
    }

    /**
     * 解析非流式响应
     *
     * @author oisin lu
     * @date 2026年1月28日
     */
    public boolean parseNonStreamResponse(ChatResponseDefault resp, String json) {
        if ("[DONE]".equals(json)) {
            if (resp.isFinished() == false) {
                resp.addChoice(new ChatChoice(0, new Date(), resp.getLastFinishReasonNormalized(), new AssistantMessage("")));
                resp.setFinished(true);
            }
            return true;
        }
        ONode oResp = ONode.ofJson(json);
        if (!oResp.isObject()) {
            return false;
        }
        // 检查错误（规范提取：error 为对象时不能整体 getString）
        if (oResp.hasKey("error") && !oResp.get("error").isNull()) {
            resp.setError(new ChatException(
                    OpenaiDialectSupport.extractErrorMessage(oResp.get("error"))));
            return true;
        }
        // 检查状态
        String status = oResp.get("status").getString();
        if ("failed".equals(status)) {
            ONode error = oResp.getOrNull("error");
            resp.setError(new ChatException(OpenaiDialectSupport.extractErrorMessage(
                    error != null ? error : oResp)));
            return true;
        }
        if ("cancelled".equals(status)) {
            resp.lastFinishReason = "cancelled";
        } else if ("incomplete".equals(status)) {
            // 输出被截断（如 max_output_tokens）：回填 finishReason，与流式 response.incomplete 对齐
            ONode incompleteDetails = oResp.getOrNull("incomplete_details");
            String reason = incompleteDetails == null ? null : incompleteDetails.get("reason").getString();
            if (Utils.isNotEmpty(reason)) {
                resp.lastFinishReason = "max_output_tokens".equals(reason) ? "length" : reason;
            } else {
                resp.lastFinishReason = "length";
            }
        }
        // 设置模型信息
        resp.setModel(oResp.get("model").getString());
        Date created = new Date();
        if (oResp.hasKey("created_at")) {
            try {
                long createdAt = oResp.get("created_at").getLong();
                if (createdAt > 0) {
                    created = new Date(createdAt * 1000);
                }
            } catch (Exception ignored) {
            }
        }
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
                    ONode contentArray = outputItem.getOrNull("content");
                    if (contentArray != null && contentArray.isArray()) {
                        for (ONode contentItem : contentArray.getArray()) {
                            String contentType = contentItem.get("type").getString();
                            if ("output_text".equals(contentType) || "text".equals(contentType)) {
                                String text = contentItem.get("text").getString();
                                if (Utils.isNotEmpty(text)) {
//                                    if (textContent.length() > 0) {
//                                        textContent.append("\n");
//                                    }
                                    textContent.append(text);
                                }
                            } else if ("refusal".equals(contentType)) {
                                String refusal = contentItem.get("refusal").getString();
                                if (Utils.isEmpty(refusal)) {
                                    refusal = contentItem.get("text").getString();
                                }
                                if (Utils.isNotEmpty(refusal)) {
//                                    if (textContent.length() > 0) {
//                                        textContent.append("\n");
//                                    }
                                    textContent.append(refusal);
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
                }
            }

            List<ContentBlock> blocksForMsg = null;
            if (!mediaBlocks.isEmpty()) {
                blocksForMsg = new ArrayList<>();
                if (textContent.length() > 0) {
                    blocksForMsg.add(TextBlock.of(textContent.toString()));
                }
                blocksForMsg.addAll(mediaBlocks);
                resp.addMediaBlocks(mediaBlocks);
            }

            // 非流式：与 AbstractChatDialect 对齐——思考与正文合并为单条消息（AssistantMessage 已分离 text/thinking），
            // 不再拆成两个 choice，以保证 getText()/getThinking() 在同一条最终消息上都可用
            String thinkingOut = reasoningContent.toString();
            String textOut = textContent.toString();
            String finishReason = allToolCalls.isEmpty()
                    ? (Utils.isEmpty(resp.lastFinishReason) ? "stop" : resp.lastFinishReason)
                    : "tool_calls";

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

                resp.addChoice(new ChatChoice(0, created, finishReason, msg));
            }
        } else {
            // 如果没有 output 数组，尝试使用便捷字段 output_text / reasoning_text（DeepSeek 顶层字段）
            String reasoningText = oResp.get("reasoning_text").getString();
            String outputText = oResp.get("output_text").getString();
            if (Utils.isNotEmpty(reasoningText) || Utils.isNotEmpty(outputText)) {
                String finishReason = Utils.isEmpty(resp.lastFinishReason) ? "stop" : resp.lastFinishReason;
                resp.addChoice(new ChatChoice(0, created, finishReason, new AssistantMessage(
                        outputText == null ? "" : outputText,
                        reasoningText == null ? "" : reasoningText,
                        false)));
            }
        }
        // 解析用量信息
        AiUsage usage = parseUsage(oResp.getOrNull("usage"));
        if (usage != null) {
            resp.setUsage(usage);
        }

        // 与 OpenaiChatDialect 对齐：output 全是未识别项（web_search_call / mcp_call 等）且无 output_text 时，
        // 也要补一条空消息 choice，避免上层 getMessage() 拿到 null
        if (resp.hasChoices() == false) {
            resp.addChoice(new ChatChoice(0, created,
                    Utils.isEmpty(resp.lastFinishReason) ? "stop" : resp.lastFinishReason,
                    new AssistantMessage("")));
        }

        resp.setFinished(true);
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
     * 流式 function_call 落地：构建 ToolCall choice 并重置状态（供 arguments.done 与 output_item.done 兜底共用）。
     *
     * @return 是否添加了 choice
     */
    private boolean flushFunctionCall(ChatResponseDefault resp, StreamState state, String arguments) {
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
            resp.addChoice(new ChatChoice(0, new Date(), null, assistantMessage));
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
            cacheReadInputTokens = inputTokensDetails.get("cached_tokens").getLong();
            cacheCreationInputTokens = inputTokensDetails.get("cache_write_tokens").getLong();
        }

        // 读取思考 token 统计（output_tokens_details.reasoning_tokens）
        long thinkTokens = 0L;
        ONode outputTokensDetails = usageNode.getOrNull("output_tokens_details");
        if (outputTokensDetails != null) {
            thinkTokens = outputTokensDetails.get("reasoning_tokens").getLong();
        }

        if (inputTokens > 0 || outputTokens > 0 || cacheReadInputTokens > 0
                || cacheCreationInputTokens > 0 || thinkTokens > 0) {
            return new AiUsage(inputTokens, thinkTokens, outputTokens, totalTokens,
                    cacheCreationInputTokens, cacheReadInputTokens, usageNode);
        }

        return null;
    }
}