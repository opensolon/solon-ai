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
package org.noear.solon.ai.llm.dialect.gemini.interactions;

import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.chat.ChatChoice;
import org.noear.solon.ai.chat.ChatException;
import org.noear.solon.ai.chat.ChatResponseDefault;
import org.noear.solon.ai.chat.content.AudioBlock;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.content.VideoBlock;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Gemini Interactions API 响应解析器
 * <p>
 * 负责解析 Interactions API 返回的流式和非流式响应。
 * Interactions API 使用 steps[] 数组替代 Generate Content API 的 candidates[]。
 * 流式模式使用 SSE 事件序列（step.start / step.delta / step.stop）。
 *
 * @since 3.1
 */
public class GeminiInteractionsResponseParser {
    private static final Logger log = LoggerFactory.getLogger(GeminiInteractionsResponseParser.class);
    private final boolean logEnabled;

    // 流式步骤累积器（按 step index 分组）
    private final Map<Integer, StepAccumulator> stepAccumulators = new LinkedHashMap<>();

    public GeminiInteractionsResponseParser() {
        this.logEnabled = log.isDebugEnabled();
    }

    /**
     * 解析响应 JSON
     *
     * @param resp 聊天响应对象
     * @param json 响应 JSON 字符串
     * @return 是否有有效的选择
     */
    public boolean parseResponse(ChatResponseDefault resp, String json) {
        if (json == null || json.isEmpty()) {
            return false;
        }

        if (logEnabled) {
            log.debug("Interactions raw response: {}", json);
        }

        if (resp.isStream()) {
            return parseStreamResponse(resp, json);
        } else {
            return parseNonStreamResponse(resp, json);
        }
    }

    // ==================== 非流式解析 ====================

    /**
     * 解析非流式响应
     * <p>
     * Interactions API 非流式响应格式：
     * <pre>{@code
     * {
     *   "id": "v1_...",
     *   "model": "gemini-3.5-flash",
     *   "status": "completed",
     *   "steps": [
     *     {"type": "thought", "summary": [...], "signature": "..."},
     *     {"type": "model_output", "content": [{"type":"text","text":"Answer"}]},
     *     {"type": "function_call", "name":"...", "arguments":{...}, "id":"..."}
     *   ],
     *   "usage": {"total_input_tokens":7, "total_output_tokens":20, "total_tokens":49}
     * }
     * }</pre>
     */
    public boolean parseNonStreamResponse(ChatResponseDefault resp, String json) {
        ONode oResp;
        try {
            oResp = ONode.ofJson(json);
        } catch (Exception e) {
            log.warn("Failed to parse Interactions response JSON", e);
            return false;
        }

        if (!oResp.isObject()) {
            return false;
        }

        // 错误处理
        if (oResp.hasKey("error")) {
            ONode oError = oResp.get("error");
            String errorMsg = oError.get("message").getString();
            if (Utils.isEmpty(errorMsg)) {
                errorMsg = oError.toJson();
            }
            resp.setError(new ChatException(errorMsg));
            return true;
        }

        // model
        if (oResp.hasKey("model")) {
            resp.setModel(oResp.get("model").getString());
        }

        // status → finishReason
        String status = oResp.get("status").getString();
        String finishReason = mapStatusToFinishReason(status);

        Date created = new Date();

        // steps[]: 解析各个 step
        List<AssistantMessage> messages = new ArrayList<>();
        List<ToolCall> toolCalls = new ArrayList<>();
        String thinkingSignature = null;

        ONode oSteps = oResp.getOrNull("steps");
        if (oSteps != null && oSteps.isArray()) {
            for (ONode oStep : oSteps.getArray()) {
                String stepType = oStep.get("type").getString();
                if (stepType == null) continue;

                switch (stepType) {
                    case "thought":
                        String thoughtText = extractThoughtSummary(oStep);
                        if (Utils.isNotEmpty(thoughtText)) {
                            String signature = oStep.get("signature").getString();
                            if (Utils.isNotEmpty(signature)) {
                                thinkingSignature = signature;
                            }
                            messages.add(new AssistantMessage(thoughtText, true));
                        }
                        break;

                    case "model_output":
                        AssistantMessage modelMsg = extractModelOutputMessage(oStep);
                        if (modelMsg != null) {
                            if (modelMsg.hasMedia()) {
                                resp.addMediaBlocks(modelMsg.getBlocks());
                            }
                            messages.add(modelMsg);
                        }
                        break;

                    case "function_call":
                        ToolCall toolCall = parseFunctionCallStep(oStep);
                        if (toolCall != null) {
                            // 第一个 function_call 可能携带 thought_signature
                            if (toolCalls.isEmpty() && oStep.hasKey("thought_signature")) {
                                String sig = oStep.get("thought_signature").getString();
                                if (Utils.isNotEmpty(sig)) {
                                    toolCall.setThoughtSignature(sig);
                                    thinkingSignature = sig;
                                }
                            }
                            toolCalls.add(toolCall);
                        }
                        break;
                }
            }
        }

        // 保存 thinkingSignature
        if (Utils.isNotEmpty(thinkingSignature)) {
            resp.thinkingSignature = thinkingSignature;
        }

        // 发出消息
        boolean hasChoices = false;
        int choiceIndex = 0;

        // 先发出 thought 消息
        for (AssistantMessage thoughtMsg : messages) {
            resp.addChoice(new ChatChoice(choiceIndex++, created, finishReason, thoughtMsg));
            hasChoices = true;
        }

        // 如果有 tool calls，发出一个空文本的 tool_calls 消息
        if (!toolCalls.isEmpty()) {
            // 结束 thinking 状态（如果有）
            if (resp.in_thinking) {
                resp.addChoice(new ChatChoice(choiceIndex++, created, finishReason,
                        new AssistantMessage("</think>", true)));
                resp.in_thinking = false;
                hasChoices = true;
            }
            AssistantMessage toolCallMsg = new AssistantMessage("", false, null, null, toolCalls, null);
            resp.addChoice(new ChatChoice(choiceIndex++, created, finishReason, toolCallMsg));
            hasChoices = true;
        }

        // finishReason
        if (Utils.isNotEmpty(finishReason)) {
            resp.setFinished(true);
            resp.lastFinishReason = finishReason;
        }

        // 兜底：如果没有 choices 但 response 存在（空响应补一个）
        if (!hasChoices && Utils.isNotEmpty(finishReason)) {
            resp.addChoice(new ChatChoice(0, created, finishReason, new AssistantMessage("")));
            hasChoices = true;
        }

        // usage
        ONode oUsage = oResp.getOrNull("usage");
        if (oUsage != null && resp.isFinished()) {
            parseUsage(resp, oUsage);
        }

        return hasChoices;
    }

    // ==================== 流式解析 ====================

    /**
     * 解析流式响应（SSE 事件数据）
     * <p>
     * 每个 SSE 事件的 data 行包含一个 JSON 对象，event_type 字段标识事件类型。
     * 支持的 event_type:
     * <ul>
     *   <li>interaction.created — 交互创建，包含 interaction id</li>
     *   <li>step.start — 步骤开始，包含 step index 和 type</li>
     *   <li>step.delta — 步骤增量，包含 delta 内容</li>
     *   <li>step.stop — 步骤结束</li>
     *   <li>interaction.completed — 交互完成，包含 usage</li>
     * </ul>
     */
    public boolean parseStreamResponse(ChatResponseDefault resp, String json) {
        ONode oData;
        try {
            oData = ONode.ofJson(json);
        } catch (Exception e) {
            log.warn("Failed to parse Interactions SSE data", e);
            return false;
        }

        if (!oData.isObject()) {
            return false;
        }

        // 错误处理
        if (oData.hasKey("error")) {
            ONode oError = oData.get("error");
            String errorMsg = oError.get("message").getString();
            if (Utils.isEmpty(errorMsg)) {
                errorMsg = oError.toJson();
            }
            resp.setError(new ChatException(errorMsg));
            return true;
        }

        String eventType = oData.get("event_type").getString();
        if (eventType == null) {
            return false;
        }

        boolean hasChoices = false;
        Date created = new Date();

        switch (eventType) {
            case "interaction.created":
                handleInteractionCreated(resp, oData);
                break;

            case "step.start":
                hasChoices = handleStepStart(resp, oData, created);
                break;

            case "step.delta":
                hasChoices = handleStepDelta(resp, oData, created);
                break;

            case "step.stop":
                hasChoices = handleStepStop(resp, oData, created);
                break;

            case "interaction.completed":
                handleInteractionCompleted(resp, oData);
                break;
        }

        return hasChoices;
    }

    /**
     * 处理 interaction.created 事件
     */
    private void handleInteractionCreated(ChatResponseDefault resp, ONode oData) {
        // 清除之前流式会话的累积状态
        stepAccumulators.clear();
        
        ONode interaction = oData.getOrNull("interaction");
        if (interaction != null) {
            if (interaction.hasKey("model")) {
                resp.setModel(interaction.get("model").getString());
            }
        }
    }

    /**
     * 处理 step.start 事件
     * <p>
     * 创建一个新的 StepAccumulator，准备接收 delta 数据。
     * 如果是 thought 类型，开始 thinking 标记。
     */
    private boolean handleStepStart(ChatResponseDefault resp, ONode oData, Date created) {
        int index = oData.get("index").getInt();
        ONode step = oData.getOrNull("step");
        if (step == null) return false;

        String stepType = step.get("type").getString();
        if (stepType == null) return false;

        StepAccumulator acc = new StepAccumulator(index, stepType);
        stepAccumulators.put(index, acc);

        // 如果是 thought 类型且尚未进入 thinking 状态，发出开始标记
        if ("thought".equals(stepType) && !resp.in_thinking) {
            resp.in_thinking = true;
            resp.addChoice(new ChatChoice(index, created, null,
                    new AssistantMessage("\n\n", true)));
            return true;
        }

        // 如果是 function_call 类型，保存函数名和 id
        // Interactions API 在 function_call step 中使用 "id" 字段（非 "call_id"）
        if ("function_call".equals(stepType)) {
            if (step.hasKey("name")) {
                acc.functionName = step.get("name").getString();
            }
            if (step.hasKey("id")) {
                acc.callId = step.get("id").getString();
            }
        }

        return false;
    }

    /**
     * 处理 step.delta 事件
     * <p>
     * 根据 delta 类型处理不同的内容：
     * <ul>
     *   <li>text — 文本增量（model_output content 的一部分）</li>
     *   <li>thought_signature — 思考签名</li>
     *   <li>thought_summary — 思考摘要文本</li>
     *   <li>arguments_delta — 工具调用参数增量</li>
     * </ul>
     */
    private boolean handleStepDelta(ChatResponseDefault resp, ONode oData, Date created) {
        int index = oData.get("index").getInt();
        ONode delta = oData.getOrNull("delta");
        if (delta == null) return false;

        StepAccumulator acc = stepAccumulators.get(index);
        if (acc == null) return false;

        String deltaType = delta.get("type").getString();

        if ("text".equals(deltaType)) {
            String text = delta.get("text").getString();
            if (Utils.isNotEmpty(text)) {
                acc.contentBuilder.append(text);
                // model_output 的 text delta 直接发出
                if ("model_output".equals(acc.stepType)) {
                    if (resp.in_thinking) {
                        resp.addChoice(new ChatChoice(index, created, null,
                                new AssistantMessage("</think>", true)));
                        resp.in_thinking = false;
                    }
                    resp.addChoice(new ChatChoice(index, created, null,
                            new AssistantMessage(text, false)));
                    return true;
                }
            }
        } else if ("thought_signature".equals(deltaType)) {
            String signature = delta.get("signature").getString();
            if (Utils.isNotEmpty(signature)) {
                acc.signature = signature;
                resp.thinkingSignature = signature;
            }
        } else if ("thought_summary".equals(deltaType)) {
            // 提取摘要文本
            ONode summary = delta.getOrNull("summary");
            if (summary != null && summary.isArray()) {
                String summaryText = extractContentArrayText(summary);
                if (Utils.isNotEmpty(summaryText)) {
                    acc.contentBuilder.append(summaryText);
                    // thought 的 summary 增量作为 thinking 内容发出
                    if (!resp.in_thinking) {
                        resp.addChoice(new ChatChoice(index, created, null,
                                new AssistantMessage("\n\n", true)));
                        resp.in_thinking = true;
                    }
                    resp.addChoice(new ChatChoice(index, created, null,
                            new AssistantMessage(summaryText, true)));
                    return true;
                }
            }
        } else if ("arguments_delta".equals(deltaType)) {
            String argsDelta = delta.get("arguments").getString();
            if (Utils.isNotEmpty(argsDelta)) {
                acc.argumentsBuilder.append(argsDelta);
            }
        }

        return false;
    }

    /**
     * 处理 step.stop 事件
     * <p>
     * 完成步骤累积，发出最终消息（如有必要）。
     * function_call 步骤在此处完成并发出 ToolCall。
     */
    private boolean handleStepStop(ChatResponseDefault resp, ONode oData, Date created) {
        int index = oData.get("index").getInt();
        StepAccumulator acc = stepAccumulators.remove(index);
        if (acc == null) return false;

        // function_call 步骤完成
        if ("function_call".equals(acc.stepType)) {
            // 结束 thinking 状态
            if (resp.in_thinking) {
                resp.addChoice(new ChatChoice(index, created, null,
                        new AssistantMessage("</think>", true)));
                resp.in_thinking = false;
            }

            // 从 step.start 中保存的 call info 构建 ToolCall
            // call info 应在之前的 step.start 或关联数据中
            ToolCall toolCall = buildToolCallFromAccumulator(acc);
            if (toolCall != null) {
                resp.addChoice(new ChatChoice(index, created, null,
                        new AssistantMessage("", false, null, null,
                                Collections.singletonList(toolCall), null)));
                return true;
            }
        }

        return false;
    }

    /**
     * 处理 interaction.completed 事件
     */
    private void handleInteractionCompleted(ChatResponseDefault resp, ONode oData) {
        ONode interaction = oData.getOrNull("interaction");
        if (interaction != null) {
            String status = interaction.get("status").getString();
            if ("completed".equals(status)) {
                resp.setFinished(true);
                resp.lastFinishReason = "stop";
            } else if ("requires_action".equals(status)) {
                resp.setFinished(true);
                resp.lastFinishReason = "tool_calls";
            } else if ("failed".equals(status)) {
                ONode oError = interaction.getOrNull("error");
                if (oError != null) {
                    String errorMsg = oError.get("message").getString();
                    if (Utils.isNotEmpty(errorMsg)) {
                        resp.setError(new ChatException(errorMsg));
                    }
                }
                resp.setFinished(true);
                resp.lastFinishReason = "error";
            }

            // usage
            ONode oUsage = interaction.getOrNull("usage");
            if (oUsage != null) {
                parseUsage(resp, oUsage);
            }
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 从 function_call step 中解析 ToolCall
     */
    private ToolCall parseFunctionCallStep(ONode oStep) {
        String name = oStep.get("name").getString();
        // Interactions API 在 function_call step 中使用 "id" 字段（非 "call_id"）
        String callId = oStep.get("id").getString();
        if (name == null) return null;

        if (Utils.isEmpty(callId)) {
            callId = name + "_" + System.currentTimeMillis();
        }

        ONode argsNode = oStep.getOrNull("arguments");
        String argsStr = null;
        Map<String, Object> argsMap = null;
        if (argsNode != null) {
            if (argsNode.isObject()) {
                argsStr = argsNode.toJson();
                argsMap = argsNode.toBean(Map.class);
            } else {
                argsStr = argsNode.getString();
            }
        }

        if (argsStr == null) {
            argsStr = "{}";
        }

        return new ToolCall(callId, callId, name, argsStr, argsMap);
    }

    /**
     * 从 StepAccumulator 构建 ToolCall
     */
    private ToolCall buildToolCallFromAccumulator(StepAccumulator acc) {
        if (Utils.isEmpty(acc.functionName)) return null;

        String callId = acc.callId;
        if (Utils.isEmpty(callId)) {
            callId = acc.functionName + "_" + System.currentTimeMillis();
        }

        String argsStr = acc.argumentsBuilder.length() > 0
                ? acc.argumentsBuilder.toString()
                : "{}";

        Map<String, Object> argsMap = null;
        try {
            ONode argsNode = ONode.ofJson(argsStr);
            if (argsNode.isObject()) {
                argsMap = argsNode.toBean(Map.class);
            }
        } catch (Exception e) {
            // ignore parse error
        }

        ToolCall toolCall = new ToolCall(callId, callId, acc.functionName, argsStr, argsMap);
        if (Utils.isNotEmpty(acc.signature)) {
            toolCall.setThoughtSignature(acc.signature);
        }

        return toolCall;
    }

    /**
     * 提取 thought step 的摘要文本
     */
    private String extractThoughtSummary(ONode oStep) {
        ONode summary = oStep.getOrNull("summary");
        if (summary == null) {
            return null;
        }
        return extractContentArrayText(summary);
    }

    /**
     * 提取 step 的 content 数组文本
     */
    private String extractStepContent(ONode oStep) {
        ONode content = oStep.getOrNull("content");
        if (content == null) {
            return null;
        }
        return extractContentArrayText(content);
    }

    /**
     * 提取 model_output 为 AssistantMessage（含多模态 blocks）。
     *
     * @since 3.9
     */
    private AssistantMessage extractModelOutputMessage(ONode oStep) {
        ONode content = oStep.getOrNull("content");
        if (content == null) {
            return null;
        }
                    
        List<ContentBlock> blocks = extractContentBlocks(content);
        if (blocks.isEmpty()) {
            return null;
        }

        StringBuilder text = new StringBuilder();
        List<ContentBlock> media = new ArrayList<>();
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock) {
                if (text.length() > 0) {
                    text.append("\n");
                }
                text.append(block.getContent());
            } else {
                media.add(block);
            }
        }
        
        if (media.isEmpty()) {
            return new AssistantMessage(text.toString(), false);
        }
    
        List<ContentBlock> blocksForMsg = new ArrayList<>();
        if (text.length() > 0) {
            blocksForMsg.add(TextBlock.of(text.toString()));
        }
        blocksForMsg.addAll(media);
        return new AssistantMessage(text.toString(), false, null, null, null, null, blocksForMsg);
    }
    
    /**
     * 从 Content[] 数组中提取文本
     * <p>
     * Content 数组结构：[{"type": "text", "text": "..."}, ...]
     */
    private String extractContentArrayText(ONode contentArr) {
        List<ContentBlock> blocks = extractContentBlocks(contentArr);
        if (blocks.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock) {
                String text = block.getContent();
                if (Utils.isNotEmpty(text)) {
                    if (sb.length() > 0) {
                        sb.append("\n");
                    }
                    sb.append(text);
                }
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }
    
    /**
     * 从 Content[] 提取完整 blocks（text + media）。
     *
     * @since 3.9
     */
    private List<ContentBlock> extractContentBlocks(ONode contentArr) {
        List<ContentBlock> blocks = new ArrayList<>();
        if (contentArr == null) {
            return blocks;
        }
    
        if (contentArr.isObject()) {
            ContentBlock block = parseInteractionContentItem(contentArr);
            if (block != null) {
                blocks.add(block);
            }
            return blocks;
        }
    
        if (!contentArr.isArray()) {
            return blocks;
        }
    
        for (ONode item : contentArr.getArray()) {
            ContentBlock block = parseInteractionContentItem(item);
            if (block != null) {
                blocks.add(block);
            }
        }
        return blocks;
    }
    
    private ContentBlock parseInteractionContentItem(ONode item) {
        if (item == null || !item.isObject()) {
            return null;
        }
    
        String type = item.get("type").getString();
        if ("text".equals(type) || item.hasKey("text")) {
            String text = item.get("text").getString();
            return Utils.isEmpty(text) ? null : TextBlock.of(text);
        }
    
        if ("inline_data".equals(type) || item.hasKey("data") || item.hasKey("inline_data") || item.hasKey("inlineData")) {
            String mime = item.get("mime_type").getString();
            if (Utils.isEmpty(mime)) {
                mime = item.get("mimeType").getString();
            }
            String data = item.get("data").getString();
            if (Utils.isEmpty(data) && item.hasKey("inline_data")) {
                ONode inline = item.get("inline_data");
                if (inline.isObject()) {
                    data = inline.get("data").getString();
                    if (Utils.isEmpty(mime)) {
                        mime = inline.get("mime_type").getString();
                    }
                }
            }
            return createMediaByMime(mime, null, data);
        }
    
        if ("file_data".equals(type) || item.hasKey("file_uri") || item.hasKey("fileUri") || item.hasKey("file_data") || item.hasKey("fileData")) {
            String mime = item.get("mime_type").getString();
            if (Utils.isEmpty(mime)) {
                mime = item.get("mimeType").getString();
            }
            String uri = item.get("file_uri").getString();
            if (Utils.isEmpty(uri)) {
                uri = item.get("fileUri").getString();
            }
            if (Utils.isEmpty(uri) && item.hasKey("file_data")) {
                ONode fileData = item.get("file_data");
                if (fileData.isObject()) {
                    uri = fileData.get("file_uri").getString();
                    if (Utils.isEmpty(uri)) {
                        uri = fileData.get("fileUri").getString();
                    }
                    if (Utils.isEmpty(mime)) {
                        mime = fileData.get("mime_type").getString();
                    }
                }
            }
            return createMediaByMime(mime, uri, null);
        }
    
        return null;
    }
    
    private ContentBlock createMediaByMime(String mime, String url, String data) {
        boolean hasData = Utils.isNotEmpty(data);
        boolean hasUrl = Utils.isNotEmpty(url);
        if (!hasData && !hasUrl) {
            return null;
        }
    
        String mediaType = "image";
        if (Utils.isNotEmpty(mime)) {
            String lower = mime.toLowerCase();
            if (lower.startsWith("audio/")) {
                mediaType = "audio";
            } else if (lower.startsWith("video/")) {
                mediaType = "video";
            }
        }
    
        if ("audio".equals(mediaType)) {
            if (hasData) {
                return Utils.isEmpty(mime) ? AudioBlock.ofBase64(data) : AudioBlock.ofBase64(data, mime);
            }
            return Utils.isEmpty(mime) ? AudioBlock.ofUrl(url) : AudioBlock.ofUrl(url, mime);
        }
        if ("video".equals(mediaType)) {
            if (hasData) {
                return Utils.isEmpty(mime) ? VideoBlock.ofBase64(data) : VideoBlock.ofBase64(data, mime);
            }
            return Utils.isEmpty(mime) ? VideoBlock.ofUrl(url) : VideoBlock.ofUrl(url, mime);
        }
    
        if (hasData) {
            return Utils.isEmpty(mime) ? ImageBlock.ofBase64(data) : ImageBlock.ofBase64(data, mime);
        }
        return Utils.isEmpty(mime) ? ImageBlock.ofUrl(url) : ImageBlock.ofUrl(url, mime);
    }

    /**
     * 将 Interactions API 的 status 映射为 finishReason
     */
    private String mapStatusToFinishReason(String status) {
        if (status == null) return null;
        switch (status) {
            case "completed":
                return "stop";
            case "requires_action":
                return "tool_calls";
            case "failed":
                return "error";
            default:
                return status;
        }
    }

    /**
     * 解析 usage 信息
     * <p>
     * Interactions API 的 usage 格式：
     * <pre>{@code
     * {
     *   "total_input_tokens": 7,
     *   "total_output_tokens": 20,
     *   "total_thought_tokens": 22,
     *   "total_tokens": 49
     * }
     * }</pre>
     */
    private void parseUsage(ChatResponseDefault resp, ONode oUsage) {
        long promptTokens = oUsage.get("total_input_tokens").getLong();
        long completionTokens = oUsage.get("total_output_tokens").getLong();
        long totalTokens = oUsage.get("total_tokens").getLong();

        resp.setUsage(new AiUsage(promptTokens, 0L, completionTokens, totalTokens, oUsage));
    }

    /**
     * 步骤累积器
     * <p>
     * 用于在流式模式下累积每个 step 的增量数据。
     */
    private static class StepAccumulator {
        final int index;
        final String stepType;
        final StringBuilder contentBuilder = new StringBuilder();
        final StringBuilder argumentsBuilder = new StringBuilder();
        String functionName;
        String callId;
        String signature;

        StepAccumulator(int index, String stepType) {
            this.index = index;
            this.stepType = stepType;
        }
    }
}
