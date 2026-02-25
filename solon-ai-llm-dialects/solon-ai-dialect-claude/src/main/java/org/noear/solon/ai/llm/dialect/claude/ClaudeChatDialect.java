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
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.ChatResponseDefault;
import org.noear.solon.ai.chat.dialect.AbstractChatDialect;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.tool.ToolCallBuilder;
import org.noear.solon.net.http.HttpUtils;
import org.noear.solon.net.http.impl.HttpSslSupplierAny;

import java.util.List;
import java.util.Map;

/**
 * Anthropic Claude Messages接口方言
 * @author oisin lu
 * @date 2026年1月27日
 */
public class ClaudeChatDialect extends AbstractChatDialect {
    private static final ClaudeChatDialect instance = new ClaudeChatDialect();

    private final ClaudeResponseParser responseParser;
    private final ClaudeRequestBuilder requestBuilder;

    public static ClaudeChatDialect getInstance() {
        return instance;
    }

    public ClaudeChatDialect() {
        this.responseParser = new ClaudeResponseParser();
        this.requestBuilder = new ClaudeRequestBuilder();
    }

    /**
     * 匹配检测
     * @param config 聊天配置
     */
    @Override
    public boolean matched(ChatConfig config) {
        return "claude".equals(config.getProvider()) ||
                "anthropic".equals(config.getProvider()) ;
    }

    @Override
    public HttpUtils createHttpUtils(ChatConfig config, boolean isStream) {
        String apiUrl = config.getApiUrl().toString();

        HttpUtils httpUtils = HttpUtils.http(apiUrl)
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

    @Override
    public boolean parseResponseJson(ChatConfig config, ChatResponseDefault resp, String json) {
        return responseParser.parseResponse(resp, json);
    }

    /**
     * 构建 Messages 规范的请求体
     * @author oisin lu
     * @date 2026年1月27日
     * @param config   聊天配置
     * @param options  聊天选项
     * @param messages 对话消息列表
     * @param isStream 是否使用流式模式
     * @return 规范的请求体
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
        return super.parseAssistantMessage(resp, oMessage); // 使用父类的通用解析方法
    }

    //如果没有改变，不需要重写
//    @Override
//    public AssistantMessage buildAssistantMessageByToolMessages(AssistantMessage toolCallMessage,List<ToolMessage> toolMessages) {
//        return requestBuilder.buildAssistantMessageByToolMessages(toolMessages);
//    }
}