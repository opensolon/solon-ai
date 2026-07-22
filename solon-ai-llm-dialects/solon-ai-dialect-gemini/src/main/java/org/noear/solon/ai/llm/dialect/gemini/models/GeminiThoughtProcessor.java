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
package org.noear.solon.ai.llm.dialect.gemini.models;

import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.ChatResponseDefault;
import org.noear.solon.ai.chat.content.AudioBlock;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.content.VideoBlock;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.tool.ToolCall;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gemini 思考内容处理器
 * <p>
 * 负责解析和处理 Gemini API 返回的思考内容（thoughts），
 * 包括流式和非流式两种场景下的思考内容提取。
 *
 * @author cwdhf
 * @since 3.1
 */
public class GeminiThoughtProcessor {

    /**
     * 解析 Gemini 助手消息，处理思考内容和工具调用
     *
     * @param resp     聊天响应
     * @param oContent 消息内容节点
     * @return 解析后的助手消息列表
     */
    public List<AssistantMessage> parse(ChatResponseDefault resp, ONode oContent) {
        List<AssistantMessage> messageList = new ArrayList<>();

        if (oContent == null) {
            return messageList;
        }

        ONode oParts = oContent.getOrNull("parts");
        if (oParts == null || oParts.isArray() == false) {
            oParts = oContent.getOrNull("part");
        }

        if (oParts == null || oParts.isArray() == false) {
            return messageList;
        }

        if (oParts != null && oParts.isArray()) {
            boolean hasThoughtPart = false;
            boolean hasNormalPart = false;
            boolean hasMediaPart = false;
            
            List<ToolCall> toolCalls = new ArrayList<>();
            List<ContentBlock> mediaBlocks = new ArrayList<>();
                
            for (ONode oPart : oParts.getArray()) {
                ONode thoughtNode = oPart.getOrNull("thought");
                boolean isThought = thoughtNode != null && thoughtNode.getBoolean();
                
                ONode functionCallNode = oPart.getOrNull("functionCall");
                if (functionCallNode == null) {
                    functionCallNode = oPart.getOrNull("function_call");
                }
                boolean isFunctionCall = functionCallNode != null && functionCallNode.isObject();
                    
                if (isFunctionCall) {
                    String functionName = functionCallNode.get("name").getString();
                    ONode argsNode = functionCallNode.get("args");
                    if (argsNode == null || argsNode.isNull()) {
                        argsNode = functionCallNode.get("arguments");
                    }
                    String argsJson = argsNode == null ? "{}" : argsNode.toJson();
                    Map<String, Object> argsMap = argsNode == null ? new LinkedHashMap<>() : argsNode.toBean(Map.class);
                            
                    ToolCall toolCall = new ToolCall(functionName, null, functionName, argsJson, argsMap);
                                
                    // 仅第一个 functionCall part 携带 thoughtSignature（并行调用时后续 part 没有）
                    if (toolCalls.isEmpty()) {
                        ONode thoughtSigNode = oPart.getOrNull("thoughtSignature");
                        if (thoughtSigNode == null) {
                            thoughtSigNode = oPart.getOrNull("thought_signature");
                        }
                        if (thoughtSigNode != null) {
                            String thoughtSignature = thoughtSigNode.getString();
                            if (Utils.isNotEmpty(thoughtSignature)) {
                                toolCall.setThoughtSignature(thoughtSignature);
                                resp.thinkingSignature = thoughtSignature;
                            }
                        }
                    }
                    
                    toolCalls.add(toolCall);
                } else if (isThought) {
                    hasThoughtPart = true;
                } else if (oPart.hasKey("text")) {
                    hasNormalPart = true;
                } else {
                    ContentBlock media = parseMediaPart(oPart);
                    if (media != null) {
                        mediaBlocks.add(media);
                        hasMediaPart = true;
                    }
                }
            }
                    
            if (!toolCalls.isEmpty()) {
                if (resp.in_thinking && resp.isStream()) {
                    messageList.add(new AssistantMessage("</think>", true));
                }
                resp.in_thinking = false;
                        
                List<ContentBlock> blocksForMsg = null;
                if (!mediaBlocks.isEmpty()) {
                    blocksForMsg = new ArrayList<>(mediaBlocks);
                    resp.addMediaBlocks(mediaBlocks);
                }
                AssistantMessage msg = new AssistantMessage("", false, null, null, toolCalls, null, blocksForMsg);
                messageList.add(msg);
                return messageList;
            }
                        
            if (resp.isStream()) {
                if (hasThoughtPart && !hasNormalPart && !hasMediaPart) {
                    if (!resp.in_thinking) {
                        messageList.add(new AssistantMessage("\n\n", true));
                        resp.in_thinking = true;
                    }
                        
                    for (ONode oPart : oParts.getArray()) {
                        ONode thoughtNode = oPart.getOrNull("thought");
                        boolean isThought = thoughtNode != null && thoughtNode.getBoolean();
                            
                        if (isThought) {
                            String text = oPart.get("text").getString();
                            if (Utils.isNotEmpty(text)) {
                                messageList.add(new AssistantMessage(text, true));
                            }
                        }
                    }
                } else if (!hasThoughtPart && (hasNormalPart || hasMediaPart)) {
                    if (resp.in_thinking) {
                        messageList.add(new AssistantMessage("</think>", true));
                        resp.in_thinking = false;
                    }

                    // 有媒体时：合并为单条消息（文本 + media blocks），避免文本先 delta 再整段重复
                    // 无媒体时：保持旧行为，按 part 增量推送文本
                    StringBuilder normalContent = new StringBuilder();
                    for (ONode oPart : oParts.getArray()) {
                        ONode thoughtNode = oPart.getOrNull("thought");
                        boolean isThought = thoughtNode != null && thoughtNode.getBoolean();

                        if (!isThought && oPart.hasKey("text")) {
                            String text = oPart.get("text").getString();
                            if (Utils.isNotEmpty(text)) {
                                normalContent.append(text);
                                if (mediaBlocks.isEmpty()) {
                                    messageList.add(new AssistantMessage(text, false));
                                }
                            }
                        }
                    }

                    if (!mediaBlocks.isEmpty()) {
                        resp.addMediaBlocks(mediaBlocks);
                        List<ContentBlock> blocks = new ArrayList<>();
                        if (normalContent.length() > 0) {
                            blocks.add(TextBlock.of(normalContent.toString()));
                        }
                        blocks.addAll(mediaBlocks);
                        messageList.add(new AssistantMessage(normalContent.toString(), false, null, null, null, null, blocks));
                    }
                } else if (hasThoughtPart && (hasNormalPart || hasMediaPart)) {
                    if (!resp.in_thinking) {
                        messageList.add(new AssistantMessage("\n\n", true));
                    }

                    for (ONode oPart : oParts.getArray()) {
                        ONode thoughtNode = oPart.getOrNull("thought");
                        boolean isThought = thoughtNode != null && thoughtNode.getBoolean();

                        if (isThought) {
                            String text = oPart.get("text").getString();
                            if (Utils.isNotEmpty(text)) {
                                messageList.add(new AssistantMessage(text, true));
                            }
                        }
                    }

                    messageList.add(new AssistantMessage("</think>", true));
                    resp.in_thinking = false;

                    // 有媒体时：合并为单条消息；无媒体时按 part 增量推送
                    StringBuilder normalContent = new StringBuilder();
                    for (ONode oPart : oParts.getArray()) {
                        ONode thoughtNode = oPart.getOrNull("thought");
                        boolean isThought = thoughtNode != null && thoughtNode.getBoolean();

                        if (!isThought && oPart.hasKey("text")) {
                            String text = oPart.get("text").getString();
                            if (Utils.isNotEmpty(text)) {
                                normalContent.append(text);
                                if (mediaBlocks.isEmpty()) {
                                    messageList.add(new AssistantMessage(text, false));
                                }
                            }
                        }
                    }

                    if (!mediaBlocks.isEmpty()) {
                        resp.addMediaBlocks(mediaBlocks);
                        List<ContentBlock> blocks = new ArrayList<>();
                        if (normalContent.length() > 0) {
                            blocks.add(TextBlock.of(normalContent.toString()));
                        }
                        blocks.addAll(mediaBlocks);
                        messageList.add(new AssistantMessage(normalContent.toString(), false, null, null, null, null, blocks));
                    }
                }
            } else {
                StringBuilder thoughtContent = new StringBuilder();
                StringBuilder normalContent = new StringBuilder();
        
                for (ONode oPart : oParts.getArray()) {
                    ONode thoughtNode = oPart.getOrNull("thought");
                    boolean isThought = thoughtNode != null && thoughtNode.getBoolean();
    
                    if (oPart.hasKey("text")) {
                        String text = oPart.get("text").getString();
                        if (Utils.isNotEmpty(text)) {
                            if (isThought) {
                                if (thoughtContent.length() > 0) {
                                    thoughtContent.append("\n");
                                }
                                thoughtContent.append(text);
                            } else {
                                if (normalContent.length() > 0) {
                                    normalContent.append("\n");
                                }
                                normalContent.append(text);
                            }
                        }
                    }
                }
    
                List<ContentBlock> blocksForMsg = null;
                if (!mediaBlocks.isEmpty()) {
                    blocksForMsg = new ArrayList<>();
                    if (normalContent.length() > 0) {
                        blocksForMsg.add(TextBlock.of(normalContent.toString()));
                    }
                    blocksForMsg.addAll(mediaBlocks);
                    resp.addMediaBlocks(mediaBlocks);
                }
    
                if (thoughtContent.length() > 0 && normalContent.length() > 0) {
                    String cleanedThought = cleanThoughtContent(thoughtContent.toString());
    
                    String fullContent = "\n\n" + cleanedThought + "\n\n" + normalContent.toString();
    
                    Map<String, Object> contentRaw = new LinkedHashMap<>();
                    contentRaw.put("thought", cleanedThought);
                    contentRaw.put("content", normalContent.toString());
    
                    messageList.add(new AssistantMessage(fullContent, false, contentRaw, null, null, null, blocksForMsg));
                } else if (thoughtContent.length() > 0) {
                    String cleanedThought = cleanThoughtContent(thoughtContent.toString());
    
                    String fullContent = "\n\n" + cleanedThought + "\n\n";
    
                    Map<String, Object> contentRaw = new LinkedHashMap<>();
                    contentRaw.put("thought", cleanedThought);
    
                    messageList.add(new AssistantMessage(fullContent, false, contentRaw, null, null, null, blocksForMsg));
                } else if (normalContent.length() > 0 || blocksForMsg != null) {
                    messageList.add(new AssistantMessage(normalContent.toString(), false, null, null, null, null, blocksForMsg));
                }
            }
        }
    
        return messageList;
    }
    
    /**
     * 解析 Gemini part 中的媒体（inline_data / file_data，兼容 camelCase）。
     *
     * @since 3.9
     */
    private ContentBlock parseMediaPart(ONode oPart) {
        if (oPart == null || !oPart.isObject()) {
            return null;
        }
    
        ONode inline = oPart.getOrNull("inline_data");
        if (inline == null) {
            inline = oPart.getOrNull("inlineData");
        }
        if (inline != null && inline.isObject()) {
            String mime = inline.get("mime_type").getString();
            if (Utils.isEmpty(mime)) {
                mime = inline.get("mimeType").getString();
            }
            String data = inline.get("data").getString();
            return createMediaByMime(mime, null, data);
        }
    
        ONode fileData = oPart.getOrNull("file_data");
        if (fileData == null) {
            fileData = oPart.getOrNull("fileData");
        }
        if (fileData != null && fileData.isObject()) {
            String mime = fileData.get("mime_type").getString();
            if (Utils.isEmpty(mime)) {
                mime = fileData.get("mimeType").getString();
            }
            String uri = fileData.get("file_uri").getString();
            if (Utils.isEmpty(uri)) {
                uri = fileData.get("fileUri").getString();
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
            } else if (lower.startsWith("image/")) {
                mediaType = "image";
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
     * 清理思考内容，移除不需要的 markdown 格式
     * <p>
     * Gemini API 返回的思考内容可能包含以下不需要的格式：
     * <ul>
     *   <li>以 "**" 开头和结尾的标题行（如 "**思考过程**"）</li>
     *   <li>纯 "**" 行</li>
     * </ul>
     * <p>
     * 此方法逐行处理内容，识别并跳过这些格式的行，
     * 保留其他所有内容以维持原始思考过程的完整性。
     *
     * @param content 原始思考内容
     * @return 清理后的思考内容，移除了不需要的 markdown 格式
     */
    public String cleanThoughtContent(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }

        String[] lines = content.split("\n");
        StringBuilder cleaned = new StringBuilder();

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("**") && trimmed.endsWith("**")) {
                continue;
            }
            if (trimmed.equals("**")) {
                continue;
            }

            if (cleaned.length() > 0) {
                cleaned.append("\n");
            }
            cleaned.append(line);
        }

        return cleaned.toString();
    }
}
