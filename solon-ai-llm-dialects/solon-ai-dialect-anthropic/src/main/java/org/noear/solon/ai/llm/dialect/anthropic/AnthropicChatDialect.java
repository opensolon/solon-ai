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
import org.noear.solon.ai.chat.ChatAccumulator;
import org.noear.solon.ai.chat.dialect.AbstractChatDialect;
import org.noear.solon.ai.chat.dialect.ChatDialects;
import org.noear.solon.ai.chat.event.ChatStreamContext;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.MessageProtocolState;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.chat.tool.ToolCallBuilder;
import org.noear.solon.core.util.Assert;
import org.noear.solon.net.http.HttpUtils;
import org.noear.solon.net.http.impl.HttpSslSupplierAny;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Anthropic Claude Messages接口方言
 * @author oisin lu
 * @date 2026年1月27日
 */
public class AnthropicChatDialect extends AbstractChatDialect {
    private static final AnthropicChatDialect instance = new AnthropicChatDialect();
    public static AnthropicChatDialect getInstance() {
        return instance;
    }

    private final AnthropicResponseParser responseParser;
    private final AnthropicRequestBuilder requestBuilder;

    private static final Pattern VERSION_PATH_PATTERN = Pattern.compile("/v\\d+/?$");

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
        String standard = config.getStandardOrProvider();

        return "claude".equalsIgnoreCase(standard) ||
                ChatDialects.ANTHROPIC.equalsIgnoreCase(standard) ||
                ChatDialects.ANTHROPIC_MESSAGES.equalsIgnoreCase(standard) ||
                (Assert.isEmpty(standard) && apiPath(config.getApiUrl()).endsWith("/messages"));
    }

    @Override
    protected String getApiUrl(ChatConfig config) {
        String apiUrl = stripFragment(config.getApiUrl());
        int queryIndex = apiUrl.indexOf('?');
        String query = queryIndex < 0 ? "" : apiUrl.substring(queryIndex);
        String path = queryIndex < 0 ? apiUrl : apiUrl.substring(0, queryIndex);

        // 自动补全地址；query 只在路径补全后接回，避免参与后缀与版本判断。
        if (path.endsWith("/messages")) {
            return path + query;
        }

        if (VERSION_PATH_PATTERN.matcher(path).find()) { //匹配 /v1,/v4/ 等
            path = path.endsWith("/") ? path + "messages" : path + "/messages";
        } else {
            path = path.endsWith("/") ? path + "v1/messages" : path + "/v1/messages";
        }
        return path + query;
    }

    private static String stripFragment(String apiUrl) {
        int index = apiUrl.indexOf('#');
        return index < 0 ? apiUrl : apiUrl.substring(0, index);
    }

    private static String apiPath(String apiUrl) {
        String value = stripFragment(apiUrl);
        int queryIndex = value.indexOf('?');
        return queryIndex < 0 ? value : value.substring(0, queryIndex);
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

        // beta 能力协商：协议上只走请求头（GA 的 MessageCreateParams 里没有 betas 字段）。
        // 写在 headers(config.getHeaders()) 之后且把已有同名头并入去重，避免两个渠道互相覆盖。
        // 注意：取的是 config 级选项——本方法先于请求体构建执行，且请求级 options 是 config 的副本，
        // 单请求临时要加 beta 请用 options().httpCustomize(...)。
        String betaHeader = resolveBetaHeader(config);
        if (Utils.isNotEmpty(betaHeader)) {
            httpUtils.header("anthropic-beta", betaHeader);
        }

        return httpUtils;
    }

    /**
     * 汇总 {@code anthropic-beta} 头值：config 级选项（{@code anthropic_beta} / {@code betas}）
     * 与 {@code config.getHeaders()} 里已有的同名头合并去重。
     *
     * <p>beta 能力只靠本头 opt-in，请求 URL 不追加 {@code ?beta=true}：本方言按 GA 面对齐
     * （{@code POST /v1/messages} 无 query param），而带 beta 头请求 GA 端点本身就是合法协商方式，
     * 绝大多数 beta 能力（output-128k、context-1m、fine-grained-tool-streaming 等）只给头即可生效。
     * 只有纯 beta 请求结构（mcp_toolsets / compaction / advisor / fallback）才可能额外要求该参数，
     * 而这些结构本方言并不建模——需要时直接写进 {@code apiUrl}，或用
     * {@code options().httpCustomize(...)} 处理。</p>
     *
     * @since 4.1
     */
    private String resolveBetaHeader(ChatConfig config) {
        Set<String> betas = new LinkedHashSet<>();

        for (Map.Entry<String, String> kv : config.getHeaders().entrySet()) {
            if ("anthropic-beta".equalsIgnoreCase(kv.getKey())) {
                AnthropicRequestBuilder.collectBetas(kv.getValue(), betas);
            }
        }

        String fromOptions = AnthropicRequestBuilder.resolveBetaHeader(config.getModelOptions());
        AnthropicRequestBuilder.collectBetas(fromOptions, betas);

        return betas.isEmpty() ? null : String.join(",", betas);
    }



//    @Override
//    public void prepareOutputSchemaInstruction(ChatOptions options, StringBuilder instructionBuilder) {
//        instructionBuilder.append("\n\n## [IMPORTANT: OUTPUT FORMAT]\n")
//                .append("Format your response as a JSON object strictly following this schema:\n")
//                .append("<output_schema>\n").append(options.outputSchema()).append("\n</output_schema>\n")
//                .append("Output only the raw JSON, beginning with '{' and ending with '}'.");
//    }

    /**
     * 置空：Anthropic 没有 OpenAI 的 {@code response_format} 字段，基类默认注入的
     * {@code response_format={type:json_object}} 在这里是非法顶层字段（直接 400）。
     *
     * <p>原生结构化输出走另一条路：由 {@code AnthropicRequestBuilder} 在构建请求时根据
     * {@code options.outputSchema()} 与模型代次写出 {@code output_config.format}。
     * 不能在本方法里做：它拿不到 {@link ChatConfig}，无法做模型门控。</p>
     */
    @Override
    public void prepareOutputFormatOptions(ChatOptions options) {

    }

    @Override
    public void parseResponseJson(ChatStreamContext ctx, String data) {
        //有些中转会直接输出："error xxx" 内容
        if (tryParseErrorText(ctx.getAccumulator(), data)) {
            return;
        }

        responseParser.parseResponse(ctx, data);
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
    public ONode buildRequestJson(ChatConfig config, ChatOptions options, List<ChatMessage> messages, boolean isStream) {
        return requestBuilder.build(config, options, messages, isStream);
    }

    @Override
    public ONode buildAssistantToolCallMessageNode(ChatAccumulator acc, Map<String, ToolCallBuilder> toolCallBuilders) {
        return requestBuilder.buildAssistantToolCallMessageNode(acc, toolCallBuilders);
    }

    @Override
    public List<AssistantMessage> parseAssistantMessage(ChatAccumulator acc, ONode oMessage) {
        ONode oContent = oMessage.getOrNull("content");
        if (oContent != null && oContent.isArray()) {
            return parseClaudeAssistantMessage(acc, oMessage, oContent);
        }

        return super.parseAssistantMessage(acc, oMessage);
    }

    /**
     * 构建Claude消息体
     * @param acc
     * @param oMessage
     * @param oContent
     * @return 消息集合
     * @author oisin lu
     * @date 2026年3月4日
     */
    private List<AssistantMessage> parseClaudeAssistantMessage(ChatAccumulator acc, ONode oMessage, ONode oContent) {
        List<AssistantMessage> messageList = new ArrayList<>();

        StringBuilder thinkingContent = new StringBuilder();
        String thinkingSignature = null;
        StringBuilder textContent = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        List<ContentBlock> mediaBlocks = new ArrayList<>();
        List<String> redactedBlocks = new ArrayList<>();

        List<String> orderedContentBlocks = new ArrayList<>();
        for (ONode rawBlock : oContent.getArray()) {
            orderedContentBlocks.add(rawBlock.toJson());
        }

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
//                    if (textContent.length() > 0) {
//                        textContent.append("\n");
//                    }
                    textContent.append(text);
                }
            } else if ("image".equals(type)) {
                ContentBlock imageBlock = responseParser.parseClaudeImageBlock(item);
                if (imageBlock != null) {
                    mediaBlocks.add(imageBlock);
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
            } else if ("redacted_thinking".equals(type)) {
                // opaque 安全过滤块：逐块原样保留，供下一轮
                // AnthropicRequestBuilder#appendRedactedThinkingBlocks 取用。
                // 旧实现在本旁路里整块丢弃 → contentRaw 无 redactedThinkingBlocks
                // → opaque 块无法原样回传，多轮 extended thinking 有断链风险
                String data = item.get("data").getString();
                if (Utils.isNotEmpty(data)) {
                    redactedBlocks.add(data);
                }
            }
            // server_tool_use / *_tool_result / container_upload 不在此逐块收集：
            // 本方法的入参多为方言自建的中间节点（buildAssistantToolCallMessageNode 的输出），
            // 其中的服务端块正是从 acc 回填的，再收一遍等于重复。权威来源统一取 acc（见下方协议状态）。
            //
            // 引用（text.citations）在本旁路无法表达：方法签名没有 ChatStreamContext，发不了 CITATION 事件。
            // 这不构成 call/stream 分叉——真实响应的引用由 parseNonStreamResponse / citations_delta 两条
            // 主路径覆盖，本旁路拿到的中间节点本身不携带 citations。
        }

        if (acc.in_thinking && acc.isStream()) {
            acc.in_thinking = false;
        }

        // 构建完整 AssistantMessage：text/thinking 分离，不再注入 <think> 标签。
        String textStr = textContent.toString();
        String thinkingStr = thinkingContent.toString();

        Map<String, Object> protocolData = new LinkedHashMap<>();
        if (thinkingSignature != null) {
            protocolData.put("thinkingSignature", thinkingSignature);
        }
        if (!orderedContentBlocks.isEmpty()) {
            protocolData.put(AnthropicResponseParser.CONTENT_BLOCKS_RAW_KEY, orderedContentBlocks);
        }

        // redacted_thinking 分块列表进入 Anthropic 命名空间状态，供多轮逐块回传；
        // 与 parseNonStreamResponse 对称
        if (redactedBlocks.isEmpty() == false) {
            protocolData.put("redactedThinkingBlocks", redactedBlocks);
        }

        // 服务端工具原始块与代码执行容器：本轮的工具循环会把这条消息写进历史，下一轮出站时
        // 由 AnthropicRequestBuilder 从协议状态取回原样回传。
        protocolData = AnthropicResponseParser.appendServerToolRaw(acc, protocolData);

        MessageProtocolState existingState = acc.getTerminalProtocolStates() == null
                ? null : acc.getTerminalProtocolStates().get(AnthropicMessageStateSupport.PROTOCOL_ID);
        MessageProtocolState protocolState = existingState != null
                ? existingState : AnthropicMessageStateSupport.createState(protocolData);
        Map<String, MessageProtocolState> protocolStates = protocolState == null ? null
                : Collections.singletonMap(AnthropicMessageStateSupport.PROTOCOL_ID, protocolState);
        messageList.add(AssistantMessage.snapshot(
                textStr,
                thinkingStr,
                toolCalls.isEmpty() ? null : toolCalls,
                mediaBlocks.isEmpty() ? null : mediaBlocks,
                null,
                null,
                protocolStates));

        return messageList;
    }

}