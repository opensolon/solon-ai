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
package org.noear.solon.ai.llm.dialect.anthropic;

import org.noear.snack4.Feature;
import org.noear.snack4.ONode;
import org.noear.snack4.Options;
import org.noear.snack4.json.JsonReader;
import org.noear.snack4.json.util.FormatUtil;
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
 * Claude 响应解析器
 * @author oisin lu
 * @date 2026年1月27日
 */
public class AnthropicResponseParser {
    private static final Logger LOG = LoggerFactory.getLogger(AnthropicResponseParser.class);

    private final boolean logEnabled;

    /**
     * 流式工具调用的按请求隔离状态
     */
    private static class StreamToolState {
        String toolUseId;
        String toolName;
        StringBuilder toolInput;
    }

    private static final String STREAM_TOOL_STATE_KEY = "StreamToolState";

    public AnthropicResponseParser() {
        this.logEnabled = LOG.isDebugEnabled();
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
            return new AiUsage(inputTokens, 0L, outputTokens, inputTokens + outputTokens,
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
                resp.attrRemove(STREAM_TOOL_STATE_KEY);
                if (resp.isFinished() == false) {
                    resp.addChoice(new ChatChoice(0, new Date(), resp.getLastFinishReasonNormalized(), new AssistantMessage("")));
                    resp.setFinished(true);
                }
                return true;
            }
            ONode oResp = ONode.ofJson(jsonData);
            if (oResp.isObject() == false) {
                continue;
            }

            if (oResp.hasKey("error")) {
                resp.setError(new ChatException(oResp.get("error").getString()));
                return true;
            }

            // Claude 流式响应事件类型
            String eventType = oResp.get("type").getString();
            if ("error".equals(eventType)) {
                resp.attrRemove(STREAM_TOOL_STATE_KEY);

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
                        if (!resp.in_thinking) {
                            // 第一次进入思考模式，添加开始标记
                            resp.addChoice(new ChatChoice(0, new Date(), null,
                                    new AssistantMessage("<think>", true)));
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
                        // 如果之前在思考模式，添加结束标记
                        if (resp.in_thinking) {
                            resp.addChoice(new ChatChoice(0, new Date(), null,
                                    new AssistantMessage("</think>", true)));
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
                        // 如果之前在思考模式，添加结束标记
                        if (resp.in_thinking) {
                            resp.addChoice(new ChatChoice(0, new Date(), null,
                                    new AssistantMessage("</think>", true)));
                            resp.in_thinking = false;
                            hasChoices = true;
                        }
                        StreamToolState state = new StreamToolState();
                        state.toolUseId = contentBlock.get("id").getString();
                        state.toolName = contentBlock.get("name").getString();
                        state.toolInput = new StringBuilder();

                        resp.attrPut(STREAM_TOOL_STATE_KEY, state);
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
                            resp.reasoningBuilder.append(thinking);
                            resp.addChoice(new ChatChoice(0, new Date(), null,
                                    new AssistantMessage(thinking, true)));
                            hasChoices = true;
                        }
                    } else if ("signature_delta".equals(deltaType)) {
                        String signature = delta.get("signature").getString();
                        if (Utils.isNotEmpty(signature)) {
                            resp.thinkingSignature = signature;
                        }
                    } else if ("text_delta".equals(deltaType)) {
                        String text = delta.get("text").getString();
                        if (Utils.isNotEmpty(text)) {
                            resp.addChoice(new ChatChoice(0, new Date(), null,
                                    new AssistantMessage(text)));
                            hasChoices = true;
                        }
                    } else if ("input_json_delta".equals(deltaType)) {
                        // 工具调用参数增量更新，按需从 map 获取状态
                        String partialJson = delta.get("partial_json").getString();
                        if (Utils.isNotEmpty(partialJson)) {
                            StreamToolState state = resp.attrAs(STREAM_TOOL_STATE_KEY);
                            if (state != null) {
                                state.toolInput.append(partialJson);
                            }
                        }
                    }
                }
            } else if ("content_block_stop".equals(eventType)) {
                // 内容块结束，按需从 map 获取并清理工具调用状态
                StreamToolState state = resp.attrRemove(STREAM_TOOL_STATE_KEY);
                if (state != null) {
                    try {
                        String argStr = state.toolInput.toString();
                        Map<String, Object> arguments = new HashMap<>();

                        if (FormatUtil.hasNestedJsonBlock(argStr)) {
                            JsonReader reader = new JsonReader(argStr, Options.of(Feature.Read_AutoRepair));
                            ONode n1fArgs = reader.readLast();

                            if (n1fArgs == null) {
                                LOG.warn("Parse tool arguments failed: {}", argStr);
                            } else if (n1fArgs.isObject()) {
                                arguments = n1fArgs.toBean(Map.class);
                            }
                        }

                        // 创建工具调用对象
                        ToolCall toolCall = new ToolCall(state.toolUseId, state.toolUseId, state.toolName, argStr, arguments);

                        // 创建带有工具调用的助手消息
                        List<Map> toolCallsRaw = new ArrayList<>();
                        Map<String, Object> toolCallRaw = new HashMap<>();
                        toolCallRaw.put("id", state.toolUseId);
                        toolCallRaw.put("type", "function");
                        Map<String, Object> functionData = new HashMap<>();
                        functionData.put("name", state.toolName);
                        functionData.put("arguments", argStr);
                        toolCallRaw.put("function", functionData);
                        toolCallsRaw.add(toolCallRaw);

                        List<ToolCall> toolCalls = new ArrayList<>();
                        toolCalls.add(toolCall);
                        AssistantMessage assistantMessage = new AssistantMessage("",
                                false, null, toolCallsRaw,
                                toolCalls, null);
                        resp.addChoice(new ChatChoice(0, new Date(), null, assistantMessage));
                        hasChoices = true;
                    } catch (Exception e) {
                        LOG.warn("Failed to parse tool call in stream mode", e);
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
                        resp.lastFinishReason = finishReason;
                    }
                }
            } else if ("message_stop".equals(eventType)) {
                // 消息结束，清理状态并添加信息对 finished 进行透传
                resp.attrRemove(STREAM_TOOL_STATE_KEY);

                if (resp.hasChoices() == false) { //完成时。如果为空，则补位
                    resp.addChoice(new ChatChoice(0, new Date(), resp.getLastFinishReasonNormalized(), new AssistantMessage("")));
                }

                resp.setFinished(true);
                hasChoices = true;
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
                resp.addChoice(new ChatChoice(0, new Date(), resp.getLastFinishReasonNormalized(), new AssistantMessage("")));
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
        // 先解析 stop_reason，供 choice.finishReason 与 lastFinishReason 共用
        String stopReason = oResp.get("stop_reason").getString();

        // 解析内容
        ONode contentArray = oResp.getOrNull("content");
        if (contentArray != null && contentArray.isArray()) {
            // 分离思考内容、普通内容、媒体与工具调用
            StringBuilder thinkingContent = new StringBuilder();
            String thinkingSignature = null;
            StringBuilder normalContent = new StringBuilder();
            List<ContentBlock> mediaBlocks = new ArrayList<>();
            List<ToolCall> allToolCalls = new ArrayList<>();
            List<Map> allToolCallsRaw = new ArrayList<>();
            
            for (ONode contentItem : contentArray.getArray()) {
                String contentType = contentItem.get("type").getString();
                if ("thinking".equals(contentType)) {
                    String thinking = contentItem.get("thinking").getString();
                    if (Utils.isNotEmpty(thinking)) {
                        if (thinkingContent.length() > 0) {
                            thinkingContent.append("\n");
                        }
                        thinkingContent.append(thinking);
                    }
                    // 保留 thinking signature，供多轮回传（非流式此前会丢失）
                    String signature = contentItem.get("signature").getString();
                    if (Utils.isNotEmpty(signature)) {
                        thinkingSignature = signature;
                    }
                } else if ("text".equals(contentType)) {
                    String text = contentItem.get("text").getString();
                    if (Utils.isNotEmpty(text)) {
                        if (normalContent.length() > 0) {
                            normalContent.append("\n");
                        }
                        normalContent.append(text);
                    }
                } else if ("image".equals(contentType)) {
                    ContentBlock imageBlock = parseClaudeImageBlock(contentItem);
                    if (imageBlock != null) {
                        mediaBlocks.add(imageBlock);
                    }
                } else if ("tool_use".equals(contentType)) {
                    String toolName = contentItem.get("name").getString();
                    String toolId = contentItem.get("id").getString();
                    ONode inputNode = contentItem.get("input");
                    Map<String, Object> arguments = new HashMap<>();
                    if (inputNode != null && inputNode.isObject()) {
                        arguments = inputNode.toBean(Map.class);
                    }
                    
                    allToolCalls.add(new ToolCall(toolId, toolId, toolName, inputNode.toJson(), arguments));
                    
                    Map<String, Object> toolCallRaw = new HashMap<>();
                    toolCallRaw.put("id", toolId);
                    toolCallRaw.put("type", "function");
                    Map<String, Object> functionData = new HashMap<>();
                    functionData.put("name", toolName);
                    functionData.put("arguments", inputNode.toJson());
                    toolCallRaw.put("function", functionData);
                    allToolCallsRaw.add(toolCallRaw);
                }
            }
                
            // 构建文本内容
            String textContent;
            Map<String, Object> contentRaw = null;
            if (thinkingContent.length() > 0 && normalContent.length() > 0) {
                textContent = "<think>\n\n" + thinkingContent.toString() + "</think>\n\n" + normalContent.toString();
                contentRaw = new LinkedHashMap<>();
                contentRaw.put("thinking", thinkingContent.toString());
                if (Utils.isNotEmpty(thinkingSignature)) {
                    contentRaw.put("thinkingSignature", thinkingSignature);
                }
                contentRaw.put("content", normalContent.toString());
            } else if (thinkingContent.length() > 0) {
                textContent = "<think>\n\n" + thinkingContent.toString() + "</think>\n\n";
                contentRaw = new LinkedHashMap<>();
                contentRaw.put("thinking", thinkingContent.toString());
                if (Utils.isNotEmpty(thinkingSignature)) {
                    contentRaw.put("thinkingSignature", thinkingSignature);
                }
            } else if (normalContent.length() > 0) {
                textContent = normalContent.toString();
            } else {
                textContent = "";
            }
            
            List<ContentBlock> blocksForMsg = null;
            if (!mediaBlocks.isEmpty()) {
                blocksForMsg = new ArrayList<>();
                if (Utils.isNotEmpty(textContent)) {
                    // 多模态时用 result 文本投影（不含 think 标签）
                    String textProjection = normalContent.length() > 0 ? normalContent.toString() : textContent;
                    blocksForMsg.add(TextBlock.of(textProjection));
                }
                blocksForMsg.addAll(mediaBlocks);
                resp.addMediaBlocks(mediaBlocks);
            }

            // finishReason：优先用真实 stop_reason；tool 场景兜底 tool_use
            String choiceFinishReason = Utils.isNotEmpty(stopReason)
                    ? stopReason
                    : (!allToolCalls.isEmpty() ? "tool_use" : "stop");
        
            // 将所有工具调用合并到一个 AssistantMessage 中
            if (!allToolCalls.isEmpty()) {
                AssistantMessage msg = new AssistantMessage(textContent,
                        false, contentRaw, allToolCallsRaw, allToolCalls, null, blocksForMsg);
                resp.addChoice(new ChatChoice(0, created, choiceFinishReason, msg));
            } else if (Utils.isNotEmpty(textContent) || blocksForMsg != null) {
                AssistantMessage msg = new AssistantMessage(textContent,
                        false, contentRaw, null, null, null, blocksForMsg);
                resp.addChoice(new ChatChoice(0, created, choiceFinishReason, msg));
            }
        }
        // 同步 lastFinishReason（无 stop_reason 时若已有 tool 选择，归一化侧仍可识别）
        if (Utils.isNotEmpty(stopReason)) {
            resp.lastFinishReason = stopReason;
        } else if (resp.hasChoices() && resp.getMessage() != null
                && Utils.isNotEmpty(resp.getMessage().getToolCalls())) {
            resp.lastFinishReason = "tool_use";
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
     * 解析 Claude content 中的 image 块。
     *
     * @since 3.9
     */
    ContentBlock parseClaudeImageBlock(ONode contentItem) {
        if (contentItem == null) {
            return null;
        }
        ONode source = contentItem.getOrNull("source");
        if (source == null || !source.isObject()) {
            // 兼容直接 url/data
            String url = contentItem.get("url").getString();
            String data = contentItem.get("data").getString();
            if (Utils.isNotEmpty(data)) {
                return ImageBlock.ofBase64(data);
            }
            if (Utils.isNotEmpty(url)) {
                return ImageBlock.ofUrl(url);
            }
            return null;
        }

        String sourceType = source.get("type").getString();
        String mediaType = source.get("media_type").getString();
        if (Utils.isEmpty(mediaType)) {
            mediaType = source.get("mediaType").getString();
        }

        if ("base64".equals(sourceType) || source.hasKey("data")) {
            String data = source.get("data").getString();
            if (Utils.isEmpty(data)) {
                return null;
            }
            return Utils.isEmpty(mediaType) ? ImageBlock.ofBase64(data) : ImageBlock.ofBase64(data, mediaType);
        }

        if ("url".equals(sourceType) || source.hasKey("url")) {
            String url = source.get("url").getString();
            if (Utils.isEmpty(url)) {
                return null;
            }
            return Utils.isEmpty(mediaType) ? ImageBlock.ofUrl(url) : ImageBlock.ofUrl(url, mediaType);
        }

        return null;
    }
}