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
import org.noear.solon.ai.chat.event.ChatEventType;
import org.noear.solon.ai.chat.event.ChatStreamContext;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.tool.ToolCallBuilder;
import org.noear.solon.ai.llm.dialect.gemini.models.GeminiRequestBuilder;
import org.noear.solon.ai.llm.dialect.gemini.models.GeminiResponseParser;
import org.noear.solon.ai.llm.dialect.gemini.models.GeminiThoughtProcessor;
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
        String standard = config.getStandardOrProvider();

        if ("google".equalsIgnoreCase(standard) ||
                "google-models".equalsIgnoreCase(standard) ||
                "google-generate".equalsIgnoreCase(standard) ||
                "gemini".equalsIgnoreCase(standard) || //弃用
                "gemini-models".equalsIgnoreCase(standard)) { //弃用
            return true;
        }

        String apiUrl = config.getApiUrl();
        return Assert.isEmpty(standard)
                && Utils.isNotEmpty(apiUrl)
                && apiUrl.contains("/v1beta/models/")
                && (apiUrl.endsWith("generateContent") || apiUrl.endsWith("streamGenerateContent"));
    }

    @Override
    public HttpUtils createHttpUtils(ChatConfig config, boolean isStream) {
        String apiUrl = buildApiUrl(config.getApiUrl(), config.getModel(), isStream);

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
        if (Utils.isEmpty(baseUrl)) {
            return baseUrl;
        }

        int index = baseUrl.indexOf('#');
        if (index > 0) {
            baseUrl = baseUrl.substring(0, index);
        }

        if (baseUrl.contains(":generateContent") || baseUrl.contains(":streamGenerateContent")) {
            if (isStream && !baseUrl.contains("alt=sse")) {
                return baseUrl + (baseUrl.contains("?") ? "&" : "?") + "alt=sse";
            }
            return baseUrl;
        }

        StringBuilder urlBuilder = new StringBuilder();
        urlBuilder.append(baseUrl);

        if (!baseUrl.endsWith("/")) {
            urlBuilder.append("/");
        }

        if (!baseUrl.contains("v1beta/") && !baseUrl.contains("v1/")) {
            urlBuilder.append("v1beta/");
        }

        urlBuilder.append("models/").append(model);
        urlBuilder.append(isStream ? ":streamGenerateContent?alt=sse" : ":generateContent");

        return urlBuilder.toString();
    }

//    @Override
//    public void prepareOutputSchemaInstruction(ChatOptions options, StringBuilder instructionBuilder) {
//        instructionBuilder.append("\n\n## [IMPORTANT: OUTPUT FORMAT]\n")
//                .append("Format your response as a JSON object strictly following this schema:\n")
//                .append("<output_schema>\n").append(options.outputSchema()).append("\n</output_schema>\n")
//                .append("Output only the raw JSON, beginning with '{' and ending with '}'.");
//
//    }

    @Override
    public void prepareOutputFormatOptions(ChatOptions options) {
        options.optionSet("response_mime_type", "application/json");
    }

    /**
     * 解析响应（事件形态）
     *
     * <p>Gemini models generateContent 协议的流式帧只承载内容增量（正文 / 思考 / 工具调用分片），
     * 没有独立的生命周期或服务端工具事件，因此内容主干统一交由核心从内容项转换为
     * TEXT_DELTA / THINKING_DELTA / TOOL_CALL_CHUNK 并保证边界，此处不额外发射事件。</p>
     *
     * @since 4.1
     */
    @Override
    public void parseResponseJson(ChatStreamContext ctx, String data) {
        ChatAccumulator acc = ctx.getAccumulator();

        // models generateContent 分支的解析器仅有累积器形态（无 ctx 重载）
        responseParser.parseResponse(acc, data);

        // 每帧只解析一次 JSON：错误事件与联网来源共用同一份节点
        ONode raw;
        try {
            raw = ONode.ofJson(data);
        } catch (Throwable e) {
            raw = null;
        }

        if (acc.getError() != null) {
            ctx.emit(ctx.event(ChatEventType.ERROR)
                    .rawType("error")
                    .error(acc.getError())
                    .raw(raw)
                    .build());
        }

        emitGroundingCitations(ctx, raw);
        emitCodeExecution(ctx, raw);
    }

    /**
     * 发射联网搜索来源（groundingMetadata）
     *
     * <p>generateContent 协议把来源放在 candidates[].groundingMetadata 里而非独立帧，
     * 事件通道就位前这部分信息对订阅方不可见。</p>
     *
     * @since 4.1
     */
    private void emitGroundingCitations(ChatStreamContext ctx, ONode oResp) {
        if (oResp == null || oResp.isObject() == false) {
            return;
        }

        ONode candidates = oResp.getOrNull("candidates");
        if (candidates == null || candidates.isArray() == false) {
            return;
        }

        int candidateIndex = -1;
        for (ONode candidate : candidates.getArray()) {
            candidateIndex++;

            ONode grounding = candidate.getOrNull("groundingMetadata");
            if (grounding == null || grounding.isObject() == false) {
                continue;
            }

            ONode chunks = grounding.getOrNull("groundingChunks");
            if (chunks == null || chunks.isArray() == false) {
                continue;
            }

            for (ONode chunk : chunks.getArray()) {
                ONode web = chunk.getOrNull("web");
                if (web == null || web.isObject() == false) {
                    continue;
                }

                String uri = web.get("uri").getString();
                if (Utils.isEmpty(uri)) {
                    continue;
                }

                ctx.emit(ctx.event(ChatEventType.CITATION)
                        .rawType("groundingMetadata")
                        .subType("google_search")
                        .index(candidateIndex)
                        .text(uri)
                        .raw(chunk)
                        .build());
            }
        }
    }

    /**
     * 发射代码执行服务端工具事件（executableCode / codeExecutionResult）
     *
     * <p>这两类 part 既不属于正文也不属于思考，内容项通道无处安放，旧实现下被静默丢弃：
     * 订阅方既看不到模型跑了什么代码，也看不到执行输出。代码执行是 Gemini 服务端内置工具，
     * 语义上与 Google 搜索同类，因此走 SERVER_TOOL_* 通道并保证 START/RESULT 成对。</p>
     *
     * <p>内容项产出不受影响：本方法只旁路发事件，不介入 GeminiThoughtProcessor 的文本拼接。</p>
     *
     * @since 4.1
     */
    private void emitCodeExecution(ChatStreamContext ctx, ONode oResp) {
        if (oResp == null || oResp.isObject() == false) {
            return;
        }

        ONode candidates = oResp.getOrNull("candidates");
        if (candidates == null || candidates.isArray() == false) {
            return;
        }

        int candidateIndex = -1;
        for (ONode candidate : candidates.getArray()) {
            candidateIndex++;

            ONode content = candidate.getOrNull("content");
            if (content == null || content.isObject() == false) {
                continue;
            }

            ONode parts = content.getOrNull("parts");
            if (parts == null || parts.isArray() == false) {
                continue;
            }

            for (ONode part : parts.getArray()) {
                if (part == null || part.isObject() == false) {
                    continue;
                }

                //官方 REST 用 camelCase，部分兼容网关转发时写成 snake_case，两种都接
                ONode executableCode = part.getOrNull("executableCode");
                if (executableCode == null) {
                    executableCode = part.getOrNull("executable_code");
                }
                if (executableCode != null && executableCode.isObject()) {
                    ctx.emit(ctx.event(ChatEventType.SERVER_TOOL_START)
                            .rawType("executableCode")
                            .subType("code_execution")
                            .index(candidateIndex)
                            .text(executableCode.get("code").getString())
                            .raw(part)
                            .build());
                    continue;
                }

                ONode executionResult = part.getOrNull("codeExecutionResult");
                if (executionResult == null) {
                    executionResult = part.getOrNull("code_execution_result");
                }
                if (executionResult != null && executionResult.isObject()) {
                    ctx.emit(ctx.event(ChatEventType.SERVER_TOOL_RESULT)
                            .rawType("codeExecutionResult")
                            .subType("code_execution")
                            .index(candidateIndex)
                            .text(executionResult.get("output").getString())
                            .raw(part)
                            .build());
                }
            }
        }
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
    public ONode buildRequestJson(ChatConfig config, ChatOptions options, List<ChatMessage> messages, boolean isStream) {
        return requestBuilder.build(config, options, messages, isStream);
    }

    @Override
    public ONode buildAssistantToolCallMessageNode(ChatAccumulator acc, Map<String, ToolCallBuilder> toolCallBuilders) {
        return requestBuilder.buildAssistantToolCallMessageNode(acc, toolCallBuilders);
    }

    @Override
    public List<AssistantMessage> parseAssistantMessage(ChatAccumulator acc, ONode oMessage) {
        ONode oParts = oMessage.getOrNull("parts");
        if (oParts != null) {
            GeminiThoughtProcessor thoughtProcessor = new GeminiThoughtProcessor();
            return thoughtProcessor.parse(acc, oMessage);
        } else {
            return super.parseAssistantMessage(acc, oMessage);
        }
    }
}
