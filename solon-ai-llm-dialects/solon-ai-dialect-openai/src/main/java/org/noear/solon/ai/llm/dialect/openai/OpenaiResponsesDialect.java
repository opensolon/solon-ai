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
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.dialect.AbstractChatDialect;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.chat.tool.ToolCallBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.noear.solon.core.util.Assert;

import java.util.ArrayList;
import java.util.Collections;
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
    public static OpenaiResponsesDialect getInstance() {
        return instance;
    }

    private final OpenaiResponsesResponseParser responseParser;
    private final OpenaiResponsesRequestBuilder requestBuilder;

    public OpenaiResponsesDialect() {
        this.responseParser = new OpenaiResponsesResponseParser();
        this.requestBuilder = new OpenaiResponsesRequestBuilder();
    }

    @Override
    protected String getApiUrl(ChatConfig config) {
        return OpenaiDialectSupport.buildApiUrl(config.getApiUrl(), "responses");
    }

    /**
     * 匹配检测
     *
     * @param config 聊天配置
     */
    @Override
    public boolean matched(ChatConfig config) {
        String standard = config.getStandardOrProvider();

        // 先规范化 URL（去尾斜杠/查询串/#后缀）再做 endsWith，避免 https://host/v1/responses/?x 失配
        return "openai-responses".equals(standard) ||
                (Assert.isEmpty(standard)
                        && OpenaiDialectSupport.normalizeApiUrl(config.getApiUrl()).endsWith("/responses"));
    }

    /**
     * 解析响应 JSON
     */
    @Override
    public boolean parseResponseJson(ChatConfig config, ChatResponseDefault resp, String json) {
        //有些中转会直接输出："error xxx" 内容
        if (tryParseErrorText(resp, json)) {
            return true;
        }

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
                // schema 无法解析时不能使用 json_schema；退回 Responses 支持的旧式 JSON mode，
                // 至少保留“输出为合法 JSON”的协议保证。
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
                // 如果 required 为空数组，填充所有 properties 的 key（用 ONode 数组 API 构造，避免手拼 JSON 转义问题）
                ONode requiredNode = node.getOrNull("required");
                if (requiredNode == null || (requiredNode.isArray() && requiredNode.getArray().isEmpty())) {
                    ONode newRequired = node.getOrNew("required").asArray();
                    for (String key : propsNode.getObject().keySet()) {
                        newRequired.add(key);
                    }
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
    public ONode buildRequestJson(ChatConfig config, ChatOptions options, List<ChatMessage> messages, boolean isStream) {
        return requestBuilder.build(config, options, messages, isStream);
    }

    /**
     * 解析助手消息（流式工具调用轮的聚合出口）。
     * <p>Responses 的 thinking / text 分帧由 {@link OpenaiResponsesResponseParser} 直接产出 choice，
     * 从不经过父类的 think 标签状态机；本方法实际只被
     * {@code ChatRequestDescDefault#buildStreamToolCallMessage} 调用，用于把
     * {@link #buildAssistantToolCallMessageNode} 的聚合结果落成会话消息。</p>
     * <p>故不再委派父类：父类会把「同帧双通道（正文 + reasoning_content）」拆成
     * 多条思考信号消息 + 正文消息，导致</p>
     * <ol>
     *   <li>{@code messages.get(0)} 不是携带 tool_calls 的那条 → 工具不被执行，
     *       下一轮 input 只有 function_call 而无 function_call_output，官方端点会 400；</li>
     *   <li>reasoning 元数据被挂到多条消息上 → 下一轮 input 出现多个同 id 的 reasoning 项。</li>
     * </ol>
     *
     * @since 4.1
     */
    @Override
    public List<AssistantMessage> parseAssistantMessage(ChatResponseDefault resp, ONode oMessage) {
        String text = oMessage.get("content").getString();
        String thinking = oMessage.get("reasoning_content").getString();

        ONode toolCallsNode = oMessage.getOrNull("tool_calls");
        List<ToolCall> toolCalls = parseToolCalls(resp, toolCallsNode);
        List<Map> toolCallsRaw = Utils.isEmpty(toolCalls) ? null : toolCallsNode.toBean(List.class);

        // 流式聚合的媒体块（如 image_generation_call）随消息带上，多轮才能按 id 回传
        List<ContentBlock> blocksForMsg = null;
        if (Utils.isNotEmpty(resp.getMediaBlocks())) {
            blocksForMsg = new ArrayList<>();
            if (Utils.isNotEmpty(text)) {
                blocksForMsg.add(TextBlock.of(text));
            }
            blocksForMsg.addAll(resp.getMediaBlocks());
        }

        if (Utils.isEmpty(text) && Utils.isEmpty(thinking)
                && Utils.isEmpty(toolCalls) && blocksForMsg == null) {
            return Collections.emptyList();
        }

        AssistantMessage message = new AssistantMessage(
                text == null ? "" : text,
                thinking == null ? "" : thinking,
                false, null, toolCallsRaw, toolCalls, null, blocksForMsg);

        // 官方多轮回放 reasoning 项需要 reasoning_item_id / reasoning_encrypted_content，
        // 它们来自流式分片 metadata 聚合（节点里不携带）
        Map<String, Object> aggMetadata = resp.getAggregationMetadata();
        if (Utils.isNotEmpty(aggMetadata)) {
            message.addMetadata(aggMetadata);
        }

        resp.in_thinking = false; //本方言不走父类思考状态机，统一复位

        return Collections.singletonList(message);
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
