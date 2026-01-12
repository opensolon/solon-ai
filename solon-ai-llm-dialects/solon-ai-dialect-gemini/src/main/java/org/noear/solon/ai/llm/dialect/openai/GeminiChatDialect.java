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
import org.noear.solon.ai.chat.*;
import org.noear.solon.ai.chat.dialect.AbstractChatDialect;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.net.http.HttpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gemini 聊天模型方言
 * <p>
 * 此类实现了与 Google Gemini API 的集成，提供聊天补全功能。
 * 主要职责包括：
 * <ul>
 *   <li>构建符合 Gemini API 规范的请求 JSON</li>
 *   <li>处理流式和非流式两种响应模式</li>
 *   <li>解析 Gemini 特有的思考内容（thoughts）格式</li>
 *   <li>处理配置参数的自动类型转换（YAML 读取的字符串转数值类型）</li>
 * </ul>
 * <p>
 * Gemini API 与 OpenAI API 的主要差异：
 * <ul>
 *   <li>URL 格式：使用 /models/{model}:generateContent 或 :streamGenerateContent</li>
 *   <li>认证方式：使用 x-goog-api-key 请求头而非 Bearer Token</li>
 *   <li>思考内容：支持将思考过程作为响应的一部分返回</li>
 * </ul>
 *
 * @author cwdhf
 * @since 3.1
 */
public class GeminiChatDialect extends AbstractChatDialect {
    private static final GeminiChatDialect instance = new GeminiChatDialect();
    private static final Logger log = LoggerFactory.getLogger(GeminiChatDialect.class);

    public static GeminiChatDialect getInstance() {
        return instance;
    }

    /**
     * 匹配检测
     *
     * @param config 聊天配置
     */
    @Override
    public boolean matched(ChatConfig config) {
        return "gemini".equals(config.getProvider());
    }

    @Override
    public HttpUtils createHttpUtils(ChatConfig config) {
        return createHttpUtils(config, false);
    }

    public HttpUtils createHttpUtils(ChatConfig config, boolean isStream) {
        String apiUrl = buildApiUrl(config.getApiUrl().toString(), config.getModel(), isStream);

        HttpUtils httpUtils = HttpUtils.http(apiUrl)
                .timeout((int) config.getTimeout().getSeconds());

        if (config.getProxy() != null) {
            httpUtils.proxy(config.getProxy());
        }

        if (Utils.isNotEmpty(config.getApiKey())) {
            httpUtils.header("x-goog-api-key", config.getApiKey());
        }

        if (isStream) {
            httpUtils.header("Accept", "text/event-stream");
        }

        httpUtils.headers(config.getHeaders());

        return httpUtils;
    }

    /**
     * 构建 Gemini API 请求 URL
     * <p>
     * Gemini API 的 URL 格式为：{baseUrl}/models/{model}:{endpoint}
     * 根据 isStream 参数决定使用流式生成（:streamGenerateContent）或非流式生成（:generateContent）
     * <p>
     * URL 构造规则：
     * <ul>
     *   <li>移除末尾的 "/" 以避免重复</li>
     *   <li>追加 "/models/" 和模型名称</li>
     *   <li>追加端点后缀，流式模式添加 ?alt=sse 参数以支持 Server-Sent Events</li>
     * </ul>
     *
     * @param baseUrl  基础 URL 地址
     * @param model    模型名称
     * @param isStream 是否使用流式模式
     * @return 完整的 API 请求 URL
     */
    private String buildApiUrl(String baseUrl, String model, boolean isStream) {
        String normalizedUrl = baseUrl;
        if (normalizedUrl.endsWith("/")) {
            normalizedUrl = normalizedUrl.substring(0, normalizedUrl.length() - 1);
        }

        StringBuilder urlBuilder = new StringBuilder(normalizedUrl);

        if (!urlBuilder.toString().endsWith("/")) {
            urlBuilder.append("/");
        }

        urlBuilder.append("models/");
        urlBuilder.append(model);

        String endpoint = isStream ? ":streamGenerateContent" : ":generateContent";
        urlBuilder.append(endpoint);

        if (isStream) {
            urlBuilder.append("?alt=sse");
        }

        return urlBuilder.toString();
    }

    @Override
    public boolean parseResponseJson(ChatConfig config, ChatResponseDefault resp, String json) {
        if (resp.isStream()) {
            return parseStreamResponse(resp, json);
        } else {
            return parseNonStreamResponse(resp, json);
        }
    }

    private boolean parseStreamResponse(ChatResponseDefault resp, String json) {
        if (json == null || json.isEmpty()) {
            return false;
        }

        if (log.isDebugEnabled()) {
            log.debug("Gemini stream raw response: {}", json);
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


            if (oResp.hasKey("error")) {
                ONode oError = oResp.get("error");
                String errorMsg = oError.get("message").getString();
                if (Utils.isEmpty(errorMsg)) {
                    errorMsg = oError.getString();
                }
                resp.setError(new ChatException(errorMsg));
                return true;
            }

            if (oResp.hasKey("model")) {
                resp.setModel(oResp.get("model").getString());
            } else if (oResp.hasKey("modelVersion")) {
                resp.setModel(oResp.get("modelVersion").getString());
            }

            Date created = new Date();
            if (oResp.hasKey("createTime")) {
                String createTime = oResp.get("createTime").getString();
                if (createTime != null && createTime.length() >= 19) {
                    try {
                        created = java.sql.Timestamp.valueOf(createTime.replace("T", " ").substring(0, 19));
                    } catch (Exception e) {
                    }
                }
            }

            ONode oCandidates = oResp.getOrNull("candidates");
            if (oCandidates != null && oCandidates.isArray()) {
                for (ONode oChoice1 : oCandidates.getArray()) {
                    int index = oChoice1.get("index").getInt();
                    String finishReason = oChoice1.get("finishReason").getString();

                    if (Utils.isNotEmpty(finishReason)) {
                        resp.setFinished(true);
                    }

                    ONode oContent = oChoice1.get("content");
                    List<AssistantMessage> messageList = parseGeminiAssistantMessage(resp, oContent);

                    for (AssistantMessage msg1 : messageList) {
                        resp.addChoice(new ChatChoice(index, created, finishReason, msg1));
                        hasChoices = true;
                    }
                }
            }

            ONode oUsage = oResp.getOrNull("usageMetadata");
            if (oUsage != null && resp.isFinished()) {
                long promptTokens = oUsage.getOrNull("promptTokenCount") != null ? oUsage.get("promptTokenCount").getLong() : 0;
                long completionTokens = oUsage.getOrNull("candidatesTokenCount") != null ? oUsage.get("candidatesTokenCount").getLong() : 0;
                long totalTokens = oUsage.getOrNull("totalTokenCount") != null ? oUsage.get("totalTokenCount").getLong() : 0;

                resp.setUsage(new AiUsage(promptTokens, completionTokens, totalTokens, oUsage));
            }
        }

        return hasChoices;
    }

    private boolean parseNonStreamResponse(ChatResponseDefault resp, String json) {
        if ("[DONE]".equals(json)) {
            if(resp.isFinished() == false) {
                resp.addChoice(new ChatChoice(0, new Date(), "stop", new AssistantMessage("")));
                resp.setFinished(true);
            }
            return true;
        }

        ONode oResp = ONode.ofJson(json);

        if (oResp.isObject() == false) {
            return false;
        }

        if (oResp.hasKey("error")) {
            ONode oError = oResp.get("error");
            String errorMsg = oError.get("message").getString();
            if (Utils.isEmpty(errorMsg)) {
                errorMsg = oError.getString();
            }
            resp.setError(new ChatException(errorMsg));
            return true;
        }

        if (oResp.hasKey("model")) {
            resp.setModel(oResp.get("model").getString());
        } else if (oResp.hasKey("modelVersion")) {
            resp.setModel(oResp.get("modelVersion").getString());
        }

        Date created = new Date();
        if (oResp.hasKey("created")) {
            created = new Date(oResp.get("created").getLong() * 1000);
        }

        ONode oCandidates = oResp.getOrNull("candidates");
        if (oCandidates != null && oCandidates.isArray()) {

            for (ONode oChoice1 : oCandidates.getArray()) {
                int index = oChoice1.get("index").getInt();
                String finishReason = oChoice1.get("finishReason").getString();

                if (Utils.isEmpty(finishReason)) {
                    finishReason = oChoice1.get("finish_reason").getString();
                }

                ONode oContent = oChoice1.get("content");
                List<AssistantMessage> messageList = parseGeminiAssistantMessage(resp, oContent);

                for (AssistantMessage msg1 : messageList) {
                    resp.addChoice(new ChatChoice(index, created, finishReason, msg1));
                }

                if (Utils.isNotEmpty(finishReason)) {
                    resp.setFinished(true);
                }
            }
        }

        if (resp.isFinished()) {
            if (resp.hasChoices() == false) {
                resp.addChoice(new ChatChoice(0, created, "stop", new AssistantMessage("")));
            }
        }

        ONode oUsage = oResp.getOrNull("usageMetadata");
        if (oUsage != null) {
            long promptTokens = oUsage.get("promptTokenCount").getLong();
            long completionTokens = oUsage.get("candidatesTokenCount").getLong();
            long totalTokens = oUsage.get("totalTokenCount").getLong();

            resp.setUsage(new AiUsage(promptTokens, completionTokens, totalTokens, oUsage));
        }

        return true;
    }

    /**
     * 构建符合 Gemini API 规范的请求 JSON
     * <p>
     * 主要处理逻辑：
     * <ul>
     *   <li>构建 contents 数组，包含对话历史</li>
     *   <li>处理 generationConfig 配置，特别是类型转换</li>
     * </ul>
     * <p>
     * <b>类型转换说明：</b>由于 YAML 配置文件读取的值都是字符串，
     * 需要在此处进行类型转换以符合 Gemini API 的要求：
     * <ul>
     *   <li>temperature 和 topP 转换为 Double 类型（范围 0-1 的小数）</li>
     *   <li>thinkingBudget 转换为 Integer 类型（思考token预算）</li>
     *   <li>thinkingConfig 中的 includeThoughts 转换为 Boolean 类型</li>
     * </ul>
     *
     * @param config   聊天配置
     * @param options  聊天选项
     * @param messages 对话消息列表
     * @param isStream 是否使用流式模式
     * @return 符合 Gemini API 规范的 JSON 字符串
     */
    @Override
    public String buildRequestJson(ChatConfig config, ChatOptions options, List<ChatMessage> messages, boolean isStream) {
        ONode root = new ONode();

        if (Utils.isNotEmpty(config.getModel())) {
            root.set("model", config.getModel());
        }

        ONode contentsNode = root.getOrNew("contents").asArray();
        for (ChatMessage m1 : messages) {
            if (m1.isThinking() == false) {
                contentsNode.add(buildGeminiMessageNode(m1));
            }
        }

        for (Map.Entry<String, Object> kv : options.options().entrySet()) {
            if ("stream".equals(kv.getKey())) {
                continue;
            }

            String key = kv.getKey();
            Object value = kv.getValue();

            if ("generationConfig".equals(key) && value instanceof Map) {
                Map<?, ?> genConfig = (Map<?, ?>) value;
                ONode genConfigNode = root.getOrNew("generationConfig");
                
                for (Map.Entry<?, ?> genKv : genConfig.entrySet()) {
                    String genKey = genKv.getKey().toString();
                    Object genValue = genKv.getValue();
                    
                    if ("temperature".equals(genKey) || "topP".equals(genKey)) {
                        if (genValue instanceof String) {
                            genValue = Double.parseDouble((String) genValue);
                        }
                        genConfigNode.set(genKey, genValue);
                    } else if ("thinkingBudget".equals(genKey)) {
                        if (genValue instanceof String) {
                            genValue = Integer.parseInt((String) genValue);
                        }
                        genConfigNode.set(genKey, genValue);
                    } else if ("thinkingConfig".equals(genKey) && genValue instanceof Map) {
                         ONode thinkingConfigNode = genConfigNode.getOrNew("thinkingConfig");
                         Map<?, ?> thinkingConfig = (Map<?, ?>) genValue;
                         for (Map.Entry<?, ?> tcKv : thinkingConfig.entrySet()) {
                             String tcKey = tcKv.getKey().toString();
                             Object tcValue = tcKv.getValue();
                             if ("includeThoughts".equals(tcKey)) {
                                 if (tcValue instanceof String) {
                                     tcValue = Boolean.parseBoolean((String) tcValue);
                                 }
                             } else if ("thinkingBudget".equals(tcKey)) {
                                 if (tcValue instanceof String) {
                                     tcValue = Integer.parseInt((String) tcValue);
                                 }
                             }
                             thinkingConfigNode.set(tcKey, tcValue);
                         }
                    } else {
                        genConfigNode.set(genKey, ONode.ofBean(genValue));
                    }
                }
            } else {
                root.set(key, ONode.ofBean(value));
            }
        }

        return root.toJson();
    }

    private ONode buildGeminiMessageNode(ChatMessage message) {
        ONode node = new ONode();
        
        ChatRole role = message.getRole();
        String roleStr = "user";
        if (role != null) {
            roleStr = role.toString();
        } else if ("system".equals(role.toString())) {
            roleStr = "user";
        }
        
        node.set("role", roleStr);
        
        String content = message.getContent();
        if (content != null) {
            node.getOrNew("parts").asArray().addNew().set("text", content);
        }
        
        return node;
    }

    /**
     * 解析 Gemini 响应中的 Assistant 消息
     * <p>
     * Gemini API 的响应包含两种类型的内容：
     * <ul>
     *   <li><b>思考内容（thought）</b>：模型的推理过程，使用 "**<text>**" 格式标记</li>
     *   <li><b>普通内容</b>：最终的回答文本</li>
     * </ul>
     * <p>
     * <b>流式处理 vs 非流式处理：</b>
     * <ul>
     *   <li><b>流式处理</b>：逐块接收数据，需要维护 in_thinking 状态来追踪思考是否开始/结束。
     *       根据内容类型分别发送不同类型的 AssistantMessage（isThinking=true/false），
     *       并在适当位置插入 "**<text>**" 和 "**<text>**" 标记</li>
     *   <li><b>非流式处理</b>：一次性接收完整响应，分别收集思考内容和普通内容，
     *       使用 cleanThoughtContent 清理不需要的 markdown 格式，最后组装成一条消息</li>
     * </ul>
     *
     * @param resp     聊天响应对象，用于维护流式处理状态
     * @param oContent Gemini 响应的内容节点
     * @return 解析后的 AssistantMessage 列表
     */
    private List<AssistantMessage> parseGeminiAssistantMessage(ChatResponseDefault resp, ONode oContent) {
        List<AssistantMessage> messageList = new ArrayList<>();

        if (oContent == null) {
            return messageList;
        }

        ONode oParts = oContent.getOrNull("parts");
        if (oParts == null || oParts.isArray() == false) {
            oParts = oContent.getOrNull("part");
        }

        if (oParts != null && oParts.isArray()) {
            boolean hasThoughtPart = false;
            boolean hasNormalPart = false;

            // 先检查这个chunk中有什么类型的内容
            for (ONode oPart : oParts.getArray()) {
                ONode thoughtNode = oPart.getOrNull("thought");
                boolean isThought = thoughtNode != null && thoughtNode.getBoolean();
                
                if (isThought) {
                    hasThoughtPart = true;
                } else if (oPart.hasKey("text")) {
                    hasNormalPart = true;
                }
            }

            // 根据 AbstractChatDialect 的逻辑处理
            if (resp.isStream()) {
                // 流式处理
                if (hasThoughtPart && !hasNormalPart) {
                    // 只有思考内容
                    if (!resp.in_thinking) {
                        // 第一次进入思考模式,添加开始标记
                        messageList.add(new AssistantMessage("<think>", true));
                        messageList.add(new AssistantMessage("\n\n", true));
                        resp.in_thinking = true;
                    }
                    
                    // 添加思考内容
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
                    // 只有普通内容
                    if (resp.in_thinking) {
                        // 思考结束,添加结束标记
                        messageList.add(new AssistantMessage("</think>", true));
                        messageList.add(new AssistantMessage("\n\n", false));
                        resp.in_thinking = false;
                    }
                    
                    // 添加普通内容
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
                    // 同时包含思考和普通内容(思考结束的chunk)
                    if (!resp.in_thinking) {
                        // 先添加思考开始标记
                        messageList.add(new AssistantMessage("<think>", true));
                        messageList.add(new AssistantMessage("\n\n", true));
                    }
                    
                    // 添加思考内容
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
                    
                    // 添加思考结束标记
                    messageList.add(new AssistantMessage("</think>", true));
                    messageList.add(new AssistantMessage("\n\n", false));
                    resp.in_thinking = false;
                    
                    // 添加普通内容
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
                // 非流式处理 - 一次性返回所有内容
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
                
                // 组装完整的内容
                if (thoughtContent.length() > 0 && normalContent.length() > 0) {
                    // 清理思考内容，移除 "**" 开头的标题行
                    String cleanedThought = cleanThoughtContent(thoughtContent.toString());
                    
                    String fullContent = "<think>\n\n" + cleanedThought + "</think>\n\n" + normalContent.toString();
                    
                    // 创建原始数据结构
                    Map<String, Object> contentRaw = new LinkedHashMap<>();
                    contentRaw.put("thought", cleanedThought);
                    contentRaw.put("content", normalContent.toString());
                    
                    messageList.add(new AssistantMessage(fullContent, false, contentRaw, null, null, null));
                } else if (thoughtContent.length() > 0) {
                    // 清理思考内容，移除 "**" 开头的标题行
                    String cleanedThought = cleanThoughtContent(thoughtContent.toString());
                    
                    String fullContent = "<think>\n\n" + cleanedThought + "</think>\n\n";
                    
                    // 创建原始数据结构
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
    private String cleanThoughtContent(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        
        String[] lines = content.split("\n");
        StringBuilder cleaned = new StringBuilder();
        
        for (String line : lines) {
            String trimmed = line.trim();
            // 跳过 "**" 开头的标题行
            if (trimmed.startsWith("**") && trimmed.endsWith("**")) {
                continue;
            }
            // 跳过纯 "**" 行
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