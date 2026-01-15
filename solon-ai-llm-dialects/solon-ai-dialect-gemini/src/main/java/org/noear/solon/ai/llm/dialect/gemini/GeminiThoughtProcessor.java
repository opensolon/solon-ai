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
package org.noear.solon.ai.llm.dialect.gemini;

import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.ChatResponseDefault;
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

            List<ToolCall> toolCalls = new ArrayList<>();

            for (ONode oPart : oParts.getArray()) {
                ONode thoughtNode = oPart.getOrNull("thought");
                boolean isThought = thoughtNode != null && thoughtNode.getBoolean();

                ONode functionCallNode = oPart.getOrNull("functionCall");
                boolean isFunctionCall = functionCallNode != null && functionCallNode.isObject();

                if (isFunctionCall) {
                    String functionName = functionCallNode.get("name").getString();
                    ONode argsNode = functionCallNode.get("args");
                    String argsJson = argsNode.toJson();
                    Map<String, Object> argsMap = argsNode.toBean(Map.class);

                    toolCalls.add(new ToolCall(functionName, null, functionName, argsJson, argsMap));
                } else if (isThought) {
                    hasThoughtPart = true;
                } else if (oPart.hasKey("text")) {
                    hasNormalPart = true;
                }
            }

            if (!toolCalls.isEmpty()) {
                if (resp.in_thinking && resp.isStream()) {
                    messageList.add(new AssistantMessage("</think>", true));
                    messageList.add(new AssistantMessage("\n\n", false));
                }
                resp.in_thinking = false;

                AssistantMessage msg = new AssistantMessage("", false, null, null, toolCalls, null);
                messageList.add(msg);
                return messageList;
            }

            if (resp.isStream()) {
                if (hasThoughtPart && !hasNormalPart) {
                    if (!resp.in_thinking) {
                        messageList.add(new AssistantMessage("\n\n", true));
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
                } else if (!hasThoughtPart && hasNormalPart) {
                    if (resp.in_thinking) {
                        messageList.add(new AssistantMessage("</think>", true));
                        messageList.add(new AssistantMessage("\n\n", false));
                        resp.in_thinking = false;
                    }

                    for (ONode oPart : oParts.getArray()) {
                        ONode thoughtNode = oPart.getOrNull("thought");
                        boolean isThought = thoughtNode != null && thoughtNode.getBoolean();

                        if (!isThought && oPart.hasKey("text")) {
                            String text = oPart.get("text").getString();
                            if (Utils.isNotEmpty(text)) {
                                messageList.add(new AssistantMessage(text, false));
                            }
                        }
                    }
                } else if (hasThoughtPart && hasNormalPart) {
                    if (!resp.in_thinking) {
                        messageList.add(new AssistantMessage("\n\n", true));
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
                    messageList.add(new AssistantMessage("\n\n", false));
                    resp.in_thinking = false;

                    for (ONode oPart : oParts.getArray()) {
                        ONode thoughtNode = oPart.getOrNull("thought");
                        boolean isThought = thoughtNode != null && thoughtNode.getBoolean();

                        if (!isThought && oPart.hasKey("text")) {
                            String text = oPart.get("text").getString();
                            if (Utils.isNotEmpty(text)) {
                                messageList.add(new AssistantMessage(text, false));
                            }
                        }
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

                if (thoughtContent.length() > 0 && normalContent.length() > 0) {
                    String cleanedThought = cleanThoughtContent(thoughtContent.toString());

                    String fullContent = "\n\n" + cleanedThought + "\n\n" + normalContent.toString();

                    Map<String, Object> contentRaw = new LinkedHashMap<>();
                    contentRaw.put("thought", cleanedThought);
                    contentRaw.put("content", normalContent.toString());

                    messageList.add(new AssistantMessage(fullContent, false, contentRaw, null, null, null));
                } else if (thoughtContent.length() > 0) {
                    String cleanedThought = cleanThoughtContent(thoughtContent.toString());

                    String fullContent = "\n\n" + cleanedThought + "\n\n";

                    Map<String, Object> contentRaw = new LinkedHashMap<>();
                    contentRaw.put("thought", cleanedThought);

                    messageList.add(new AssistantMessage(fullContent, false, contentRaw, null, null, null));
                } else if (normalContent.length() > 0) {
                    messageList.add(new AssistantMessage(normalContent.toString()));
                }
            }
        }

        return messageList;
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
