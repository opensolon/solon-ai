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
package org.noear.solon.ai.llm.dialect.claude;

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
 * Claude 响应解析器
 * @author oisin lu
 * @date 2026年1月27日
 */
public class ClaudeResponseParser {
    private static final Logger log = LoggerFactory.getLogger(ClaudeResponseParser.class);
    private final boolean logEnabled;

    // 流式工具调用状态追踪
    private String currentToolUseId;
    private String currentToolName;
    private StringBuilder currentToolInput;

    // 流式思考内容状态追踪
    private boolean currentBlockIsThinking;

    public ClaudeResponseParser() {
        this.logEnabled = log.isDebugEnabled();
    }

    /**
     * 解析 usage 信息（包含 Prompt Caching 统计）
     * @author oisin lu
     * @date 2026年1月27日
     * @param usageNode usage JSON 节点
     * @return AiUsage 对象
     */
    private AiUsage parseUsage(ONode usageNode) {
        if (usageNode == null) {
            return null;
        }
        long inputTokens = usageNode.hasKey("input_tokens") ? usageNode.get("input_tokens").getLong() : 0L;
        long outputTokens = usageNode.hasKey("output_tokens") ? usageNode.get("output_tokens").getLong() : 0L;
        // Claude Prompt Caching 相关的 token 统计
        long cacheCreationInputTokens = 0L;
        long cacheReadInputTokens = 0L;
        if (usageNode.hasKey("cache_creation_input_tokens")) {
            cacheCreationInputTokens = usageNode.get("cache_creation_input_tokens").getLong();
        }
        if (usageNode.hasKey("cache_read_input_tokens")) {
            cacheReadInputTokens = usageNode.get("cache_read_input_tokens").getLong();
        }
        // 只有在有实际 token 消耗时才返回 usage
        if (inputTokens > 0 || outputTokens > 0 || cacheCreationInputTokens > 0 || cacheReadInputTokens > 0) {
            return new AiUsage(inputTokens, outputTokens, inputTokens + outputTokens,
                    cacheCreationInputTokens, cacheReadInputTokens, usageNode);
        }

        return null;
    }

    /**
     * 解析响应 JSON
     * @author oisin lu
     * @date 2026年1月27日
     * @param resp  聊天响应对象
     * @param json  响应 JSON 字符串
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
     * @date 2026年1月27日
     * @param resp 聊天响应对象
     * @param json 响应 JSON 字符串
     * @return 是否有有效的选择
     */
    public boolean parseStreamResponse(ChatResponseDefault resp, String json) {
        if (json == null || json.isEmpty()) {
            return false;
        }
        if (logEnabled) {
            log.debug("Claude stream raw response: {}", json);
        }
        String[] lines = json.split("\n");
        boolean hasChoices = false;

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            String jsonData = line;
            if (line.startsWith("data:")) {
                jsonData = line.substring(5).trim();
            }
            if (jsonData.isEmpty()) {
                continue;
            }
            if ("[DONE]".equals(jsonData)) {
                if (resp.isFinished() == false) {
                    resp.addChoice(new ChatChoice(0, new Date(), "stop", new AssistantMessage("")));
                    resp.setFinished(true);
                }
                return true;
            }
            ONode oResp = ONode.ofJson(jsonData);
            if (oResp.isObject() == false) {
                continue;
            }
            // Claude 流式响应事件类型
            String eventType = oResp.get("type").getString();
            if ("error".equals(eventType)) {
                ONode oError = oResp.get("error");
                String errorType = oError.get("type").getString();
                String errorMsg = oError.get("message").getString();
                if (Utils.isEmpty(errorMsg)) {
                    errorMsg = oError.getString();
                }

                // 构建详细的错误信息
                String detailedError = errorMsg;
                if (Utils.isNotEmpty(errorType)) {
                    detailedError = String.format("[%s] %s", errorType, errorMsg);
                }

                resp.setError(new ChatException(detailedError));
                return true;
            } else if ("message_start".equals(eventType)) {
                // 消息开始，可以设置模型信息和初始 usage
                ONode message = oResp.get("message");
                if (message != null) {
                    resp.setModel(message.get("model").getString());

                    // 某些情况下 message_start 也包含初始 usage 信息
                    AiUsage usage = parseUsage(message.getOrNull("usage"));
                    if (usage != null) {
                        resp.setUsage(usage);
                    }
                }
            } else if ("content_block_start".equals(eventType)) {
                ONode contentBlock = oResp.get("content_block");
                if (contentBlock != null) {
                    String blockType = contentBlock.get("type").getString();
                    if ("thinking".equals(blockType)) {
                        // 思考内容块开始
                        currentBlockIsThinking = true;
                        if (!resp.in_thinking) {
                            // 第一次进入思考模式，添加开始标记
                            resp.addChoice(new ChatChoice(0, new Date(), null,
                                    new AssistantMessage("<think>", true)));
                            resp.addChoice(new ChatChoice(0, new Date(), null,
                                    new AssistantMessage("\n\n", true)));
                            resp.in_thinking = true;
                            hasChoices = true;
                        }
                        String thinking = contentBlock.get("thinking").getString();
                        if (Utils.isNotEmpty(thinking)) {
                            resp.addChoice(new ChatChoice(0, new Date(), null,
                                    new AssistantMessage(thinking, true)));
                            hasChoices = true;
                        }
                    } else if ("text".equals(blockType)) {
                        currentBlockIsThinking = false;
                        // 如果之前在思考模式，添加结束标记
                        if (resp.in_thinking) {
                            resp.addChoice(new ChatChoice(0, new Date(), null,
                                    new AssistantMessage("</think>", true)));
                            resp.addChoice(new ChatChoice(0, new Date(), null,
                                    new AssistantMessage("\n\n", false)));
                            resp.in_thinking = false;
                            hasChoices = true;
                        }
                        String text = contentBlock.get("text").getString();
                        if (Utils.isNotEmpty(text)) {
                            resp.addChoice(new ChatChoice(0, new Date(), null,
                                    new AssistantMessage(text)));
                            hasChoices = true;
                        }
                    } else if ("tool_use".equals(blockType)) {
                        currentBlockIsThinking = false;
                        // 如果之前在思考模式，添加结束标记
                        if (resp.in_thinking) {
                            resp.addChoice(new ChatChoice(0, new Date(), null,
                                    new AssistantMessage("</think>", true)));
                            resp.addChoice(new ChatChoice(0, new Date(), null,
                                    new AssistantMessage("\n\n", false)));
                            resp.in_thinking = false;
                            hasChoices = true;
                        }
                        // 工具调用开始，初始化状态
                        currentToolUseId = contentBlock.get("id").getString();
                        currentToolName = contentBlock.get("name").getString();
                        currentToolInput = new StringBuilder();
                    }
                }
            } else if ("content_block_delta".equals(eventType)) {
                // 内容块增量更新
                ONode delta = oResp.get("delta");
                if (delta != null) {
                    String deltaType = delta.get("type").getString();
                    if ("thinking_delta".equals(deltaType)) {
                        // 思考内容增量更新
                        String thinking = delta.get("thinking").getString();
                        if (Utils.isNotEmpty(thinking)) {
                            resp.addChoice(new ChatChoice(0, new Date(), null,
                                    new AssistantMessage(thinking, true)));
                            hasChoices = true;
                        }
                    } else if ("text_delta".equals(deltaType)) {
                        String text = delta.get("text").getString();
                        if (Utils.isNotEmpty(text)) {
                            resp.addChoice(new ChatChoice(0, new Date(), null,
                                    new AssistantMessage(text)));
                            hasChoices = true;
                        }
                    } else if ("input_json_delta".equals(deltaType)) {
                        // 工具调用参数增量更新，累积JSON
                        String partialJson = delta.get("partial_json").getString();
                        if (Utils.isNotEmpty(partialJson) && currentToolInput != null) {
                            currentToolInput.append(partialJson);
                        }
                    }
                }
            } else if ("content_block_stop".equals(eventType)) {
                // 内容块结束，如果是工具调用，构建工具调用消息
                if (currentToolUseId != null && currentToolName != null && currentToolInput != null) {
                    try {
                        String inputJson = currentToolInput.toString();
                        Map<String, Object> arguments = new HashMap<>();
                        if (Utils.isNotEmpty(inputJson)) {
                            ONode inputNode = ONode.ofJson(inputJson);
                            if (inputNode.isObject()) {
                                arguments = inputNode.toBean(Map.class);
                            }
                        }

                        // 创建工具调用对象
                        ToolCall toolCall = new ToolCall(currentToolUseId, currentToolUseId, currentToolName, inputJson, arguments);

                        // 创建带有工具调用的助手消息
                        List<Map> toolCallsRaw = new ArrayList<>();
                        Map<String, Object> toolCallRaw = new HashMap<>();
                        toolCallRaw.put("id", currentToolUseId);
                        toolCallRaw.put("type", "function");
                        Map<String, Object> functionData = new HashMap<>();
                        functionData.put("name", currentToolName);
                        functionData.put("arguments", inputJson);
                        toolCallRaw.put("function", functionData);
                        toolCallsRaw.add(toolCallRaw);

                        List<ToolCall> toolCalls = new ArrayList<>();
                        toolCalls.add(toolCall);
                        AssistantMessage assistantMessage = new AssistantMessage(resp.getAggregationContent(),
                                false, null, toolCallsRaw,
                                toolCalls, null);
                        resp.addChoice(new ChatChoice(0, new Date(), null, assistantMessage));
                        hasChoices = true;
                    } catch (Exception e) {
                        log.warn("Failed to parse tool call in stream mode", e);
                    } finally {
                        // 重置工具调用状态
                        currentToolUseId = null;
                        currentToolName = null;
                        currentToolInput = null;
                    }
                }
            } else if ("message_delta".equals(eventType)) {
                // 消息增量更新，包含停止原因和用量信息
                AiUsage usage = parseUsage(oResp.get("usage"));
                if (usage != null) {
                    resp.setUsage(usage);
                }
                
                ONode stopReason = oResp.get("delta");
                if (stopReason != null) {
                    String finishReason = stopReason.get("stop_reason").getString();
                    if (Utils.isNotEmpty(finishReason)) {
                        resp.setFinished(true);
                    }
                }
            } else if ("message_stop".equals(eventType)) {
                // 消息结束
                resp.setFinished(true);
            } else if ("ping".equals(eventType)) {
                // 心跳消息，忽略
                continue;
            }
        }

        return hasChoices;
    }

    /**
     * 解析非流式响应
     * @author oisin lu
     * @date 2026年1月27日
     * @param resp 聊天响应对象
     * @param json 响应 JSON 字符串
     * @return 解析是否成功
     */
    public boolean parseNonStreamResponse(ChatResponseDefault resp, String json) {
        if ("[DONE]".equals(json)) {
            if (resp.isFinished() == false) {
                resp.addChoice(new ChatChoice(0, new Date(), "stop", new AssistantMessage("")));
                resp.setFinished(true);
            }
            return true;
        }
        ONode oResp = ONode.ofJson(json);
        if (oResp.isObject() == false) {
            return false;
        }
        if (oResp.hasKey("error") && !oResp.get("error").isNull()) {
            ONode oError = oResp.get("error");
            String errorType = oError.get("type").getString();
            String errorMsg = oError.get("message").getString();
            if (Utils.isEmpty(errorMsg)) {
                errorMsg = oError.getString();
            }
            // 构建详细的错误信息
            String detailedError = errorMsg;
            if (Utils.isNotEmpty(errorType)) {
                detailedError = String.format("[%s] %s", errorType, errorMsg);
            }
            resp.setError(new ChatException(detailedError));
            return true;
        }

        // 设置模型信息
        resp.setModel(oResp.get("model").getString());
        Date created = new Date();
        if (oResp.hasKey("created")) {
            created = new Date(oResp.get("created").getLong() * 1000);
        }
        // 解析内容
        ONode contentArray = oResp.getOrNull("content");
        if (contentArray != null && contentArray.isArray()) {
            List<AssistantMessage> messageList = new ArrayList<>();
            // 分离思考内容和普通内容
            StringBuilder thinkingContent = new StringBuilder();
            StringBuilder normalContent = new StringBuilder();
            for (ONode contentItem : contentArray.getArray()) {
                String contentType = contentItem.get("type").getString();
                if ("thinking".equals(contentType)) {
                    // 收集思考内容
                    String thinking = contentItem.get("thinking").getString();
                    if (Utils.isNotEmpty(thinking)) {
                        if (thinkingContent.length() > 0) {
                            thinkingContent.append("\n");
                        }
                        thinkingContent.append(thinking);
                    }
                } else if ("text".equals(contentType)) {
                    // 收集普通文本内容
                    String text = contentItem.get("text").getString();
                    if (Utils.isNotEmpty(text)) {
                        if (normalContent.length() > 0) {
                            normalContent.append("\n");
                        }
                        normalContent.append(text);
                    }
                } else if ("tool_use".equals(contentType)) {
                    // 解析工具调用
                    String toolName = contentItem.get("name").getString();
                    String toolId = contentItem.get("id").getString();
                    ONode inputNode = contentItem.get("input");
                    Map<String, Object> arguments = new HashMap<>();
                    if (inputNode != null && inputNode.isObject()) {
                        arguments = inputNode.toBean(Map.class);
                    }
                    // 创建工具调用对象
                    ToolCall toolCall = new ToolCall(toolId, toolId, toolName, inputNode.toJson(), arguments);
                    // 创建带有工具调用的助手消息
                    List<Map> toolCallsRaw = new ArrayList<>();
                    Map<String, Object> toolCallRaw = new HashMap<>();
                    toolCallRaw.put("id", toolId);
                    toolCallRaw.put("type", "function");
                    Map<String, Object> functionData = new HashMap<>();
                    functionData.put("name", toolName);
                    functionData.put("arguments", inputNode.toJson());
                    toolCallRaw.put("function", functionData);
                    toolCallsRaw.add(toolCallRaw);
                    List<ToolCall> toolCalls = new ArrayList<>();
                    toolCalls.add(toolCall);

                    AssistantMessage assistantMessage = new AssistantMessage(resp.getAggregationContent(),
                            false, null, toolCallsRaw,
                            toolCalls, null);
                    messageList.add(assistantMessage);
                }
            }

            // 构建包含思考内容的消息
            if (thinkingContent.length() > 0 && normalContent.length() > 0) {
                // 有思考内容和普通内容
                String fullContent = "<think>\n\n" + thinkingContent.toString() + "</think>\n\n" + normalContent.toString();
                Map<String, Object> contentRaw = new LinkedHashMap<>();
                contentRaw.put("thinking", thinkingContent.toString());
                contentRaw.put("content", normalContent.toString());
                messageList.add(0, new AssistantMessage(fullContent, false, contentRaw, null, null, null));
            } else if (thinkingContent.length() > 0) {
                // 只有思考内容
                String fullContent = "<think>\n\n" + thinkingContent.toString() + "</think>\n\n";
                Map<String, Object> contentRaw = new LinkedHashMap<>();
                contentRaw.put("thinking", thinkingContent.toString());
                messageList.add(0, new AssistantMessage(fullContent, false, contentRaw, null, null, null));
            } else if (normalContent.length() > 0) {
                // 只有普通内容
                messageList.add(0, new AssistantMessage(normalContent.toString()));
            }
            // 添加所有解析出的消息
            for (AssistantMessage msg : messageList) {
                resp.addChoice(new ChatChoice(0, created, "stop", msg));
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
}