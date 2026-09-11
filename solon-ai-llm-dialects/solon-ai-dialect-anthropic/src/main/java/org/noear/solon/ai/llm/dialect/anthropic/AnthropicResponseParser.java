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
import org.noear.snack4.json.JsonReader;
import org.noear.solon.Utils;
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.chat.ChatException;
import org.noear.solon.ai.chat.ChatAccumulator;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.event.ChatEventDefault;
import org.noear.solon.ai.chat.event.ChatEventType;
import org.noear.solon.ai.chat.event.ChatStreamContext;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.MessageProtocolState;
import org.noear.solon.ai.chat.source.Citation;
import org.noear.solon.ai.chat.source.SearchResult;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.chat.tool.ToolCallJsonSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Claude 响应解析器
 * @author oisin lu
 * @date 2026年1月27日
 */
public class AnthropicResponseParser {
    private static final Logger LOG = LoggerFactory.getLogger(AnthropicResponseParser.class);

    /**
     * 流式工具调用的按请求隔离状态
     */
    private static class StreamToolState {
        String toolUseId;
        String toolName;
        StringBuilder toolInput;
        String initialInput;
        boolean hasInputDelta;
        boolean serverTool;
    }

    /**
     * 流式工具调用状态容器：按 content_block 的 index 跟踪。
     * <p>协议规范（RawMessageStreamEvent）：一次响应的 content[] 可包含多个并列/交错的
     * tool_use 块，content_block_start/delta/stop 事件均携带 index 字段标识所属块。
     * 官方 SDK 即按 index 分别聚合每个块的 input_json_delta，因此这里必须用 Map 而非单值。</p>
     */
    private static final String STREAM_TOOL_STATE_KEY = "StreamToolStates";
    private static final String REDACTED_THINKING_DATA_KEY = "redactedThinkingData";
    /** 完整响应 content blocks 的有序、原始 JSON 载体。 */
    static final String CONTENT_BLOCKS_RAW_KEY = "anthropicContentBlocks";
    private static final String STREAM_CONTENT_BLOCKS_KEY = "AnthropicContentBlocks";
    private static final String EMITTED_TYPED_EVENTS_KEY = "AnthropicEmittedTypedEvents";
    /**
     * 流式 thinking signature 的兼容累计状态：按 content block index 隔离。
     *
     * <p>Anthropic 官方协议规定每个 thinking 块只发送一个完整的 {@code signature_delta}；
     * 部分兼容网关却会把 opaque signature 拆成多帧。单帧时追加结果与官方覆盖语义一致，
     * 多帧时则必须按 index 拼回完整签名，避免不同 thinking 块的分片串扰。</p>
     */
    private static final String STREAM_THINKING_SIGNATURES_KEY = "AnthropicThinkingSignatures";

    @SuppressWarnings("unchecked")
    private static Map<Integer, StringBuilder> streamThinkingSignatures(ChatAccumulator acc) {
        Map<Integer, StringBuilder> signatures = acc.attrAs(STREAM_THINKING_SIGNATURES_KEY);
        if (signatures == null) {
            signatures = new HashMap<>();
            acc.attrPut(STREAM_THINKING_SIGNATURES_KEY, signatures);
        }
        return signatures;
    }

    /**
     * 累计一个 thinking 块的 signature 分片，并同步回原始块载体。
     */
    private static String appendStreamThinkingSignature(ChatAccumulator acc, int index, String fragment) {
        StringBuilder signature = streamThinkingSignatures(acc).get(index);
        if (signature == null) {
            signature = new StringBuilder();
            streamThinkingSignatures(acc).put(index, signature);
        }
        signature.append(fragment);

        String completeSignature = signature.toString();
        Map<Integer, ONode> blocks = streamContentBlocks(acc, false);
        ONode block = blocks == null ? null : blocks.get(index);
        if (block != null) {
            block.set("signature", completeSignature);
        }
        return completeSignature;
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, ONode> streamContentBlocks(ChatAccumulator acc, boolean create) {
        Map<Integer, ONode> blocks = acc.attrAs(STREAM_CONTENT_BLOCKS_KEY);
        if (blocks == null && create) {
            blocks = new HashMap<>();
            acc.attrPut(STREAM_CONTENT_BLOCKS_KEY, blocks);
        }
        return blocks;
    }

    private static void captureStreamContentBlock(ChatAccumulator acc, int index, ONode block) {
        if (block != null && block.isObject()) {
            streamContentBlocks(acc, true).put(index, ONode.ofJson(block.toJson()));
        }
    }

    private static void appendOrderedContentRaw(ChatAccumulator acc, Map<String, Object> raw) {
        Map<Integer, ONode> blocks = streamContentBlocks(acc, false);
        if (blocks == null || blocks.isEmpty()) return;
        List<Integer> indexes = new ArrayList<>(blocks.keySet());
        Collections.sort(indexes);
        List<String> ordered = new ArrayList<>();
        for (Integer index : indexes) ordered.add(blocks.get(index).toJson());
        raw.put(CONTENT_BLOCKS_RAW_KEY, ordered);
    }

    private static void updateStreamContentBlock(ChatAccumulator acc, int index, String deltaType, ONode delta) {
        Map<Integer, ONode> blocks = streamContentBlocks(acc, false);
        ONode block = blocks == null ? null : blocks.get(index);
        if (block == null || delta == null) return;
        if ("text_delta".equals(deltaType)) block.set("text", block.get("text").getString() + delta.get("text").getString());
        else if ("thinking_delta".equals(deltaType)) block.set("thinking", block.get("thinking").getString() + delta.get("thinking").getString());
        else if ("citations_delta".equals(deltaType) || "citation_delta".equals(deltaType)) {
            ONode citation = delta.getOrNull("citation");
            if (citation != null && citation.isObject()) {
                // 引用 delta 属于最终 text block 的一部分。深拷贝后按到达顺序追加，
                // 保证流式终态与非流式 content、下一轮原样回放保持一致。
                block.getOrNew("citations").asArray().add(ONode.ofJson(citation.toJson()));
            }
        } else if ("input_json_delta".equals(deltaType)) {
            // 参数分片只做字符串累计，不做逐帧 JSON 解析：完成前的累计串几乎必然不是合法 JSON
            //（截断的对象、裸的键名），逐帧尝试解析是 O(n²) 纯开销；最终 input 统一在
            // content_block_stop 由 updateStreamToolBlockInput 落定（或流中断时保留 start 初值）。
            Map<Integer, StreamToolState> states = toolStates(acc, false);
            StreamToolState state = states == null ? null : states.get(index);
            if (state != null) {
                String partial = delta.get("partial_json").getString();
                if (Utils.isNotEmpty(partial)) {
                    state.hasInputDelta = true;
                    state.toolInput.append(partial);
                }
            }
        }
    }

    private static String resolveFinalToolInput(StreamToolState state) {
        if (state == null) {
            return null;
        }
        if (state.initialInput == null) {
            return state.toolInput.toString();
        }
        if (state.hasInputDelta) {
            String deltaInput = state.toolInput.toString();
            if (ToolCallJsonSanitizer.isSingleJsonObject(deltaInput)) {
                return deltaInput;
            }
        }
        return state.initialInput;
    }

    private static void updateStreamToolBlockInput(ChatAccumulator acc, int index, String inputJson) {
        if (Utils.isEmpty(inputJson)) {
            return;
        }
        Map<Integer, ONode> blocks = streamContentBlocks(acc, false);
        ONode block = blocks == null ? null : blocks.get(index);
        if (block == null) {
            return;
        }
        try {
            ONode input = ONode.ofJson(inputJson);
            if (input != null && input.isObject()) {
                block.set("input", input);
            }
        } catch (Exception ignored) {
            // 最终回放块宁可保留 start 的合法 input，也不能写入损坏 JSON。
        }
    }

    /**
     * 保存流式内容块（按协议 index 排序），供终态和下一轮请求完整回放。
     */
    private static void appendStreamContentRaw(ChatAccumulator acc, Map<String, Object> raw) {
        appendOrderedContentRaw(acc, raw);
    }


    private static final String REASONING_FIELD_THINKING = "thinking";

    /**
     * 终态帧已发射标记（message_stop 与兼容网关 [DONE] 双发时防重复补位）
     */
    private static final String TERMINAL_FRAME_EMITTED_KEY = "AnthropicTerminalFrameEmitted";

    /**
     * 代码执行沙盒容器（{@code message_start.message.container} / {@code message_delta.delta.container}
     * / 非流式 {@code container}）。
     *
     * <p>协议 Container 携带 {@code id} 与 {@code expires_at}：多轮复用同一容器需在下一轮请求里回传
     * {@code container} 字段（MessageCreateParams 合法顶层字段，可经 options 透传）。旧实现整块丢弃，
     * 调用方无从获取容器标识。</p>
     *
     * @since 4.1
     */
    private static final String CONTAINER_KEY = "AnthropicContainer";

    /**
     * 取本次响应携带的代码执行容器节点（未出现则为 null）。
     *
     * @since 4.1
     */
    public static ONode container(ChatAccumulator acc) {
        return acc == null ? null : acc.attrAs(CONTAINER_KEY);
    }

    /**
     * 记录代码执行容器（幂等覆盖：message_start 先给，message_delta 可刷新）。
     *
     * @since 4.1
     */
    private static void captureContainer(ChatAccumulator acc, ONode containerNode) {
        if (containerNode != null && containerNode.isObject()) {
            acc.attrPut(CONTAINER_KEY, containerNode);
        }
    }

    /**
     * 服务端工具原始块留存区（{@code server_tool_use} / {@code *_tool_result} / {@code container_upload}）。
     *
     * <p><b>为什么必须原样留存</b>：协议要求「续跑时把上一轮的 content 原样回传」，而这些块的语义
     * 无法从事件重建——{@code web_search_tool_result} 里的 {@code encrypted_content} 是服务端签发的
     * opaque 凭证、{@code code_execution_tool_result} 关联着沙盒容器、{@code server_tool_use} 是结果块的
     * {@code tool_use_id} 归属方。旧实现把它们转成事件后即丢弃，于是：</p>
     * <ul>
     *   <li>{@code stop_reason=pause_turn} 的续跑退化为「重跑」（搜索/抓取重新计费）；</li>
     *   <li>历史前缀每轮缺块，缓存断点前的字节序与上一轮不一致，prompt cache 命中率被连带拉低。</li>
     * </ul>
     *
     * <p>以 JSON 字符串按原序保存（而非 ONode）：出站要跨轮存活在
     * {@code AssistantMessage.contentRaw} 里，字符串形态可被记忆序列化与反序列化。</p>
     *
     * @since 4.1
     */
    static final String SERVER_BLOCKS_KEY = "AnthropicServerToolBlocks";

    /**
     * {@code contentRaw} 中承载服务端工具原始块的键（供 {@code AnthropicRequestBuilder} 回传取用）。
     *
     * @since 4.1
     */
    static final String SERVER_BLOCKS_RAW_KEY = "anthropicServerToolBlocks";

    /**
     * {@code contentRaw} 中承载代码执行容器的键（供下一轮回填顶层 {@code container}）。
     *
     * @since 4.1
     */
    static final String CONTAINER_RAW_KEY = "anthropicContainer";

    @SuppressWarnings("unchecked")
    static List<String> getServerToolBlocks(ChatAccumulator acc, boolean create) {
        List<String> blocks = acc.attrAs(SERVER_BLOCKS_KEY);
        if (blocks == null && create) {
            blocks = new ArrayList<>();
            acc.attrPut(SERVER_BLOCKS_KEY, blocks);
        }
        return blocks;
    }

    /**
     * 留存一个服务端工具原始块（保序、去重）。
     *
     * <p>去重按整块 JSON：兼容网关重发同一块（如把 content_block_start 与非流式 content 都投一遍）时，
     * 回传里出现两个同 {@code tool_use_id} 的结果块会被服务端按 schema 拒掉。</p>
     *
     * @since 4.1
     */
    private static void captureServerToolBlock(ChatAccumulator acc, ONode blockNode) {
        if (blockNode == null || blockNode.isObject() == false) {
            return;
        }
        String json = blockNode.toJson();
        List<String> blocks = getServerToolBlocks(acc, true);
        if (blocks.contains(json) == false) {
            blocks.add(json);
        }
    }

    private static void updateServerToolBlockInput(ChatAccumulator acc, String toolUseId, String inputJson) {
        if (Utils.isEmpty(toolUseId) || Utils.isEmpty(inputJson)) {
            return;
        }
        List<String> blocks = getServerToolBlocks(acc, false);
        if (Utils.isEmpty(blocks)) {
            return;
        }
        for (int i = 0; i < blocks.size(); i++) {
            ONode block = ONode.ofJson(blocks.get(i));
            if (toolUseId.equals(block.get("id").getString())) {
                ONode input = ONode.ofJson(inputJson);
                if (input.isObject()) {
                    block.set("input", input);
                    blocks.set(i, block.toJson());
                }
                return;
            }
        }
    }

    /**
     * 把留存的服务端工具块与容器写入 {@code contentRaw}（供下一轮出站回传）。
     *
     * @since 4.1
     */
    static Map<String, Object> appendServerToolRaw(ChatAccumulator acc, Map<String, Object> contentRaw) {
        List<String> blocks = getServerToolBlocks(acc, false);
        ONode containerNode = container(acc);

        if (Utils.isEmpty(blocks) && containerNode == null) {
            return contentRaw;
        }

        Map<String, Object> raw = contentRaw == null ? new LinkedHashMap<>() : contentRaw;
        if (Utils.isEmpty(blocks) == false) {
            raw.put(SERVER_BLOCKS_RAW_KEY, new ArrayList<>(blocks));
        }
        if (containerNode != null) {
            raw.put(CONTAINER_RAW_KEY, containerNode.toJson());
        }
        return raw;
    }

    /**
     * 取 {@code tool_use} / {@code server_tool_use} 块在 {@code content_block_start} 时已给出的参数初值。
     *
     * <p>协议上 {@code input} 是必填字段，标准流式实现里它恒为空对象（真实参数全部走
     * {@code input_json_delta}）。但网关与 eager input streaming 可能在 start 就给出部分或完整参数，
     * 且此时后续可能<b>不再有</b> delta——不读它，参数会静默退化成空对象。</p>
     *
     * <p>空对象必须返回 null 而不是 {@code "{}"}：否则它会与随后的 delta 拼成 {@code {}{"a":1}} 这类脏值。</p>
     *
     * @since 4.1
     */
    private static String initialToolInput(ONode contentBlock) {
        ONode inputNode = contentBlock.getOrNull("input");
        if (inputNode == null || inputNode.isObject() == false || inputNode.getObject().isEmpty()) {
            return null;
        }
        return inputNode.toJson();
    }

    @SuppressWarnings("unchecked")
    private static Set<String> emittedTypedEvents(ChatAccumulator acc) {
        Set<String> emitted = acc.attrAs(EMITTED_TYPED_EVENTS_KEY);
        if (emitted == null) {
            emitted = new HashSet<>();
            acc.attrPut(EMITTED_TYPED_EVENTS_KEY, emitted);
        }
        return emitted;
    }

    private static boolean markTypedEvent(ChatAccumulator acc, String kind, int blockIndex,
                                          int itemIndex, ONode payload) {
        String key = kind + ':' + blockIndex + ':' + itemIndex + ':'
                + (payload == null ? "null" : payload.toJson());
        return emittedTypedEvents(acc).add(key);
    }

    private static Citation parseCitation(ONode citation) {
        if (citation == null || citation.isObject() == false) {
            return null;
        }

        String title = citation.get("document_title").getString();
        if (Utils.isEmpty(title)) {
            title = citation.get("title").getString();
        }
        return new Citation()
                .type(citation.get("type").getString())
                .title(title)
                .url(citation.get("url").getString())
                .citedText(citation.get("cited_text").getString());
    }

    private static boolean emitCitation(ChatStreamContext ctx, String rawType, int blockIndex,
                                         ONode citation, ONode raw) {
        Citation typedCitation = parseCitation(citation);
        if (typedCitation == null) {
            return false;
        }

        ctx.emit(ctx.event(ChatEventType.CITATION)
                .rawType(rawType)
                .subType(typedCitation.getType())
                .index(blockIndex)
                .text(extractCitationText(citation))
                .citation(typedCitation)
                .raw(raw)
                .build());
        return true;
    }

    /**
     * 发射 {@code text} 块内嵌的引用（协议 {@code TextBlock.citations}）。
     *
     * <p>流式路径有 {@code citations_delta} 事件可依，非流式的引用则直接内嵌在 text 块里。旧实现只读
     * {@code text} 字段，于是同一模型行为下 {@code call()} 完全看不到引用、{@code stream()} 看得到——
     * 正是本方言在别处反复保证要避免的 call/stream 分叉。</p>
     *
     * @since 4.1
     */
    private static void emitTextCitations(ChatStreamContext ctx, String rawType, int blockIndex, ONode textBlock) {
        ONode citations = textBlock.getOrNull("citations");
        if (citations == null || citations.isArray() == false) {
            return;
        }

        for (ONode citation : citations.getArray()) {
            if (citation == null || citation.isObject() == false) {
                continue;
            }
            emitCitation(ctx, rawType, blockIndex, citation, citation);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<Integer, StreamToolState> toolStates(ChatAccumulator acc, boolean create) {
        Map<Integer, StreamToolState> states = acc.attrAs(STREAM_TOOL_STATE_KEY);
        if (states == null && create) {
            states = new HashMap<>();
            acc.attrPut(STREAM_TOOL_STATE_KEY, states);
        }
        return states;
    }

    private Map<Integer, StreamToolState> getToolStates(ChatAccumulator acc, boolean create) {
        return toolStates(acc, create);
    }

    /**
     * redacted_thinking 分块列表（协议要求逐块原样回传，不可拼接）。
     */
    static final String REDACTED_BLOCKS_KEY = "RedactedThinkingBlocks";

    @SuppressWarnings("unchecked")
    static List<String> getRedactedBlocks(ChatAccumulator acc, boolean create) {
        List<String> blocks = acc.attrAs(REDACTED_BLOCKS_KEY);
        if (blocks == null && create) {
            blocks = new ArrayList<>();
            acc.attrPut(REDACTED_BLOCKS_KEY, blocks);
        }
        return blocks;
    }

    /**
     * 解析 usage 信息（包含 Prompt Caching 与思考 token 统计）
     *
     * @param usageNode usage JSON 节点
     * @return AiUsage 对象
     * @author oisin lu
     * @date 2026年1月27日
     */
    private AiUsage parseUsage(ONode usageNode) {
        if (usageNode == null) {
            return null;
        }
        long inputTokens = usageNode.hasKey("input_tokens") ? usageNode.get("input_tokens").getLong() : 0L;
        long outputTokens = usageNode.hasKey("output_tokens") ? usageNode.get("output_tokens").getLong() : 0L;
        // Claude Prompt Caching 相关的 token 统计
        long cacheCreationInputTokens = 0L;
        long cacheReadInputTokens = 0L;

        // 缓存写入的 TTL 明细（协议 Usage.cache_creation → CacheCreation）：
        // 1h 写入单价是 5m 的两倍，只有汇总的 cache_creation_input_tokens 无法还原真实缓存成本。
        // 旧实现整块不读，调用方只能自己去 AiUsage.getSource() 里捞
        long cacheCreation5mTokens = 0L;
        long cacheCreation1hTokens = 0L;
        ONode cacheCreation = usageNode.getOrNull("cache_creation");
        if (cacheCreation != null && cacheCreation.isObject()) {
            cacheCreation5mTokens = cacheCreation.get("ephemeral_5m_input_tokens").getLong();
            cacheCreation1hTokens = cacheCreation.get("ephemeral_1h_input_tokens").getLong();
        }

        if (usageNode.hasKey("cache_creation_input_tokens")) {
            cacheCreationInputTokens = usageNode.get("cache_creation_input_tokens").getLong();
        } else {
            // 只给明细不给汇总的形态：由明细求和补出汇总，否则 cacheCreationInputTokens 恒 0，
            // 连带 promptTokens 少算掉整个缓存写入部分
            cacheCreationInputTokens = cacheCreation5mTokens + cacheCreation1hTokens;
        }
        if (usageNode.hasKey("cache_read_input_tokens")) {
            cacheReadInputTokens = usageNode.get("cache_read_input_tokens").getLong();
        }
        // 思考 token（协议 OutputTokensDetails.thinking_tokens；兼容网关可能用 OpenAI 风格的 reasoning_tokens），
        // 不读取时 AiUsage.thinkTokens() 在 Anthropic 路径下恒为 0
        long thinkTokens = 0L;
        ONode outputTokensDetails = usageNode.getOrNull("output_tokens_details");
        if (outputTokensDetails != null && outputTokensDetails.isObject()) {
            thinkTokens = outputTokensDetails.get("thinking_tokens").getLong();
            if (thinkTokens == 0L) {
                thinkTokens = outputTokensDetails.get("reasoning_tokens").getLong();
            }
        }

        // 服务端内置工具的调用次数（协议 Usage.server_tool_use → ServerToolUsage）：
        // 按「次」独立计费（与 token 不同计价单位），不能并入 promptTokens/totalTokens，否则等于把次数当 token 算。
        // 该字段在 message_start 与 message_delta 都会出现（协议 MessageDeltaUsage 同样带 server_tool_use）
        long webSearchRequests = 0L;
        long webFetchRequests = 0L;
        ONode serverToolUse = usageNode.getOrNull("server_tool_use");
        if (serverToolUse != null && serverToolUse.isObject()) {
            webSearchRequests = serverToolUse.get("web_search_requests").getLong();
            webFetchRequests = serverToolUse.get("web_fetch_requests").getLong();
        }

        // 计费档位与推理区域（协议 Usage.service_tier / Usage.inference_geo）：只出现在 message_start，
        // 保留供应商原始字面量（service_tier 已知 standard/priority/batch，不做枚举归一以免掩盖新增档位）
        String serviceTier = usageNode.get("service_tier").getString();
        String inferenceGeo = usageNode.get("inference_geo").getString();

        // Anthropic 的 input_tokens 不含缓存部分，需将 cache 两项并入，归一为“全部输入 token”语义（与 OpenAI prompt_tokens 对齐），
        // 否则下游 cacheRate = cacheRead / promptTokens 会被高估并恒定 100%
        long totalInputTokens = inputTokens + cacheCreationInputTokens + cacheReadInputTokens;
        // usage 对象即使所有累计值均为 0 也有语义：message_delta 可用显式 0 修正早期快照。
        // 只要包含协议字段就构建 AiUsage，不能用“值大于 0”判断字段是否存在。
        boolean hasUsageFields = usageNode.isObject() && (usageNode.hasKey("input_tokens")
                || usageNode.hasKey("output_tokens")
                || usageNode.hasKey("cache_creation_input_tokens")
                || usageNode.hasKey("cache_read_input_tokens")
                || usageNode.hasKey("cache_creation")
                || usageNode.hasKey("output_tokens_details")
                || usageNode.hasKey("server_tool_use")
                || usageNode.hasKey("service_tier")
                || usageNode.hasKey("inference_geo"));
        if (hasUsageFields) {
            return AiUsage.builder()
                    .promptTokens(totalInputTokens)
                    .thinkTokens(thinkTokens)
                    .completionTokens(outputTokens)
                    .totalTokens(totalInputTokens + outputTokens)
                    .cacheCreationInputTokens(cacheCreationInputTokens)
                    .cacheReadInputTokens(cacheReadInputTokens)
                    .cacheCreation5mInputTokens(cacheCreation5mTokens)
                    .cacheCreation1hInputTokens(cacheCreation1hTokens)
                    .webSearchRequests(webSearchRequests)
                    .webFetchRequests(webFetchRequests)
                    .serviceTier(serviceTier)
                    .inferenceGeo(inferenceGeo)
                    .source(usageNode)
                    .build();
        }

        return null;
    }

    /**
     * 合并两个 usage 累计快照：当前帧明确携带的字段覆盖旧值，缺失字段沿用旧值。
     *
     * <p>Anthropic 的 message_delta.usage 是累计快照，不是增量。官方 SDK 按字段存在性覆盖，
     * 包括显式的 0；server_tool_use / output_tokens_details 等对象同样整体替换，不做递归累加。</p>
     */
    private static ONode mergeUsageSource(ONode prev, ONode curr) {
        if (prev == null || !prev.isObject()) {
            return curr;
        }
        if (curr == null || !curr.isObject()) {
            return prev;
        }
        ONode merged = ONode.ofJson(prev.toJson());
        for (Map.Entry<String, ONode> kv : curr.getObject().entrySet()) {
            ONode newValue = kv.getValue();
            merged.set(kv.getKey(), newValue == null ? null : ONode.ofJson(newValue.toJson()));
        }
        return merged;
    }

    /**
     * 解析响应 JSON
     *
     * @param ctx  流上下文
     * @param json 响应 JSON 字符串
     * @since 4.1
     */
    public boolean parseResponse(ChatStreamContext ctx, String json) {
        if (ctx.getAccumulator().isStream()) {
            return parseStreamResponse(ctx, json);
        } else {
            return parseNonStreamResponse(ctx, json);
        }
    }

    private ChatException parseAnthropicError(ONode error) {
        if (error == null || error.isNull()) {
            return new ChatException("Anthropic API error");
        }

        String errorType = error.isObject() ? error.get("type").getString() : null;
        String errorMsg = error.isObject() ? error.get("message").getString() : error.getString();
        if (Utils.isEmpty(errorMsg)) {
            errorMsg = error.toJson();
        }
        if (Utils.isNotEmpty(errorType)) {
            errorMsg = "[" + errorType + "] " + errorMsg;
        }
        return new ChatException(errorMsg);
    }

    /**
     * 解析流式响应
     *
     * <p>正文、思考、工具调用和工具参数均直接通过事件通道交付；本方法仅在终态保存
     * 无法由事件重建的协议载荷（签名、服务端工具原始块和容器）。</p>
     *
     * @param ctx  流上下文
     * @param json 响应 JSON 字符串
     * @return 是否有有效的选择
     * @since 4.1
     */
    public boolean parseStreamResponse(ChatStreamContext ctx, String json) {
        ChatAccumulator acc = ctx.getAccumulator();

        if (json == null || json.isEmpty()) {
            return false;
        }

        StringBuilder redactedThinkingData = acc.attrIfAbsent(REDACTED_THINKING_DATA_KEY, (k) -> new StringBuilder());

        String[] lines = json.split("\n");
        boolean hasContent = false;

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
                // 工具状态由终态收口的 flushPendingToolStates 统一兑底与清理（不能在此先移除，
                // 否则缺 content_block_stop 的截断流参数永远停在字符串累计阶段）
                emitTerminalFrameOnce(ctx, "[DONE]");
                return true;
            }

            ONode oResp = new JsonReader(jsonData).readNext();
            if (oResp.isObject() == false) {
                continue;
            }

            if (oResp.hasKey("error")) {
                acc.setError(parseAnthropicError(oResp.get("error")));
                ctx.emit(ctx.event(ChatEventType.ERROR)
                        .rawType("error")
                        .error(acc.getError())
                        .raw(oResp)
                        .build());
                return true;
            }

            // Claude 流式响应事件类型
            String eventType = oResp.get("type").getString();
            if ("error".equals(eventType)) {
                acc.attrRemove(STREAM_TOOL_STATE_KEY);

                ONode oError = oResp.get("error");
                acc.setError(parseAnthropicError(oError));
                ctx.emit(ctx.event(ChatEventType.ERROR)
                        .rawType(eventType)
                        .error(acc.getError())
                        .raw(oResp)
                        .build());
                return true;
            } else if ("message_start".equals(eventType)) {
                // 消息开始，可以设置模型信息和初始 usage
                ONode message = oResp.get("message");
                if (message != null) {
                    acc.setModel(message.get("model").getString());

                    // 供应商响应标识（msg_xxx）：记录一次，本步后续事件自动预填
                    ctx.setProviderResponseId(message.get("id").getString());

                    // 代码执行容器（container.id / expires_at）：多轮复用需回传，旧实现整块丢弃
                    captureContainer(acc, message.getOrNull("container"));

                    // 某些情况下 message_start 也包含初始 usage 信息
                    AiUsage usage = parseUsage(message.getOrNull("usage"));
                    if (usage != null) {
                        acc.setUsage(usage);
                    }
                }
            } else if ("content_block_start".equals(eventType)) {
                ONode contentBlock = oResp.get("content_block");
                if (contentBlock != null) {
                    captureStreamContentBlock(acc, oResp.get("index").getInt(), contentBlock);
                    String blockType = contentBlock.get("type").getString();
                    if ("thinking".equals(blockType)) {
                        acc.reasoning_field_name = REASONING_FIELD_THINKING;
                        acc.in_thinking = true;
                        hasContent = true;
                        String thinking = contentBlock.get("thinking").getString();
                        if (Utils.isNotEmpty(thinking)) {
                            ctx.emit(ctx.event(ChatEventType.THINKING_DELTA)
                                    .rawType(eventType)
                                    .index(oResp.get("index").getInt())
                                    .text(thinking)
                                    .raw(oResp)
                                    .build());
                        }
                        String signature = contentBlock.get("signature").getString();
                        if (Utils.isNotEmpty(signature)) {
                            int blockIndex = oResp.get("index").getInt();
                            streamThinkingSignatures(acc).put(blockIndex, new StringBuilder(signature));
                            acc.thinkingSignature = signature;
                            ctx.emit(ctx.event(ChatEventType.THINKING_SIGNATURE)
                                    .rawType(eventType)
                                    .index(blockIndex)
                                    .text(signature)
                                    .raw(oResp)
                                    .build());
                        }
                    } else if ("text".equals(blockType)) {
                        // 思考到正文的边界由 ChatEventNormalizer 根据事件类型补齐。
                        acc.in_thinking = false;
                        String text = contentBlock.get("text").getString();
                        if (Utils.isNotEmpty(text)) {
                            ctx.emit(ctx.event(ChatEventType.TEXT_DELTA)
                                    .rawType(eventType)
                                    .index(oResp.get("index").getInt())
                                    .text(text)
                                    .raw(oResp)
                                    .build());
                            hasContent = true;
                        }
                        emitTextCitations(ctx, eventType, oResp.get("index").getInt(), contentBlock);
                    } else if ("tool_use".equals(blockType)) {
                        // 本地工具调用直接进入事件通道，避免 AssistantMessage 充当流式分片载体。
                        acc.in_thinking = false;
                        int blockIdx = oResp.get("index").getInt();
                        StreamToolState state = new StreamToolState();
                        state.toolUseId = contentBlock.get("id").getString();
                        state.toolName = contentBlock.get("name").getString();
                        state.toolInput = new StringBuilder();
                        getToolStates(acc, true).put(blockIdx, state);

                        String initialInput = initialToolInput(contentBlock);
                        state.initialInput = initialInput;
                        ctx.emit(ctx.event(ChatEventType.TOOL_CALL_START)
                                .rawType(eventType)
                                .toolCallId(state.toolUseId)
                                .itemId(state.toolUseId)
                                .index(blockIdx)
                                .toolCall(new ToolCall("idx:" + blockIdx, state.toolUseId, state.toolName, null, null))
                                .raw(oResp)
                                .build());
                        hasContent = true;
                    } else if ("redacted_thinking".equals(blockType)) {
                        // 安全过滤的推理内容块，原样保留供多轮回传（对齐 Anthropic SDK）
                        String data = contentBlock.get("data").getString();
                        if (Utils.isNotEmpty(data)) {
                            redactedThinkingData.append(data);
                            // opaque 数据块必须独立分块回传，拼接会损坏 base64（对齐 SDK：逐块保留）
                            getRedactedBlocks(acc, true).add(data);

                            // 旧实现下只能塞进 contentRaw.redactedThinkingBlocks，订阅方不可见
                            ctx.emit(ctx.event(ChatEventType.THINKING_REDACTED)
                                    .rawType(eventType)
                                    .index(oResp.get("index").getInt())
                                    .text(data)
                                    .raw(oResp)
                                    .build());
                        }
                    } else if ("server_tool_use".equals(blockType) || "mcp_tool_use".equals(blockType)) {
                        // 「服务端执行的工具调用」：input 经 input_json_delta 分片下发，必须登记 StreamToolState，
                        // 否则随后的参数分片因取不到状态而静默丢弃（既无 SERVER_TOOL_START 也无 SERVER_TOOL_ARGS_DELTA）。
                        // mcp_tool_use 一并收在这里：它的结果块 mcp_tool_result 会被下面的 _tool_result 后缀规则
                        // 捎带命中，把调用侧排除反而要额外加特例代码，还会让事件流出现「有结果无调用」的不自洽。
                        int serverBlockIdx = oResp.get("index").getInt();
                        ChatEventDefault.Builder serverToolStart = ctx.event(ChatEventType.SERVER_TOOL_START)
                                .rawType(eventType)
                                .subType(contentBlock.get("name").getString())
                                .itemId(contentBlock.get("id").getString())
                                .index(serverBlockIdx)
                                .raw(oResp);
                        appendServerToolCaller(serverToolStart, contentBlock);
                        ctx.emit(serverToolStart.build());

                        StreamToolState serverState = new StreamToolState();
                        serverState.serverTool = true;
                        serverState.toolUseId = contentBlock.get("id").getString();
                        serverState.toolName = contentBlock.get("name").getString();
                        serverState.toolInput = new StringBuilder();
                        getToolStates(acc, true).put(serverBlockIdx, serverState);

                        //start 已给出的 input 初值：不读则该调用的参数在事件流里完全缺失
                        String initialServerInput = initialToolInput(contentBlock);
                        serverState.initialInput = initialServerInput;

                        //原样留存供下一轮回传（pause_turn 续跑与缓存前缀稳定性）
                        captureServerToolBlock(acc, contentBlock);
                    } else if (blockType != null && blockType.endsWith("_tool_result")) {
                        // 服务端工具结果（web_search_tool_result / web_fetch_tool_result / mcp_tool_result 等）：
                        // 旧实现把结果内容直接拼进正文，订阅方无法与模型自述区分
                        ctx.emit(ctx.event(ChatEventType.SERVER_TOOL_RESULT)
                                .rawType(eventType)
                                .subType(blockType)
                                .itemId(contentBlock.get("tool_use_id").getString())
                                .index(oResp.get("index").getInt())
                                .text(extractToolResultText(contentBlock))
                                .raw(oResp)
                                .build());
                        emitWebSearchResults(ctx, eventType, oResp.get("index").getInt(), contentBlock);

                        //结果块含 encrypted_content 等无法重建的服务端凭证，必须原样留存
                        captureServerToolBlock(acc, contentBlock);
                    } else if ("container_upload".equals(blockType)) {
                        // 代码执行产出的文件（协议 ContainerUploadBlock，仅 file_id）：
                        // 旧实现不匹配任何分支被静默丢弃，file_id 对订阅方不可见
                        String fileId = contentBlock.get("file_id").getString();
                        ctx.emit(ctx.event(ChatEventType.SERVER_TOOL_RESULT)
                                .rawType(eventType)
                                .subType(blockType)
                                .itemId(fileId)
                                .index(oResp.get("index").getInt())
                                .text(fileId)
                                .raw(oResp)
                                .build());

                        captureServerToolBlock(acc, contentBlock);
                    } else if (Utils.isNotEmpty(blockType)) {
                        // 未建模内容块：与顶层未建模事件对称地以 RAW 透出。
                        // 这是 GA 前向兼容手段，不是为 Beta 建模：ContentBlock 的 GA 面本身就在逐步变长，
                        // 无兜底时新增类型是静默丢帧（无异常、无日志）。官方 SDK 同样给每个 union
                        // 留了 unknown(json) visitor。旧实现在此静默落空，是块级与事件级的不对称缺口
                        ctx.emit(ctx.event(ChatEventType.RAW)
                                .rawType(eventType)
                                .subType(blockType)
                                .index(oResp.get("index").getInt())
                                .raw(oResp)
                                .build());
                        //RAW 是已消费的合法模型帧，与顶层 RAW 一致地不让调用方误判为不可识别响应
                        hasContent = true;
                    }
                }
            } else if ("content_block_delta".equals(eventType)) {
                // 内容块增量更新
                ONode delta = oResp.get("delta");
                if (delta != null) {
                    String deltaType = delta.get("type").getString();
                    if ("thinking_delta".equals(deltaType)) {
                        updateStreamContentBlock(acc, oResp.get("index").getInt(), deltaType, delta);
                        String thinking = delta.get("thinking").getString();
                        if (Utils.isNotEmpty(thinking)) {
                            ctx.emit(ctx.event(ChatEventType.THINKING_DELTA)
                                    .rawType(eventType)
                                    .index(oResp.get("index").getInt())
                                    .text(thinking)
                                    .raw(oResp)
                                    .build());
                            hasContent = true;
                        }
                    } else if ("signature_delta".equals(deltaType)) {
                        int blockIndex = oResp.get("index").getInt();
                        String fragment = delta.get("signature").getString();
                        if (Utils.isNotEmpty(fragment)) {
                            String signature = appendStreamThinkingSignature(acc, blockIndex, fragment);
                            // 官方流只会发送一帧完整签名；兼容网关多帧拆分时，这里保存当前完整累计值。
                            acc.thinkingSignature = signature;

                            // THINKING_SIGNATURE 表达当前可回放的完整签名快照，而非网关分片，
                            // 因而最后一帧事件、acc 与终态 contentRaw 始终一致。
                            ctx.emit(ctx.event(ChatEventType.THINKING_SIGNATURE)
                                    .rawType(eventType)
                                    .index(blockIndex)
                                    .text(signature)
                                    .raw(oResp)
                                    .build());
                        }
                    } else if ("text_delta".equals(deltaType)) {
                        updateStreamContentBlock(acc, oResp.get("index").getInt(), deltaType, delta);
                        String text = delta.get("text").getString();
                        if (Utils.isNotEmpty(text)) {
                            ctx.emit(ctx.event(ChatEventType.TEXT_DELTA)
                                    .rawType(eventType)
                                    .index(oResp.get("index").getInt())
                                    .text(text)
                                    .raw(oResp)
                                    .build());
                            hasContent = true;
                        }
                    } else if ("citations_delta".equals(deltaType) || "citation_delta".equals(deltaType)) {
                        int blockIndex = oResp.get("index").getInt();
                        ONode citation = delta.getOrNull("citation");
                        boolean emitted = emitCitation(ctx, eventType, blockIndex, citation, oResp);
                        if (emitted) {
                            updateStreamContentBlock(acc, blockIndex, deltaType, delta);
                        }
                        if (citation != null && citation.isObject()) {
                            hasContent = true;
                        }
                    } else if ("input_json_delta".equals(deltaType)) {
                        updateStreamContentBlock(acc, oResp.get("index").getInt(), deltaType, delta);
                        // 工具调用参数增量更新，按需从 map 获取状态
                        String partialJson = delta.get("partial_json").getString();
                        if (Utils.isNotEmpty(partialJson)) {
                            Map<Integer, StreamToolState> states = getToolStates(acc, false);
                            if (states != null) {
                                // 按事件携带的 index 定位所属工具块
                                int deltaBlockIdx = oResp.get("index").getInt();
                                StreamToolState state = states.get(deltaBlockIdx);
                                if (state != null && state.initialInput == null) {
                                    if (state.serverTool) {
                                        ctx.emit(ctx.event(ChatEventType.SERVER_TOOL_ARGS_DELTA)
                                                .rawType(eventType)
                                                .toolCallId(state.toolUseId)
                                                .itemId(state.toolUseId)
                                                .index(deltaBlockIdx)
                                                .text(partialJson)
                                                .raw(oResp)
                                                .build());
                                    } else {
                                        ctx.emit(ctx.event(ChatEventType.TOOL_CALL_ARGS_DELTA)
                                                .rawType(eventType)
                                                .toolCallId(state.toolUseId)
                                                .itemId(state.toolUseId)
                                                .index(deltaBlockIdx)
                                                .text(partialJson)
                                                .raw(oResp)
                                                .build());
                                        hasContent = true;
                                    }
                                }
                            }
                        }
                    } else if (Utils.isNotEmpty(deltaType)) {
                        // 未建模增量：与块级、事件级的 RAW 兜底对称。
                        // 旧实现是一条无 else 的 if/else-if 链，未知 delta 整帧静默丢弃；
                        // GA 的 RawContentBlockDelta 现为 5 变体（已全覆盖），兜底是为它今后变长而留：
                        // 官方 SDK 同样给每个 union 留了 unknown(json) visitor 作为前向兼容手段
                        ctx.emit(ctx.event(ChatEventType.RAW)
                                .rawType(eventType)
                                .subType(deltaType)
                                .index(oResp.get("index").getInt())
                                .raw(oResp)
                                .build());
                        hasContent = true;
                    }
                }
            } else if ("content_block_stop".equals(eventType)) {
                // 内容块结束：按 index 精确定位并释放对应工具块状态。
                //
                // 本地 tool_use 的参数已由 TOOL_CALL_* 事件归并；TOOL_CALL_END 由核心在完整参数
                // 聚合并构建工具消息后统一发出，避免 content_block_stop 与核心各发一次。
                Map<Integer, StreamToolState> states = getToolStates(acc, false);
                if (states != null) {
                    int blockIdx = oResp.get("index").getInt();
                    StreamToolState state = states.remove(blockIdx);
                    if (state != null) {
                        String finalInput = resolveFinalToolInput(state);
                        if (state.initialInput != null && Utils.isNotEmpty(finalInput)) {
                            ChatEventType argsType = state.serverTool
                                    ? ChatEventType.SERVER_TOOL_ARGS_DELTA
                                    : ChatEventType.TOOL_CALL_ARGS_DELTA;
                            ctx.emit(ctx.event(argsType)
                                    .rawType(eventType)
                                    .toolCallId(state.toolUseId)
                                    .itemId(state.toolUseId)
                                    .index(blockIdx)
                                    .text(finalInput)
                                    .raw(oResp)
                                    .build());
                        }
                        updateStreamToolBlockInput(acc, blockIdx, finalInput);
                        if (state.serverTool) {
                            updateServerToolBlockInput(acc, state.toolUseId, finalInput);
                        }
                    }
                }
            } else if ("message_delta".equals(eventType)) {
                // message_delta.usage 是整条消息截至当前的累计快照。当前帧出现的字段覆盖旧值，
                // 缺失字段（例如仅 message_start 携带的 service_tier）继续保留；不能相加或取 max，
                // 否则服务端最终修正为更小值或显式 0 时会保留错误旧值。
                ONode usageNode = oResp.getOrNull("usage");
                if (usageNode != null && usageNode.isObject()) {
                    AiUsage prev = acc.getUsage();
                    ONode mergedSource = prev == null
                            ? usageNode
                            : mergeUsageSource(prev.getSource(), usageNode);
                    AiUsage usage = parseUsage(mergedSource);
                    if (usage != null) {
                        acc.setUsage(usage);
                    }
                }

                ONode delta = oResp.getOrNull("delta");
                if (delta != null) {
                    // 容器可能在此刷新（代码执行工具的沙盒续期）
                    captureContainer(acc, delta.getOrNull("container"));

                    String finishReason = delta.get("stop_reason").getString();
                    if (Utils.isNotEmpty(finishReason)) {
                        // stop_reason 是消息终态元数据，不是 SSE 完成信号；只有 message_stop（或兼容
                        // 网关的 [DONE]）才能证明所有 content block 已完整到达。
                        acc.lastFinishReason = finishReason;
                        emitStopReasonEvent(ctx, eventType, finishReason, delta, oResp);
                    }
                }
            } else if ("message_stop".equals(eventType)) {
                // 消息结束：收口终态（thinking 边界闭合走事件，签名载体走内容项）。
                // 工具状态同样由 flushPendingToolStates 统一兑底，不在此提前移除
                emitTerminalFrameOnce(ctx, eventType);

                acc.setFinished(true);
                hasContent = true;
            } else if ("ping".equals(eventType)) {
                // 心跳：旧实现直接丢弃；现在以事件透出（默认不投递给订阅方）
                ctx.emit(ctx.event(ChatEventType.HEARTBEAT)
                        .rawType(eventType)
                        .raw(oResp)
                        .build());
            } else if (Utils.isNotEmpty(eventType)) {
                // 未建模事件：旧实现静默丢弃，现在以 RAW 透出
                ctx.emit(ctx.event(ChatEventType.RAW)
                        .rawType(eventType)
                        .raw(oResp)
                        .build());
                //RAW 是已消费的合法模型帧，不能让调用方误判为不可识别响应。
                hasContent = true;
            }
        }

        return hasContent;
    }

    private static SearchResult parseSearchResult(ONode result) {
        SearchResult searchResult = new SearchResult()
                .title(result.get("title").getString())
                .url(result.get("url").getString());
        if (result.hasKey("id")) {
            searchResult.id(result.get("id").getString());
        }
        if (result.hasKey("index") && result.get("index").isNumber()) {
            searchResult.index(result.get("index").getInt());
        }
        if (result.hasKey("snippet")) {
            searchResult.snippet(result.get("snippet").getString());
        }
        return searchResult;
    }

    /**
     * 将 {@code web_search_tool_result.content[]} 中的标准搜索结果逐项投影为类型化事件。
     */
    private static void emitWebSearchResults(ChatStreamContext ctx, String rawType, int blockIndex,
                                             ONode contentBlock) {
        if (contentBlock == null
                || "web_search_tool_result".equals(contentBlock.get("type").getString()) == false) {
            return;
        }
        ONode content = contentBlock.getOrNull("content");
        if (content == null || content.isArray() == false) {
            return;
        }

        int resultIndex = -1;
        for (ONode resultNode : content.getArray()) {
            resultIndex++;
            if (resultNode == null || resultNode.isObject() == false
                    || "web_search_result".equals(resultNode.get("type").getString()) == false
                    || markTypedEvent(ctx.getAccumulator(), "search_result", blockIndex,
                    resultIndex, resultNode) == false) {
                continue;
            }

            SearchResult searchResult = parseSearchResult(resultNode);
            ChatEventDefault.Builder event = ctx.event(ChatEventType.SEARCH_RESULT)
                    .rawType(rawType)
                    .subType("web_search_result")
                    .index(blockIndex)
                    .text(extractResultEntryText(resultNode))
                    .searchResult(searchResult)
                    .raw(resultNode);
            if (Utils.isNotEmpty(searchResult.getId())) {
                event.itemId(searchResult.getId());
            }
            ctx.emit(event.build());
        }
    }

    /**
     * 提取服务端工具结果块内的可读文本。
     *
     * <p><b>为什么不能只找 {@code text} 字段</b>：Anthropic 各服务端工具的 {@code content} 都不是
     * {@code [{text:...}]} 形态，协议里根本没有 text 字段——
     * {@code web_search_tool_result} 是 {@code WebSearchResultBlock[]}（title/url/page_age/encrypted_content）、
     * {@code web_fetch_tool_result} 是单个 {@code WebFetchBlock} 对象（url/content:DocumentBlock/retrieved_at）、
     * {@code code_execution_tool_result} 是 {@code CodeExecutionResultBlock}（stdout/stderr/return_code/content）、
     * {@code tool_search_tool_result} 是 {@code tool_references[]}。
     * 旧实现按 text 取值，对真实服务端工具一律返回 null，订阅方只能自己去啃 {@code raw}。</p>
     *
     * <p>错误形态（{@code *_tool_result_error}，携带 {@code error_code}）同样在此归一，
     * 否则搜索失败时事件既无文本也无错误。</p>
     *
     * @since 4.1
     */
    private static String extractToolResultText(ONode contentBlock) {
        ONode resultContent = contentBlock.getOrNull("content");
        if (resultContent == null) {
            return null;
        }

        if (resultContent.isString()) {
            return Utils.isEmpty(resultContent.getString()) ? null : resultContent.getString();
        }

        if (resultContent.isArray()) {
            StringBuilder buf = new StringBuilder();
            for (ONode rb : resultContent.getArray()) {
                appendLine(buf, extractResultEntryText(rb));
            }
            return buf.length() == 0 ? null : buf.toString();
        }

        //单对象形态（web_fetch_result / code_execution_result / *_tool_result_error 等）
        return extractResultEntryText(resultContent);
    }

    /**
     * 按各服务端工具结果块的实际结构提取文本（单条目）。
     *
     * @since 4.1
     */
    private static String extractResultEntryText(ONode node) {
        if (node == null) {
            return null;
        }
        if (node.isString()) {
            return Utils.isEmpty(node.getString()) ? null : node.getString();
        }
        if (node.isObject() == false) {
            return null;
        }

        //通用 text（tool_result 的 text block、兼容网关的简化形态）
        String text = node.get("text").getString();
        if (Utils.isNotEmpty(text)) {
            return text;
        }

        //错误形态：web_search / web_fetch / code_execution / tool_search 共用 error_code(+error_message)
        String errorCode = node.get("error_code").getString();
        if (Utils.isNotEmpty(errorCode)) {
            String errorMessage = node.get("error_message").getString();
            return Utils.isEmpty(errorMessage) ? "[" + errorCode + "]" : "[" + errorCode + "] " + errorMessage;
        }

        //代码执行结果：stdout / stderr（含 bash / text_editor 变体）
        if (node.hasKey("stdout") || node.hasKey("stderr")) {
            StringBuilder buf = new StringBuilder();
            appendLine(buf, node.get("stdout").getString());
            appendLine(buf, node.get("stderr").getString());
            return buf.length() == 0 ? null : buf.toString();
        }

        //web_search_result: title + url；web_fetch_result: url + 内嵌 document 的 title
        String url = node.get("url").getString();
        String title = node.get("title").getString();
        if (Utils.isEmpty(title)) {
            ONode document = node.getOrNull("content");
            if (document != null && document.isObject()) {
                title = document.get("title").getString();
            }
        }
        if (Utils.isNotEmpty(title) && Utils.isNotEmpty(url)) {
            return title + " - " + url;
        }
        if (Utils.isNotEmpty(title)) {
            return title;
        }
        if (Utils.isNotEmpty(url)) {
            return url;
        }

        //tool_search 结果：tool_references[].tool_name
        ONode toolReferences = node.getOrNull("tool_references");
        if (toolReferences != null && toolReferences.isArray()) {
            StringBuilder buf = new StringBuilder();
            for (ONode ref : toolReferences.getArray()) {
                String toolName = ref.get("tool_name").getString();
                if (Utils.isNotEmpty(toolName)) {
                    if (buf.length() > 0) {
                        buf.append(", ");
                    }
                    buf.append(toolName);
                }
            }
            return buf.length() == 0 ? null : buf.toString();
        }

        //container_upload / code_execution_output：仅 file_id
        String fileId = node.get("file_id").getString();
        return Utils.isEmpty(fileId) ? null : fileId;
    }

    /**
     * 追加一段非空文本，多段之间用换行分隔。
     *
     * @since 4.1
     */
    private static void appendLine(StringBuilder buf, String text) {
        if (Utils.isNotEmpty(text)) {
            if (buf.length() > 0) {
                buf.append('\n');
            }
            buf.append(text);
        }
    }

    /**
     * 提取引用的可读文本。
     *
     * <p>协议 {@code TextCitation} 是 5 变体联合，只有 {@code web_search_result_location} 与
     * {@code search_result_location} 带 {@code url}；{@code char_location} / {@code page_location} /
     * {@code content_block_location} 是文档内定位，字段为 {@code cited_text} 与 {@code document_title}。
     * 旧实现固定取 url，文档类引用的 CITATION 事件文本一律为 null。</p>
     *
     * @since 4.1
     */
    private static String extractCitationText(ONode citation) {
        if (citation == null || citation.isObject() == false) {
            return null;
        }

        String url = citation.get("url").getString();
        if (Utils.isNotEmpty(url)) {
            return url;
        }

        String citedText = citation.get("cited_text").getString();
        if (Utils.isNotEmpty(citedText)) {
            return citedText;
        }

        String title = citation.get("title").getString();
        if (Utils.isEmpty(title)) {
            title = citation.get("document_title").getString();
        }
        return Utils.isEmpty(title) ? null : title;
    }

    /**
     * 白名单之外的 server_tool_use.caller：区分模型直调与代码执行内嵌调用。
     *
     * <p>协议 {@code ServerToolUseBlock.caller} 是三变体联合（{@code direct} /
     * {@code code_execution_20250825} / {@code code_execution_20260120}），后两者还携带
     * {@code tool_id}（发起该调用的代码执行块）。不读它就无法分辨 programmatic tool calling
     * 里究竟是谁发起的工具调用。</p>
     *
     * <p>宽容两种形态：对象（取 {@code type}）与网关简化后的纯字符串。</p>
     *
     * @since 4.1
     */
    private static void appendServerToolCaller(ChatEventDefault.Builder builder, ONode contentBlock) {
        ONode caller = contentBlock.getOrNull("caller");
        if (caller == null) {
            return;
        }

        if (caller.isString()) {
            if (Utils.isNotEmpty(caller.getString())) {
                builder.attr("caller", caller.getString());
            }
            return;
        }
        if (caller.isObject() == false) {
            return;
        }

        String callerType = caller.get("type").getString();
        if (Utils.isNotEmpty(callerType)) {
            builder.attr("caller", callerType);
        }
        String callerToolId = caller.get("tool_id").getString();
        if (Utils.isNotEmpty(callerToolId)) {
            builder.attr("callerToolId", callerToolId);
        }
    }

    /**
     * 停止原因的语义分流。
     *
     * <p>协议 {@code StopReason} 共 7 值：{@code end_turn} / {@code max_tokens} / {@code stop_sequence}
     * / {@code tool_use} / {@code pause_turn} / {@code refusal} / {@code model_context_window_exceeded}。
     * 旧实现只把原始串塞进 {@code lastFinishReason}，除 {@code end_turn} / {@code tool_use} 之外
     * 的语义对订阅方完全不可见。</p>
     *
     * <p>{@code lastFinishReason} 仍保留供应商原始值（不做归一，避免掩盖具体成因），这里只为原本静默的情形补事件：</p>
     * <ul>
     *   <li>{@code refusal} → {@code CONTENT_FILTER}，携带 {@code stop_details} 的分类与说明</li>
     *   <li>{@code pause_turn} → {@code STATUS}，服务端工具轮次暂停、需带上下文续跑</li>
     *   <li>{@code max_tokens} → {@code STATUS}，输出被截断（回答不完整，与正常收尾同为“成功”响应）</li>
     *   <li>{@code model_context_window_exceeded} → {@code STATUS}，上下文溢出</li>
     *   <li>{@code stop_sequence} → {@code STATUS}，透出实际命中的自定义停止序列（旧实现连这个值都不读）</li>
     * </ul>
     *
     * <p><b>为什么截断类停止原因用 STATUS 而不是 ERROR / ABORT</b>：</p>
     * <ul>
     *   <li>不用 {@code ERROR}：{@code max_tokens} 与 {@code model_context_window_exceeded} 下
     *   服务端返回的是一条完整合法、携带可用部分内容的消息。抬为 ERROR 会连带
     *   {@code acc.setError}，把已生成的正文与计费一同弃掉，属于倒退；</li>
     *   <li>不用 {@code ABORT}：ABORT 在事件归一化器里会提前关闭未闭合块，而 {@code message_delta}
     *   紧邻 {@code message_stop}，会与随后的终态收口帧（签名载体）抢块边界。</li>
     * </ul>
     *
     * @param stopNode 承载 stop_sequence / stop_details 的节点（流式为 delta，非流式为 message 本体）
     * @since 4.1
     */
    private void emitStopReasonEvent(ChatStreamContext ctx, String rawType, String stopReason,
                                     ONode stopNode, ONode raw) {
        if ("refusal".equals(stopReason)) {
            String category = null;
            String explanation = null;
            ONode stopDetails = stopNode == null ? null : stopNode.getOrNull("stop_details");
            if (stopDetails != null && stopDetails.isObject()) {
                category = stopDetails.get("category").getString();
                explanation = stopDetails.get("explanation").getString();
            }
            ctx.emit(ctx.event(ChatEventType.CONTENT_FILTER)
                    .rawType(rawType)
                    .subType(Utils.isEmpty(category) ? stopReason : category)
                    .text(explanation)
                    .raw(raw)
                    .build());
        } else if ("stop_sequence".equals(stopReason)) {
            ctx.emit(ctx.event(ChatEventType.STATUS)
                    .rawType(rawType)
                    .subType(stopReason)
                    .text(stopNode == null ? null : stopNode.get("stop_sequence").getString())
                    .raw(raw)
                    .build());
        } else if ("pause_turn".equals(stopReason)
                || "max_tokens".equals(stopReason)
                || "model_context_window_exceeded".equals(stopReason)) {
            ctx.emit(ctx.event(ChatEventType.STATUS)
                    .rawType(rawType)
                    .subType(stopReason)
                    .raw(raw)
                    .build());
        }
    }

    /**
     * 流式终态收口（幂等）：message_stop 与兼容网关的 [DONE] 双发场景下仅执行一次。
     *
     * <p>完成状态只在本方法内设置；message_delta 即使携带 stop_reason 也只更新消息元数据，
     * 不能掩盖缺失 message_stop 的截断流。</p>
     *
     * @param rawType 触发收口的原始事件类型（message_stop / [DONE]），仅用于事件溯源
     * @since 4.0.4
     */
    private void emitTerminalFrameOnce(ChatStreamContext ctx, String rawType) {
        ChatAccumulator acc = ctx.getAccumulator();

        if (acc.attrAs(TERMINAL_FRAME_EMITTED_KEY) == null) {
            acc.attrPut(TERMINAL_FRAME_EMITTED_KEY, Boolean.TRUE);
            emitTerminalFrame(ctx, acc, rawType);
        }
        acc.setFinished(true);
    }

    /**
     * 流式终态收口：内容主干已经由事件归并，终态载体只保存事件无法重建的协议字段。
     */
    private void emitTerminalFrame(ChatStreamContext ctx, ChatAccumulator acc, String rawType) {
        boolean wasThinking = acc.in_thinking;
        acc.in_thinking = false;

        if (wasThinking) {
            ctx.emit(ctx.event(ChatEventType.THINKING_END)
                    .rawType(rawType)
                    .build());
        }

        // 流中断兑底：缺 content_block_stop 的截断流（网关异常、连接断开）里
        // tool_use 参数仅停留在字符串累计，在此统一解析落定一次。
        flushPendingToolStates(acc);

        Map<String, Object> protocolData = null;
        if (Utils.isNotEmpty(acc.thinkingSignature)) {
            protocolData = new LinkedHashMap<>();
            protocolData.put("thinkingSignature", acc.thinkingSignature);
        }

        List<String> redactedBlocks = getRedactedBlocks(acc, false);
        if (Utils.isEmpty(redactedBlocks) == false) {
            if (protocolData == null) {
                protocolData = new LinkedHashMap<>();
            }
            protocolData.put("redactedThinkingBlocks", new ArrayList<>(redactedBlocks));
        }

        protocolData = appendServerToolRaw(acc, protocolData);
        if (protocolData == null) protocolData = new LinkedHashMap<>();
        appendStreamContentRaw(acc, protocolData);

        MessageProtocolState protocolState = AnthropicMessageStateSupport.createState(protocolData);
        if (protocolState != null) {
            acc.putTerminalProtocolState(AnthropicMessageStateSupport.PROTOCOL_ID, protocolState);
        }
    }

    /**
     * 终态兑底：对未收到 content_block_stop 的工具块补一次参数落定。
     *
     * <p>正常流由 content_block_stop 释放状态；截断流（异常断开、兼容网关缺帧）的
     * 状态残留到终态，这里统一补齐——否则回放载体里 tool_use.input 停留在 start 的空对象，
     * 下一轮回传会被服务端按 tool_use/tool_result 不配对拒掉。</p>
     *
     * @since 4.1
     */
    private static void flushPendingToolStates(ChatAccumulator acc) {
        Map<Integer, StreamToolState> states = toolStates(acc, false);
        if (states == null || states.isEmpty()) {
            return;
        }
        for (Map.Entry<Integer, StreamToolState> kv : states.entrySet()) {
            StreamToolState state = kv.getValue();
            String finalInput = resolveFinalToolInput(state);
            updateStreamToolBlockInput(acc, kv.getKey(), finalInput);
            if (state.serverTool) {
                updateServerToolBlockInput(acc, state.toolUseId, finalInput);
            }
        }
        acc.attrRemove(STREAM_TOOL_STATE_KEY);
    }

    /**
     * 解析非流式响应
     *
     * <p>与流式路径对称：服务端工具、引用、思考签名、redacted_thinking 走事件通道，
     * 不再降级拼进正文——否则同一服务端行为下 {@code call()} 的聚合正文会含
     * {@code "[server tool: ...]"} 与搜索结果原文，而 {@code stream()} 不含。</p>
     *
     * @since 4.1
     */
    public boolean parseNonStreamResponse(ChatStreamContext ctx, String json) {
        ChatAccumulator acc = ctx.getAccumulator();

        if ("[DONE]".equals(json)) {
            if (acc.isFinished() == false) {
                acc.setTerminalMessage(new AssistantMessage("", ""));
                acc.setFinished(true);
            }
            return true;
        }

        ONode oResp = new JsonReader(json).readNext();
        if (oResp.isObject() == false) {
            return false;
        }

        if (oResp.hasKey("error") && !oResp.get("error").isNull()) {
            acc.setError(parseAnthropicError(oResp.get("error")));
            ctx.emit(ctx.event(ChatEventType.ERROR)
                    .rawType("error")
                    .error(acc.getError())
                    .raw(oResp)
                    .build());
            return true;
        }

        StringBuilder redactedThinkingData = acc.attrIfAbsent(REDACTED_THINKING_DATA_KEY, (k) -> new StringBuilder());

        // 设置模型信息与供应商响应标识；必须在内容事件发射前完成，确保事件自动携带 message id。
        acc.setModel(oResp.get("model").getString());
        ctx.setProviderResponseId(oResp.get("id").getString());
        // 代码执行容器（协议 Message.container）：与流式 message_start 对称地记录，供多轮复用
        captureContainer(acc, oResp.getOrNull("container"));
        // 先解析 stop_reason 供 lastFinishReason 使用；但停止事件延到内容块遍历之后再发，
        // 以保持与流式同序：content_block_* 在前、message_delta 的 stop 在后。
        // 否则按事件序做状态机的订阅方会看到 call() 与 stream() 行为分叉
        String stopReason = oResp.get("stop_reason").getString();

        // 解析内容
        ONode contentArray = oResp.getOrNull("content");
        // finishReason 在外层作用域声明，供后续 lastFinishReason 同步使用
        String choiceFinishReason = Utils.isNotEmpty(stopReason)
                ? stopReason
                : "stop";
        if (contentArray != null && contentArray.isArray()) {
            // 分离思考内容、普通内容、媒体与工具调用
            StringBuilder thinkingContent = new StringBuilder();
            String thinkingSignature = null;
            StringBuilder normalContent = new StringBuilder();
            List<ContentBlock> mediaBlocks = new ArrayList<>();
            List<ToolCall> allToolCalls = new ArrayList<>();
            List<String> redactedBlocks = new ArrayList<>();
            int blockIndex = -1;

            List<String> orderedContentBlocks = new ArrayList<>();
            for (ONode contentItem : contentArray.getArray()) {
                orderedContentBlocks.add(contentItem.toJson());
                blockIndex++;
                String contentType = contentItem.get("type").getString();
                if ("thinking".equals(contentType)) {
                    String thinking = contentItem.get("thinking").getString();
                    if (Utils.isNotEmpty(thinking)) {
                        thinkingContent.append(thinking);
                        ctx.emit(ctx.event(ChatEventType.THINKING_DELTA)
                                .rawType(contentType)
                                .index(blockIndex)
                                .text(thinking)
                                .raw(contentItem)
                                .build());
                    }
                    // 保留 thinking signature，供多轮回传（非流式此前会丢失）
                    String signature = contentItem.get("signature").getString();
                    if (Utils.isNotEmpty(signature)) {
                        thinkingSignature = signature;
                        acc.thinkingSignature = signature;

                        // 与流式 signature_delta 对称：给出专用事件通道
                        ctx.emit(ctx.event(ChatEventType.THINKING_SIGNATURE)
                                .rawType(contentType)
                                .index(blockIndex)
                                .text(signature)
                                .raw(contentItem)
                                .build());
                    }
                } else if ("text".equals(contentType)) {
                    String text = contentItem.get("text").getString();
                    if (Utils.isNotEmpty(text)) {
                        normalContent.append(text);
                        // 非流式也按 content index 进入事件有序块；否则同一响应只要含媒体，
                        // 终态会优先采用媒体事件列表并遮蔽未事件化的文本。
                        ctx.emit(ctx.event(ChatEventType.TEXT_DELTA)
                                .rawType(contentType)
                                .index(blockIndex)
                                .text(text)
                                .raw(contentItem)
                                .build());
                    }
                    //与流式 citations_delta 对称：非流式的引用直接内嵌在 text 块的 citations 数组里，
                    //旧实现只读 text 字段，使得 call() 根本看不到引用（而 stream() 看得到）
                    emitTextCitations(ctx, contentType, blockIndex, contentItem);
                } else if ("image".equals(contentType)) {
                    ContentBlock imageBlock = parseClaudeImageBlock(contentItem);
                    if (imageBlock != null) {
                        mediaBlocks.add(imageBlock);
                        // 媒体统一走事件通道；协议 index 是合法重复媒体的身份，不能按内容自行去重。
                        ctx.emit(ctx.event(ChatEventType.MEDIA_DONE)
                                .rawType(contentType)
                                .index(blockIndex)
                                .block(imageBlock)
                                .raw(contentItem)
                                .build());
                    }
                } else if ("tool_use".equals(contentType)) {
                    String toolName = contentItem.get("name").getString();
                    String toolId = contentItem.get("id").getString();
                    ONode inputNode = contentItem.get("input");
                    Map<String, Object> arguments = new HashMap<>();
                    // 网关/兼容实现可能缺省 input 字段，兜底空对象避免 NPE（对齐 SDK 的 Optional 语义）
                    String inputJson = "{}";
                    if (inputNode != null && inputNode.isObject()) {
                        arguments = inputNode.toBean(Map.class);
                        inputJson = inputNode.toJson();
                    }

                    ToolCall toolCall = new ToolCall(toolId, toolId, toolName, inputJson, arguments);
                    allToolCalls.add(toolCall);

                    // 非流式没有核心流收口补位，必须在完整响应内保留完整工具调用生命周期。
                    ctx.emit(ctx.event(ChatEventType.TOOL_CALL_START)
                            .rawType(contentType)
                            .toolCallId(toolId)
                            .itemId(toolId)
                            .index(blockIndex)
                            .toolCall(new ToolCall("idx:" + blockIndex, toolId, toolName, null, null))
                            .raw(contentItem)
                            .build());
                    if (Utils.isNotEmpty(inputJson) && arguments.isEmpty() == false) {
                        ctx.emit(ctx.event(ChatEventType.TOOL_CALL_ARGS_DELTA)
                                .rawType(contentType)
                                .toolCallId(toolId)
                                .itemId(toolId)
                                .index(blockIndex)
                                .text(inputJson)
                                .raw(contentItem)
                                .build());
                    }
                    ctx.emit(ctx.event(ChatEventType.TOOL_CALL_END)
                            .rawType(contentType)
                            .toolCallId(toolId)
                            .itemId(toolId)
                            .index(blockIndex)
                            .toolCall(toolCall)
                            .raw(contentItem)
                            .build());


                } else if ("redacted_thinking".equals(contentType)) {
                    // 安全过滤的推理内容块：opaque data，逐块原样保留供多轮回传（对齐 Anthropic SDK）
                    String data = contentItem.get("data").getString();
                    if (Utils.isNotEmpty(data)) {
                        redactedThinkingData.append(data);
                        redactedBlocks.add(data);

                        //与流式对称：旧实现只能塞进 contentRaw.redactedThinkingBlocks
                        ctx.emit(ctx.event(ChatEventType.THINKING_REDACTED)
                                .rawType(contentType)
                                .index(blockIndex)
                                .text(data)
                                .raw(contentItem)
                                .build());
                    }
                } else if ("server_tool_use".equals(contentType) || "mcp_tool_use".equals(contentType)) {
                    // 服务端工具（web_search/code_execution 等）：走事件通道。
                    // 旧实现在此拼 "[server tool: name]" 进正文，导致 call() 与 stream() 的聚合正文分叉
                    ChatEventDefault.Builder serverToolStart = ctx.event(ChatEventType.SERVER_TOOL_START)
                            .rawType(contentType)
                            .subType(contentItem.get("name").getString())
                            .itemId(contentItem.get("id").getString())
                            .index(blockIndex)
                            .raw(contentItem);
                    appendServerToolCaller(serverToolStart, contentItem);
                    ctx.emit(serverToolStart.build());

                    // 非流式 server-only 工具同样交付完整参数增量，且绝不进入本地 tool_calls。
                    String serverInput = initialToolInput(contentItem);
                    if (serverInput != null) {
                        ctx.emit(ctx.event(ChatEventType.SERVER_TOOL_ARGS_DELTA)
                                .rawType(contentType).toolCallId(contentItem.get("id").getString())
                                .itemId(contentItem.get("id").getString()).index(blockIndex)
                                .text(serverInput).raw(contentItem).build());
                    }

                    //原样留存供下一轮回传（与流式对称）
                    captureServerToolBlock(acc, contentItem);
                } else if (contentType != null && contentType.endsWith("_tool_result")) {
                    // web_search_tool_result / web_fetch_tool_result 等：
                    // 旧实现把结果原文拍平进正文，订阅方无法与模型自述区分
                    ctx.emit(ctx.event(ChatEventType.SERVER_TOOL_RESULT)
                            .rawType(contentType)
                            .subType(contentType)
                            .itemId(contentItem.get("tool_use_id").getString())
                            .index(blockIndex)
                            .text(extractToolResultText(contentItem))
                            .raw(contentItem)
                            .build());
                    emitWebSearchResults(ctx, contentType, blockIndex, contentItem);

                    captureServerToolBlock(acc, contentItem);
                } else if ("container_upload".equals(contentType)) {
                    // 与流式对称：代码执行产出的文件（仅 file_id）
                    String fileId = contentItem.get("file_id").getString();
                    ctx.emit(ctx.event(ChatEventType.SERVER_TOOL_RESULT)
                            .rawType(contentType)
                            .subType(contentType)
                            .itemId(fileId)
                            .index(blockIndex)
                            .text(fileId)
                            .raw(contentItem)
                            .build());

                    captureServerToolBlock(acc, contentItem);
                } else if (Utils.isNotEmpty(contentType)) {
                    // 与流式对称：未建模内容块以 RAW 透出，不再静默丢弃
                    ctx.emit(ctx.event(ChatEventType.RAW)
                            .rawType(contentType)
                            .subType(contentType)
                            .index(blockIndex)
                            .raw(contentItem)
                            .build());
                }
            }

            // 构建完整非流式终态消息；纯思考语义由 text/thinking 字段自然表达。
            String textStr = normalContent.toString();
            String thinkingStr = thinkingContent.toString();

            Map<String, Object> protocolData = null;
            if (Utils.isNotEmpty(thinkingSignature)) {
                protocolData = new LinkedHashMap<>();
                protocolData.put("thinkingSignature", thinkingSignature);
            }

            // redacted_thinking 分块列表进入 Anthropic 命名空间状态，供多轮逐块回传（拼接会损坏 opaque 数据）
            if (!redactedBlocks.isEmpty()) {
                if (protocolData == null) {
                    protocolData = new LinkedHashMap<>();
                }
                protocolData.put("redactedThinkingBlocks", redactedBlocks);
            }

            // 服务端工具原始块与代码执行容器：pause_turn 续跑与多轮服务端工具要求原样回传，
            // 不存就只能重跑（搜索/抓取重新计费），且历史前缀每轮缺块还会拉低 prompt cache 命中率
            protocolData = appendServerToolRaw(acc, protocolData);
            if (!orderedContentBlocks.isEmpty()) {
                if (protocolData == null) protocolData = new LinkedHashMap<>();
                protocolData.put(CONTENT_BLOCKS_RAW_KEY, orderedContentBlocks);
            }

            List<ContentBlock> blocksForMsg = null;
            if (!mediaBlocks.isEmpty()) {
                blocksForMsg = new ArrayList<>();
                if (Utils.isNotEmpty(textStr)) {
                    // 多模态时用 result 文本投影（不含思考内容）
                    blocksForMsg.add(TextBlock.of(textStr));
                }
                blocksForMsg.addAll(mediaBlocks);
            }

            // finishReason：优先用真实 stop_reason；tool 场景兜底 tool_use
            choiceFinishReason = Utils.isNotEmpty(stopReason)
                    ? stopReason
                    : (!allToolCalls.isEmpty() ? "tool_use" : "stop");

            // 构建完整非流式终态消息；纯思考语义由 text/thinking 字段自然表达。
            AssistantMessage msg = new AssistantMessage(textStr, thinkingStr,
                    allToolCalls.isEmpty() ? null : allToolCalls, blocksForMsg);
            acc.setTerminalMessage(msg);
            MessageProtocolState protocolState = AnthropicMessageStateSupport.createState(protocolData);
            if (protocolState != null) {
                acc.putTerminalProtocolState(AnthropicMessageStateSupport.PROTOCOL_ID, protocolState);
            }
        } else {
            // 响应没有 content 时也要建立空终态，保持完成响应的消息契约。
            acc.setTerminalMessage(new AssistantMessage("", ""));
        }
        // 同步 lastFinishReason（复用已算好的 choiceFinishReason，避免重复计算）
        acc.lastFinishReason = choiceFinishReason;

        // 与流式 message_delta 对称：refusal / stop_sequence / pause_turn / max_tokens /
        // model_context_window_exceeded 补事件通道（非流式的 stop_sequence 与 stop_details
        // 位于 message 本体的顶层）。放在内容块事件之后，与流式的事件序一致
        if (Utils.isNotEmpty(stopReason)) {
            emitStopReasonEvent(ctx, "message", stopReason, oResp, oResp);
        }

        // 解析用量信息
        AiUsage usage = parseUsage(oResp.getOrNull("usage"));
        if (usage != null) {
            acc.setUsage(usage);
        }
        acc.setFinished(true);
        return true;
    }

    /**
     * 解析 Claude content 中的 image 块。
     *
     * @since 3.9
     */
    ContentBlock parseClaudeImageBlock(ONode contentItem) {
        if (contentItem == null) {
            return null;
        }
        ONode source = contentItem.getOrNull("source");
        if (source == null || !source.isObject()) {
            // 兼容直接 url/data
            String url = contentItem.get("url").getString();
            String data = contentItem.get("data").getString();
            if (Utils.isNotEmpty(data)) {
                return ImageBlock.ofBase64(data);
            }
            if (Utils.isNotEmpty(url)) {
                return ImageBlock.ofUrl(url);
            }
            return null;
        }

        String sourceType = source.get("type").getString();
        String mediaType = source.get("media_type").getString();
        if (Utils.isEmpty(mediaType)) {
            mediaType = source.get("mediaType").getString();
        }

        if ("base64".equals(sourceType) || source.hasKey("data")) {
            String data = source.get("data").getString();
            if (Utils.isEmpty(data)) {
                return null;
            }
            return Utils.isEmpty(mediaType) ? ImageBlock.ofBase64(data) : ImageBlock.ofBase64(data, mediaType);
        }

        if ("url".equals(sourceType) || source.hasKey("url")) {
            String url = source.get("url").getString();
            if (Utils.isEmpty(url)) {
                return null;
            }
            return Utils.isEmpty(mediaType) ? ImageBlock.ofUrl(url) : ImageBlock.ofUrl(url, mediaType);
        }

        return null;
    }
}