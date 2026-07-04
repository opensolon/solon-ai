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
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.ChatResponseDefault;
import org.noear.solon.ai.chat.dialect.AbstractChatDialect;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.chat.tool.ToolCallBuilder;
import org.noear.solon.ai.llm.dialect.gemini.interactions.GeminiInteractionsRequestBuilder;
import org.noear.solon.ai.llm.dialect.gemini.interactions.GeminiInteractionsResponseParser;
import org.noear.solon.core.util.Assert;
import org.noear.solon.net.http.HttpUtils;
import org.noear.solon.net.http.impl.HttpSslSupplierAny;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Gemini Interactions API 方言
 * <p>
 * 适配 Google Gemini Interactions API（端点 /v1beta/interactions 或 /v1/interactions）。
 * 这是 Gemini 的新一代接口协议，使用 step 序列替代传统的 generateContent 格式。
 * 支持流式 SSE 事件（step.start/step.delta/step.stop）和非流式响应。
 * <p>
 * 兼容的 URL 格式：
 * <ul>
 *   <li>https://generativelanguage.googleapis.com/v1beta/interactions</li>
 *   <li>https://generativelanguage.googleapis.com/v1/interactions</li>
 *   <li>https://generativelanguage.googleapis.com/v1beta（自动补全 /interactions）</li>
 *   <li>https://generativelanguage.googleapis.com/v1（自动补全 /interactions）</li>
 * </ul>
 * <p>
 * 认证方式：x-goog-api-key（与 Generate Content API 相同）
 *
 * @since 3.1
 */
public class GeminiInteractionsDialect extends AbstractChatDialect {
    private static final GeminiInteractionsDialect instance = new GeminiInteractionsDialect();
    private static final Logger log = LoggerFactory.getLogger(GeminiInteractionsDialect.class);

    private final GeminiInteractionsResponseParser responseParser;
    private final GeminiInteractionsRequestBuilder requestBuilder;
    private static final Pattern pattern =  Pattern.compile("/v\\d+(beta)?/?");

    public static GeminiInteractionsDialect getInstance() {
        return instance;
    }

    public GeminiInteractionsDialect() {
        this.responseParser = new GeminiInteractionsResponseParser();
        this.requestBuilder = new GeminiInteractionsRequestBuilder();
    }

    /**
     * 匹配检测
     * <p>
     * 匹配规则（按优先级）：
     * <ol>
     *   <li>standard/provider 为 "gemini-interactions"</li>
     *   <li>URL 包含 "/interactions" 且不包含 "generateContent"（自动检测）</li>
     * </ol>
     */
    @Override
    public boolean matched(ChatConfig config) {
        String standard = config.getStandardOrProvider();

        if ("gemini-interactions".equalsIgnoreCase(standard) ||
                "google".equalsIgnoreCase(standard)) {
            return true;
        }

        if (Assert.isEmpty(standard)) {
            if (config.getApiUrl().endsWith("/interactions") ) {
                return true;
            }
        }

        return false;
    }

    @Override
    protected String getApiUrl(ChatConfig config) {
        //处理后缀#
        int index = config.getApiUrl().indexOf('#');
        if (index > 0) {
            return config.getApiUrl().substring(0, index);
        }

        //自动补全地址
        if (config.getApiUrl().endsWith("/interactions?alt=sse") || config.getApiUrl().endsWith("/interactions")) {
            return config.getApiUrl();
        } else {
            if (pattern.matcher(config.getApiUrl()).find()) { //匹配 /v1,/v4/ 等
                //已带版本
                if (config.getApiUrl().endsWith("/")) {
                    return config.getApiUrl() + "interactions";
                } else {
                    return config.getApiUrl() + "/interactions";
                }
            } else {
                //未带版本
                if (config.getApiUrl().endsWith("/")) {
                    return config.getApiUrl() + "v1/interactions";
                } else {
                    return config.getApiUrl() + "/v1/interactions";
                }
            }
        }
    }

    @Override
    public HttpUtils createHttpUtils(ChatConfig config, boolean isStream) {
        String apiUrl = getApiUrl(config);
        if (isStream && apiUrl.contains("?") == false) {
            apiUrl += "?alt=sse";
        }

        HttpUtils httpUtils = HttpUtils.http(apiUrl)
                .ssl(HttpSslSupplierAny.getInstance())
                .timeout((int) config.getTimeout().getSeconds());

        if (config.getProxy() != null) {
            httpUtils.proxy(config.getProxy());
        }

        // Interactions API 使用 x-goog-api-key 认证
        if (Utils.isNotEmpty(config.getApiKey())) {
            httpUtils.header("x-goog-api-key", config.getApiKey());
        }

        if (isStream) {
            httpUtils.header("Accept", "text/event-stream");
            // Interactions API 流式模式需要 ?alt=sse
            // 但已在 URL 中处理
        }

        if (Utils.isNotEmpty(config.getUserAgent())) {
            httpUtils.userAgent(config.getUserAgent());
        }

        httpUtils.headers(config.getHeaders());

        return httpUtils;
    }

    @Override
    public void prepareOutputFormatOptions(ChatOptions options) {
        // Interactions API 使用 response_format 数组而非 response_mime_type
        // 默认不设置，由 buildResponseFormatNode 在需要时添加
    }

    @Override
    public ONode buildRequestJson(ChatConfig config, ChatOptions options,
                                   List<ChatMessage> messages, boolean isStream) {
        return requestBuilder.build(config, options, messages, isStream);
    }

    @Override
    public boolean parseResponseJson(ChatConfig config, ChatResponseDefault resp, String json) {
        return responseParser.parseResponse(resp, json);
    }

    @Override
    public ONode buildAssistantToolCallMessageNode(ChatResponseDefault resp,
                                                    Map<String, ToolCallBuilder> toolCallBuilders) {
        return requestBuilder.buildAssistantToolCallMessageNode(resp, toolCallBuilders);
    }

    @Override
    public List<AssistantMessage> parseAssistantMessage(ChatResponseDefault resp, ONode oMessage) {
        // 处理 steps 数组格式（来自 buildAssistantToolCallMessageNode）
        // 格式: [{type:"function_call", name:"...", id:"...", arguments:{...}}, ...]
        if (oMessage != null && oMessage.isArray()) {
            List<ToolCall> toolCalls = new ArrayList<>();
            int idx = 0;
            for (ONode step : oMessage.getArray()) {
                String type = step.get("type").getString();
                if ("function_call".equals(type)) {
                    String name = step.get("name").getString();
                    String callId = step.get("id").getString();
                    if (Utils.isEmpty(callId)) {
                        callId = name + "_" + System.currentTimeMillis();
                    }

                    // 解析 arguments
                    ONode argsNode = step.getOrNull("arguments");
                    String argsStr = "{}";
                    Map<String, Object> argsMap = null;
                    if (argsNode != null) {
                        if (argsNode.isObject()) {
                            argsStr = argsNode.toJson();
                            argsMap = argsNode.toBean(Map.class);
                        } else {
                            argsStr = argsNode.getString();
                        }
                    }

                    ToolCall toolCall = new ToolCall(String.valueOf(idx), callId, name, argsStr, argsMap);

                    // 第一个 function_call 可能携带 thought_signature
                    if (idx == 0 && step.hasKey("thought_signature")) {
                        String sig = step.get("thought_signature").getString();
                        if (Utils.isNotEmpty(sig)) {
                            toolCall.setThoughtSignature(sig);
                        }
                    }

                    toolCalls.add(toolCall);
                    idx++;
                }
            }

            List<AssistantMessage> messages = new ArrayList<>();
            if (!toolCalls.isEmpty()) {
                messages.add(new AssistantMessage("", false, null, null, toolCalls, null));
            }
            return messages;
        }

        return super.parseAssistantMessage(resp, oMessage);
    }
}
