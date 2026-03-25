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
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.ChatResponseDefault;
import org.noear.solon.ai.chat.dialect.AbstractChatDialect;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.tool.ToolCallBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.noear.solon.core.util.Assert;

import java.util.List;
import java.util.Map;

/**
 * OpenAI Responses 接口方言
 * @author oisin lu
 * @date 2026年1月28日
 * 支持 OpenAI 的 /v1/responses 接口
 * 通过 provider: "openai-responses" 来使用
 */
public class OpenaiResponsesDialect extends AbstractChatDialect {
    private static final Logger log = LoggerFactory.getLogger(OpenaiResponsesDialect.class);
    private static final OpenaiResponsesDialect instance = new OpenaiResponsesDialect();

    private final OpenaiResponsesResponseParser responseParser;
    private final OpenaiResponsesRequestBuilder requestBuilder;

    public static OpenaiResponsesDialect getInstance() {
        return instance;
    }

    public OpenaiResponsesDialect() {
        this.responseParser = new OpenaiResponsesResponseParser();
        this.requestBuilder = new OpenaiResponsesRequestBuilder();
    }

    /**
     * 匹配检测
     *
     * @param config 聊天配置
     */
    @Override
    public boolean matched(ChatConfig config) {
        return "openai-responses".equals(config.getProvider()) ||
                (Assert.isEmpty(config.getProvider()) && config.getApiUrl().endsWith("/v1/responses"));
    }

    /**
     * 解析响应 JSON
     */
    @Override
    public boolean parseResponseJson(ChatConfig config, ChatResponseDefault resp, String json) {
        return responseParser.parseResponse(resp, json);
    }

    /**
     * Responses API 使用 text.format.json_schema 而非 response_format
     */
    @Override
    public void prepareOutputFormatOptions(ChatOptions options) {
        String outputSchema = options.outputSchema();
        if (Utils.isNotEmpty(outputSchema)) {
            ONode formatNode = new ONode();
            try {
                ONode schemaNode = ONode.ofJson(outputSchema);
                applyStrictSchema(schemaNode);

                formatNode.set("type", "json_schema");
                formatNode.set("name", "output_schema");
                formatNode.set("schema", schemaNode);
                formatNode.set("strict", true);
            } catch (Exception e) {
                log.warn("Failed to parse outputSchema as JSON, falling back to json_object format", e);
                formatNode.set("type", "json_object");
            }

            ONode textNode = new ONode();
            textNode.set("format", formatNode);
            options.optionSet("text", textNode);
        }
    }

    /**
     * 递归为 strict 模式补充 additionalProperties 和 required
     */
    private void applyStrictSchema(ONode node) {
        if (node == null || !node.isObject()) {
            return;
        }

        ONode typeNode = node.getOrNull("type");
        if (typeNode != null && "object".equals(typeNode.getString())) {
            node.set("additionalProperties", false);

            ONode propsNode = node.getOrNull("properties");
            if (propsNode != null && propsNode.isObject()) {
                // 如果 required 为空数组，填充所有 properties 的 key
                ONode requiredNode = node.getOrNull("required");
                if (requiredNode == null || (requiredNode.isArray() && requiredNode.getArray().isEmpty())) {
                    StringBuilder sb = new StringBuilder("[");
                    boolean first = true;
                    for (String key : propsNode.getObject().keySet()) {
                        if (!first) sb.append(",");
                        sb.append("\"").append(key).append("\"");
                        first = false;
                    }
                    sb.append("]");
                    node.set("required", ONode.ofJson(sb.toString()));
                }

                // 递归处理嵌套的 properties
                for (Map.Entry<String, ONode> entry : propsNode.getObject().entrySet()) {
                    applyStrictSchema(entry.getValue());
                }
            }
        }

        // 处理 array 的 items
        ONode itemsNode = node.getOrNull("items");
        if (itemsNode != null && itemsNode.isObject()) {
            applyStrictSchema(itemsNode);
        }
    }

    /**
     * 构建 Responses 规范的请求体
     *
     * @param config   聊天配置
     * @param options  聊天选项
     * @param messages 对话消息列表
     * @param isStream 是否使用流式模式
     * @return Responses 请求体
     * @author oisin lu
     * @date 2026年1月28日
     */
    @Override
    public String buildRequestJson(ChatConfig config, ChatOptions options, List<ChatMessage> messages, boolean isStream) {
        return requestBuilder.build(config, options, messages, isStream);
    }

    /**
     * 构建助手消息（用于工具调用）
     *
     * @author oisin lu
     * @date 2026年1月28日
     */
    @Override
    public ONode buildAssistantToolCallMessageNode(ChatResponseDefault resp, Map<String, ToolCallBuilder> toolCallBuilders) {
        return requestBuilder.buildAssistantToolCallMessageNode(resp, toolCallBuilders);
    }
}
