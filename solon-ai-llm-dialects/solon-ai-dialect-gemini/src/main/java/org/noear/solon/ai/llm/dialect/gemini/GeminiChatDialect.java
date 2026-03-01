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
import org.noear.solon.ai.chat.*;
import org.noear.solon.ai.chat.dialect.AbstractChatDialect;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.tool.ToolCallBuilder;
import org.noear.solon.core.util.Assert;
import org.noear.solon.net.http.HttpUtils;
import org.noear.solon.net.http.impl.HttpSslSupplierAny;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private final GeminiResponseParser responseParser;
    private final GeminiRequestBuilder requestBuilder;

    public static GeminiChatDialect getInstance() {
        return instance;
    }

    public GeminiChatDialect() {
        this.responseParser = new GeminiResponseParser();
        this.requestBuilder = new GeminiRequestBuilder();
    }

    /**
     * 匹配检测
     *
     * @param config 聊天配置
     */
    @Override
    public boolean matched(ChatConfig config) {
        return "gemini".equals(config.getProvider()) ||
                (Assert.isEmpty(config.getProvider()) && config.getApiUrl().contains("/v1beta/models/") && config.getApiUrl().endsWith("generateContent"));
    }

    public HttpUtils createHttpUtils(ChatConfig config, boolean isStream) {
        String apiUrl = buildApiUrl(config.getApiUrl().toString(), config.getModel(), isStream);

        HttpUtils httpUtils = HttpUtils.http(apiUrl)
                .ssl(HttpSslSupplierAny.getInstance())
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

        if (Utils.isNotEmpty(config.getUserAgent())) {
            httpUtils.userAgent(config.getUserAgent());
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
        return responseParser.parseResponse(resp, json);
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
        return requestBuilder.build(config, options, messages, isStream);
    }

    @Override
    public ONode buildAssistantToolCallMessageNode(ChatResponseDefault resp, Map<String, ToolCallBuilder> toolCallBuilders) {
        return requestBuilder.buildAssistantToolCallMessageNode(resp, toolCallBuilders);
    }

    @Override
    public List<AssistantMessage> parseAssistantMessage(ChatResponseDefault resp, ONode oMessage) {
        ONode oParts = oMessage.getOrNull("parts");
        if (oParts != null) {
            GeminiThoughtProcessor thoughtProcessor = new GeminiThoughtProcessor();
            return thoughtProcessor.parse(resp, oMessage);
        } else {
            return super.parseAssistantMessage(resp, oMessage);
        }
    }
}
