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

    // 流式状态追踪
    private String currentItemId;
    private String currentItemType;
    private StringBuilder currentTextContent;
    private String currentFunctionCallId;
    private String currentFunctionName;
    private StringBuilder currentFunctionArguments;

    public OpenaiResponsesResponseParser() {
        this.logEnabled = log.isDebugEnabled();
    }

    /**
     * 解析响应 JSON
     * @author oisin lu
     * @date 2026年1月28日
     * @param resp 聊天响应对象
     * @param json 响应 JSON 字符串
     * @return 是否有有效的选择
     */
    public boolean parseResponse(ChatResponseDefault resp, String json) {
        if (resp.isStream()) {
            return parseStreamResponse(resp, json);
        } else {
            return parseNonStreamResponse(resp, json);
        }
    }

    /**
     * 解析流式响应
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
                    if (!resp.isFinished()) {
                        resp.addChoice(new ChatChoice(0, new Date(), "stop", new AssistantMessage("")));
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
            String eventType = oResp.get("type").getString();
            if ("error".equals(eventType)) {
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
                    currentItemId = item.get("id").getString();
                    currentItemType = item.get("type").getString();

                    if ("message".equals(currentItemType)) {
                        currentTextContent = new StringBuilder();
                    } else if ("function_call".equals(currentItemType)) {
                        currentFunctionCallId = item.get("call_id").getString();
                        currentFunctionName = item.get("name").getString();
                        currentFunctionArguments = new StringBuilder();
                    }
                }
            } else if ("response.content_part.added".equals(eventType)) {
                // 内容部分添加
                ONode part = oResp.get("part");
                if (part != null) {
                    String partType = part.get("type").getString();
                    if ("output_text".equals(partType)) {
                        currentTextContent = new StringBuilder();
                    }
                }
            } else if ("response.output_text.delta".equals(eventType)) {
                // 文本增量
                String delta = oResp.get("delta").getString();
                if (Utils.isNotEmpty(delta)) {
                    if (currentTextContent != null) {
                        currentTextContent.append(delta);
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
                        if (currentTextContent != null) {
                            currentTextContent.append(text);
                        }
                        resp.addChoice(new ChatChoice(0, new Date(), null, new AssistantMessage(text)));
                        hasChoices = true;
                    }
                }
            } else if ("response.function_call_arguments.delta".equals(eventType)) {
                // 函数调用参数增量
                String delta = oResp.get("delta").getString();
                if (Utils.isNotEmpty(delta) && currentFunctionArguments != null) {
                    currentFunctionArguments.append(delta);
                }
            } else if ("response.function_call_arguments.done".equals(eventType)) {
                // 函数调用参数完成
                if (currentFunctionCallId != null && currentFunctionName != null) {
                    String arguments = oResp.get("arguments").getString();
                    if (Utils.isEmpty(arguments) && currentFunctionArguments != null) {
                        arguments = currentFunctionArguments.toString();
                    }
                    try {
                        Map<String, Object> argMap = new HashMap<>();
                        if (Utils.isNotEmpty(arguments)) {
                            ONode argsNode = ONode.ofJson(arguments);
                            if (argsNode.isObject()) {
                                argMap = argsNode.toBean(Map.class);
                            }
                        }
                        ToolCall toolCall = new ToolCall(currentFunctionCallId, currentFunctionCallId,
                                currentFunctionName, arguments, argMap);
                        List<Map> toolCallsRaw = new ArrayList<>();
                        Map<String, Object> toolCallRaw = new HashMap<>();
                        toolCallRaw.put("id", currentFunctionCallId);
                        toolCallRaw.put("type", "function");
                        Map<String, Object> functionData = new HashMap<>();
                        functionData.put("name", currentFunctionName);
                        functionData.put("arguments", arguments);
                        toolCallRaw.put("function", functionData);
                        toolCallsRaw.add(toolCallRaw);
                        List<ToolCall> toolCalls = new ArrayList<>();
                        toolCalls.add(toolCall);
                        AssistantMessage assistantMessage = new AssistantMessage(resp.getAggregationContent(),
                                false, null,
                                toolCallsRaw, toolCalls, null);
                        resp.addChoice(new ChatChoice(0, new Date(), null, assistantMessage));
                        hasChoices = true;
                    } catch (Exception e) {
                        log.warn("Failed to parse function call in stream mode", e);
                    } finally {
                        // 重置函数调用状态
                        currentFunctionCallId = null;
                        currentFunctionName = null;
                        currentFunctionArguments = null;
                    }
                }
            } else if ("response.output_item.done".equals(eventType)) {
                // 输出项完成
                currentItemId = null;
                currentItemType = null;
                currentTextContent = null;
            } else if ("response.output_text.done".equals(eventType) || "response.content_part.done".equals(eventType)) {
                // 文本/内容部分完成
                // 不需要特殊处理
            } else if ("response.completed".equals(eventType)) {
                // 响应完成
                ONode response = oResp.get("response");
                if (response != null) {
                    resp.setModel(response.get("model").getString());
                    // 解析 usage
                    AiUsage usage = parseUsage(response.getOrNull("usage"));
                    if (usage != null) {
                        resp.setUsage(usage);
                    }
                }
                resp.setFinished(true);
            } else if ("response.failed".equals(eventType)) {
                // 响应失败
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
        return hasChoices;
    }

    /**
     * 解析非流式响应
     * @author oisin lu
     * @date 2026年1月28日
     */
    public boolean parseNonStreamResponse(ChatResponseDefault resp, String json) {
        if ("[DONE]".equals(json)) {
            if (!resp.isFinished()) {
                resp.addChoice(new ChatChoice(0, new Date(), "stop", new AssistantMessage("")));
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
            List<AssistantMessage> messageList = new ArrayList<>();
            StringBuilder textContent = new StringBuilder();
            for (ONode outputItem : outputArray.getArray()) {
                String itemType = outputItem.get("type").getString();
                if ("message".equals(itemType)) {
                    // 解析消息内容
                    ONode contentArray = outputItem.getOrNull("content");
                    if (contentArray != null && contentArray.isArray()) {
                        for (ONode contentItem : contentArray.getArray()) {
                            String contentType = contentItem.get("type").getString();
                            if ("output_text".equals(contentType)) {
                                String text = contentItem.get("text").getString();
                                if (Utils.isNotEmpty(text)) {
                                    if (textContent.length() > 0) {
                                        textContent.append("\n");
                                    }
                                    textContent.append(text);
                                }
                            }
                        }
                    }
                } else if ("function_call".equals(itemType)) {
                    // 解析工具调用
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
                    ToolCall toolCall = new ToolCall(callId, callId, functionName, arguments, argMap);
                    List<Map> toolCallsRaw = new ArrayList<>();
                    Map<String, Object> toolCallRaw = new HashMap<>();
                    toolCallRaw.put("id", callId);
                    toolCallRaw.put("type", "function");
                    Map<String, Object> functionData = new HashMap<>();
                    functionData.put("name", functionName);
                    functionData.put("arguments", arguments);
                    toolCallRaw.put("function", functionData);
                    toolCallsRaw.add(toolCallRaw);

                    List<ToolCall> toolCalls = new ArrayList<>();
                    toolCalls.add(toolCall);

                    AssistantMessage assistantMessage = new AssistantMessage(resp.getAggregationContent(),
                            false, null,
                            toolCallsRaw, toolCalls, null);
                    messageList.add(assistantMessage);
                }
            }
            // 添加文本内容消息
            if (textContent.length() > 0) {
                messageList.add(0, new AssistantMessage(textContent.toString()));
            }
            // 如果有 output_text 便捷字段
            if (messageList.isEmpty()) {
                String outputText = oResp.get("output_text").getString();
                if (Utils.isNotEmpty(outputText)) {
                    messageList.add(new AssistantMessage(outputText));
                }
            }
            // 添加所有解析出的消息
            for (AssistantMessage msg : messageList) {
                resp.addChoice(new ChatChoice(0, created, "stop", msg));
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
        if (inputTokens > 0 || outputTokens > 0) {
            return new AiUsage(inputTokens, outputTokens, totalTokens, usageNode);
        }
        return null;
    }
}
