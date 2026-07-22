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
    private final boolean logEnabled;

    /**
     * 流式工具调用的按请求隔离状态
     */
    private static class StreamState {
        String currentItemId;
        String currentItemType;
        StringBuilder currentTextContent;
        String currentFunctionCallId;
        String currentFunctionName;
        StringBuilder currentFunctionArguments;
    }
    private static final String STREAM_STATE_KEY = "StreamState";

    public OpenaiResponsesResponseParser() {
        this.logEnabled = log.isDebugEnabled();
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
        if (logEnabled) {
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
            ONode oResp = ONode.ofJson(jsonData);
            if (!oResp.isObject()) {
                continue;
            }

            if(oResp.hasKey("error")){
                resp.setError(new ChatException(oResp.get("error").getString()));
                return true;
            }

            String eventType = oResp.get("type").getString();
            if ("error".equals(eventType)) {
                resp.attrRemove(STREAM_STATE_KEY);
                ONode oError = oResp.get("error");
                String errorType = oError.get("type").getString();
                String errorMsg = oError.get("message").getString();
                if (Utils.isEmpty(errorMsg)) {
                    errorMsg = oError.getString();
                }
                String detailedError = errorMsg;
                if (Utils.isNotEmpty(errorType)) {
                    detailedError = String.format("[%s] %s", errorType, errorMsg);
                }
                resp.setError(new ChatException(detailedError));
                return true;
            } else if ("response.created".equals(eventType) || "response.in_progress".equals(eventType)) {
                // 响应创建/进行中，可以设置模型信息
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
                        state.currentTextContent = new StringBuilder();
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
                    state.currentItemId = null;
                    state.currentItemType = null;
                    state.currentTextContent = null;
                }
            } else if ("response.content_part.added".equals(eventType)) {
                // 内容部分添加
                ONode part = oResp.get("part");
                if (part != null) {
                    String partType = part.get("type").getString();
                    if ("output_text".equals(partType)) {
                        StreamState state = getOrCreateState(resp);
                        state.currentTextContent = new StringBuilder();
                    }
                }
            } else if ("response.output_text.delta".equals(eventType)) {
                // 文本增量
                String delta = oResp.get("delta").getString();
                if (Utils.isNotEmpty(delta)) {
                    StreamState state = resp.attrAs(STREAM_STATE_KEY);
                    if (state != null && state.currentTextContent != null) {
                        state.currentTextContent.append(delta);
                    }
                    resp.addChoice(new ChatChoice(0, new Date(), null, new AssistantMessage(delta)));
                    hasChoices = true;
                }
            } else if ("response.content_part.delta".equals(eventType)) {
                // 内容部分增量（通用）
                ONode delta = oResp.get("delta");
                if (delta != null) {
                    String text = delta.get("text").getString();
                    if (Utils.isNotEmpty(text)) {
                        StreamState state = resp.attrAs(STREAM_STATE_KEY);
                        if (state != null && state.currentTextContent != null) {
                            state.currentTextContent.append(text);
                        }
                        resp.addChoice(new ChatChoice(0, new Date(), null, new AssistantMessage(text)));
                        hasChoices = true;
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
                    String arguments = oResp.get("arguments").getString();
                    if (Utils.isEmpty(arguments) && state.currentFunctionArguments != null) {
                        arguments = state.currentFunctionArguments.toString();
                    }
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
                        AssistantMessage assistantMessage = new AssistantMessage("",
                                false, null,
                                toolCallsRaw, toolCalls, null);
                        resp.addChoice(new ChatChoice(0, new Date(), null, assistantMessage));
                        hasChoices = true;
                    } catch (Exception e) {
                        log.warn("Failed to parse function call in stream mode", e);
                    } finally {
                        // 重置函数调用状态
                        state.currentFunctionCallId = null;
                        state.currentFunctionName = null;
                        state.currentFunctionArguments = null;
                    }
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
            } else if ("response.failed".equals(eventType)) {
                // 响应失败，清理状态
                resp.attrRemove(STREAM_STATE_KEY);
                ONode response = oResp.get("response");
                if (response != null) {
                    ONode error = response.get("error");
                    if (error != null) {
                        String errorMsg = error.get("message").getString();
                        resp.setError(new ChatException(errorMsg != null ? errorMsg : "Response failed"));
                    }
                }
                resp.setFinished(true);
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
        // 检查错误
        if (oResp.hasKey("error") && !oResp.get("error").isNull()) {
            ONode oError = oResp.get("error");
            String errorType = oError.get("type").getString();
            String errorMsg = oError.get("message").getString();
            if (Utils.isEmpty(errorMsg)) {
                errorMsg = oError.getString();
            }
            String detailedError = errorMsg;
            if (Utils.isNotEmpty(errorType)) {
                detailedError = String.format("[%s] %s", errorType, errorMsg);
            }
            resp.setError(new ChatException(detailedError));
            return true;
        }
        // 检查状态
        String status = oResp.get("status").getString();
        if ("failed".equals(status)) {
            ONode error = oResp.get("error");
            String errorMsg = error != null ? error.get("message").getString() : "Response failed";
            resp.setError(new ChatException(errorMsg));
            return true;
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
            List<ContentBlock> mediaBlocks = new ArrayList<>();
            List<ToolCall> allToolCalls = new ArrayList<>();
            List<Map> allToolCallsRaw = new ArrayList<>();
            for (ONode outputItem : outputArray.getArray()) {
                String itemType = outputItem.get("type").getString();
                if ("message".equals(itemType)) {
                    // 解析消息内容：output_text / refusal / 兼容 image
                    ONode contentArray = outputItem.getOrNull("content");
                    if (contentArray != null && contentArray.isArray()) {
                        for (ONode contentItem : contentArray.getArray()) {
                            String contentType = contentItem.get("type").getString();
                            if ("output_text".equals(contentType) || "text".equals(contentType)) {
                                String text = contentItem.get("text").getString();
                                if (Utils.isNotEmpty(text)) {
                                    if (textContent.length() > 0) {
                                        textContent.append("\n");
                                    }
                                    textContent.append(text);
                                }
                            } else if ("refusal".equals(contentType)) {
                                String refusal = contentItem.get("refusal").getString();
                                if (Utils.isEmpty(refusal)) {
                                    refusal = contentItem.get("text").getString();
                                }
                                if (Utils.isNotEmpty(refusal)) {
                                    if (textContent.length() > 0) {
                                        textContent.append("\n");
                                    }
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
                    String arguments = outputItem.get("arguments").getString();
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
        
            // 将所有工具调用合并到一个 AssistantMessage 中
            if (!allToolCalls.isEmpty()) {
                AssistantMessage msg = new AssistantMessage(textContent.toString(),
                        false, null, allToolCallsRaw, allToolCalls, null, blocksForMsg);
                resp.addChoice(new ChatChoice(0, created, "stop", msg));
            } else if (textContent.length() > 0 || blocksForMsg != null) {
                AssistantMessage msg = new AssistantMessage(textContent.toString(),
                        false, null, null, null, null, blocksForMsg);
                resp.addChoice(new ChatChoice(0, created, "stop", msg));
            } else {
                // 如果有 output_text 便捷字段
                String outputText = oResp.get("output_text").getString();
                if (Utils.isNotEmpty(outputText)) {
                    resp.addChoice(new ChatChoice(0, created, "stop", new AssistantMessage(outputText)));
                }
            }
        } else {
            // 如果没有 output 数组，尝试使用 output_text
            String outputText = oResp.get("output_text").getString();
            if (Utils.isNotEmpty(outputText)) {
                resp.addChoice(new ChatChoice(0, created, "stop", new AssistantMessage(outputText)));
            }
        }
        // 解析用量信息
        AiUsage usage = parseUsage(oResp.getOrNull("usage"));
        if (usage != null) {
            resp.setUsage(usage);
        }

        resp.setFinished(true);
        return true;
    }

    /**
     * 解析 usage 信息
     *
     * @author oisin lu
     * @date 2026年1月28日
     */
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
    
    private AiUsage parseUsage(ONode usageNode) {
        if (usageNode == null) {
            return null;
        }
        // Responses API 使用 input_tokens 和 output_tokens
        long inputTokens = usageNode.hasKey("input_tokens") ? usageNode.get("input_tokens").getLong() : 0L;
        long outputTokens = usageNode.hasKey("output_tokens") ? usageNode.get("output_tokens").getLong() : 0L;
        long totalTokens = usageNode.hasKey("total_tokens") ? usageNode.get("total_tokens").getLong() : (inputTokens + outputTokens);

        // 读取缓存 token 统计
        long cacheReadInputTokens = 0L;
        ONode inputTokensDetails = usageNode.getOrNull("input_tokens_details");
        if (inputTokensDetails != null) {
            cacheReadInputTokens = inputTokensDetails.get("cached_tokens").getLong();
        }

        if (inputTokens > 0 || outputTokens > 0 || cacheReadInputTokens > 0) {
            return new AiUsage(inputTokens, 0L, outputTokens, totalTokens,
                    0L, cacheReadInputTokens, usageNode);
        }

        return null;
    }
}