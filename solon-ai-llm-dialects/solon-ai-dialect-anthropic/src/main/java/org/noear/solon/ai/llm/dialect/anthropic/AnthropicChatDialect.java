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
import org.noear.solon.core.util.Assert;
import org.noear.solon.net.http.HttpUtils;
import org.noear.solon.net.http.impl.HttpSslSupplierAny;

import java.util.*;

/**
 * Anthropic Claude Messages接口方言
 * @author oisin lu
 * @date 2026年1月27日
 */
public class AnthropicChatDialect extends AbstractChatDialect {
    private static final AnthropicChatDialect instance = new AnthropicChatDialect();

    private final AnthropicResponseParser responseParser;
    private final AnthropicRequestBuilder requestBuilder;

    public static AnthropicChatDialect getInstance() {
        return instance;
    }

    public AnthropicChatDialect() {
        this.responseParser = new AnthropicResponseParser();
        this.requestBuilder = new AnthropicRequestBuilder();
    }

    /**
     * 匹配检测
     *
     * @param config 聊天配置
     */
    @Override
    public boolean matched(ChatConfig config) {
        return "claude".equalsIgnoreCase(config.getProvider()) ||
                "anthropic".equalsIgnoreCase(config.getProvider()) ||
                (Assert.isEmpty(config.getProvider()) && config.getApiUrl().endsWith("/v1/messages"));
    }

    @Override
    protected String getApiUrl(ChatConfig config) {

        //处理后缀#
        if (config.getApiUrl().indexOf("#") > 0) {
            return config.getApiUrl();
        }

        //自动补全地址
        if (config.getApiUrl().endsWith("/v1/messages")) {
            return config.getApiUrl();
        } else {
            if (config.getApiUrl().endsWith("/")) {
                return config.getApiUrl() + "v1/messages";
            } else {
                return config.getApiUrl() + "/v1/messages";
            }
        }
    }

    @Override
    public HttpUtils createHttpUtils(ChatConfig config, boolean isStream) {
        HttpUtils httpUtils = HttpUtils.http(getApiUrl(config))
                .ssl(HttpSslSupplierAny.getInstance())
                .timeout((int) config.getTimeout().getSeconds());

        if (config.getProxy() != null) {
            httpUtils.proxy(config.getProxy());
        }

        if (Utils.isNotEmpty(config.getApiKey())) {
            httpUtils.header("x-api-key", config.getApiKey());
        }

        // 设置Anthropic版本头
        httpUtils.header("anthropic-version", "2023-06-01");
        httpUtils.header("Content-Type", "application/json");
        if (isStream) {
            httpUtils.header("Accept", "text/event-stream");
        }

        if (Utils.isNotEmpty(config.getUserAgent())) {
            httpUtils.userAgent(config.getUserAgent());
        }

        httpUtils.headers(config.getHeaders());

        return httpUtils;
    }


//    @Override
//    public void prepareOutputSchemaInstruction(ChatOptions options, StringBuilder instructionBuilder) {
//        instructionBuilder.append("\n\n## [IMPORTANT: OUTPUT FORMAT]\n")
//                .append("Format your response as a JSON object strictly following this schema:\n")
//                .append("<output_schema>\n").append(options.outputSchema()).append("\n</output_schema>\n")
//                .append("Output only the raw JSON, beginning with '{' and ending with '}'.");
//    }

    @Override
    public void prepareOutputFormatOptions(ChatOptions options) {

    }

    @Override
    public boolean parseResponseJson(ChatConfig config, ChatResponseDefault resp, String json) {
        return responseParser.parseResponse(resp, json);
    }

    /**
     * 构建 Messages 规范的请求体
     *
     * @param config   聊天配置
     * @param options  聊天选项
     * @param messages 对话消息列表
     * @param isStream 是否使用流式模式
     * @return 规范的请求体
     * @author oisin lu
     * @date 2026年1月27日
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
        ONode oContent = oMessage.getOrNull("content");
        if (oContent != null && oContent.isArray()) {
            boolean hasToolUse = false;
            for (ONode item : oContent.getArray()) {
                if ("tool_use".equals(item.get("type").getString())) {
                    hasToolUse = true;
                    break;
                }
            }

            if (hasToolUse) {
                return parseClaudeAssistantMessage(resp, oMessage, oContent);
            }
        }

        return super.parseAssistantMessage(resp, oMessage);
    }

    /**
     * 构建Claude消息体
     * @param resp
     * @param oMessage
     * @param oContent
     * @return 消息集合
     * @author oisin lu
     * @date 2026年3月4日
     */
    private List<AssistantMessage> parseClaudeAssistantMessage(ChatResponseDefault resp, ONode oMessage, ONode oContent) {
        List<AssistantMessage> messageList = new ArrayList<>();

        StringBuilder thinkingContent = new StringBuilder();
        String thinkingSignature = null;
        StringBuilder textContent = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        List<Map> toolCallsRaw = new ArrayList<>();

        for (ONode item : oContent.getArray()) {
            String type = item.get("type").getString();
            if ("thinking".equals(type)) {
                String thinking = item.get("thinking").getString();
                if (Utils.isNotEmpty(thinking)) {
                    thinkingContent.append(thinking);
                }
                String signature = item.get("signature").getString();
                if (Utils.isNotEmpty(signature)) {
                    thinkingSignature = signature;
                }
            } else if ("text".equals(type)) {
                String text = item.get("text").getString();
                if (Utils.isNotEmpty(text)) {
                    if (textContent.length() > 0) {
                        textContent.append("\n");
                    }
                    textContent.append(text);
                }
            } else if ("tool_use".equals(type)) {
                String toolId = item.get("id").getString();
                String toolName = item.get("name").getString();
                ONode inputNode = item.get("input");

                String inputJson = inputNode != null ? inputNode.toJson() : "{}";
                Map<String, Object> arguments = new HashMap<>();
                if (inputNode != null && inputNode.isObject()) {
                    arguments = inputNode.toBean(Map.class);
                }

                ToolCall toolCall = new ToolCall(toolId, toolId, toolName, inputJson, arguments);
                toolCalls.add(toolCall);
                Map<String, Object> toolCallRaw = new HashMap<>();
                toolCallRaw.put("id", toolId);
                toolCallRaw.put("type", "function");
                Map<String, Object> functionData = new HashMap<>();
                functionData.put("name", toolName);
                functionData.put("arguments", inputJson);
                toolCallRaw.put("function", functionData);
                toolCallsRaw.add(toolCallRaw);
            }
        }

        if (resp.in_thinking && resp.isStream()) {
            messageList.add(new AssistantMessage("</think>", true));
            messageList.add(new AssistantMessage("\n\n", false));
            resp.in_thinking = false;
        }

        // 构建消息内容，将思考内容用 <think>...</think> 包裹以便 getReasoning() 能提取
        String content;
        Map<String, Object> contentRaw = null;
        if (thinkingContent.length() > 0) {
            content = "<think>\n\n" + thinkingContent.toString() + "</think>\n\n";
            if (textContent.length() > 0) {
                content += textContent.toString();
            }
            contentRaw = new LinkedHashMap<>();
            contentRaw.put("thinking", thinkingContent.toString());
            if (thinkingSignature != null) {
                contentRaw.put("thinkingSignature", thinkingSignature);
            }
            if (textContent.length() > 0) {
                contentRaw.put("content", textContent.toString());
            }
        } else {
            content = textContent.length() > 0 ? textContent.toString() : "";
        }

        AssistantMessage message = new AssistantMessage(content,
                false, contentRaw, toolCallsRaw, toolCalls, null);
        messageList.add(message);

        return messageList;
    }

}