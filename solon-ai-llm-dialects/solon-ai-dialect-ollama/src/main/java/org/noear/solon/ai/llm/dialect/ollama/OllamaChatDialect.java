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
package org.noear.solon.ai.llm.dialect.ollama;

import org.noear.snack4.Feature;
import org.noear.snack4.ONode;
import org.noear.snack4.Options;
import org.noear.snack4.json.JsonReader;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.*;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.chat.content.AudioBlock;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatException;
import org.noear.solon.ai.chat.ChatAccumulator;
import org.noear.solon.ai.chat.dialect.AbstractChatDialect;
import org.noear.solon.ai.chat.event.ChatEventType;
import org.noear.solon.ai.chat.event.ChatStreamContext;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.chat.tool.ToolCallBuilder;
import org.noear.solon.ai.chat.tool.ToolCallJsonSanitizer;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.content.VideoBlock;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ollama 聊天模型方言
 *
 * @author noear
 * @since 3.1
 */
public class OllamaChatDialect extends AbstractChatDialect {

    private static final Logger LOG = LoggerFactory.getLogger(OllamaChatDialect.class);

    private static OllamaChatDialect instance = new OllamaChatDialect();

    public static OllamaChatDialect getInstance() {
        return instance;
    }

    /**
     * 匹配检测
     *
     * @param config 聊天配置
     */
    @Override
    public boolean matched(ChatConfig config) {
        String standard = config.getStandardOrProvider();

        return "ollama".equalsIgnoreCase(standard) ||
                (Assert.isEmpty(standard) && config.getApiUrl().endsWith("/api/chat"));
    }

    @Override
    protected String getApiUrl(ChatConfig config) {

        //处理后缀#
        int index = config.getApiUrl().indexOf('#');
        if (index > 0) {
            return config.getApiUrl().substring(0, index);
        }

        //自动补全地址
        if (config.getApiUrl().endsWith("/api/chat")) {
            return config.getApiUrl();
        } else {
            if (config.getApiUrl().endsWith("/")) {
                return config.getApiUrl() + "api/chat";
            } else {
                return config.getApiUrl() + "/api/chat";
            }
        }
    }

    @Override
    protected void buildUserMessageNodeDo(ChatConfig config, ONode oNode, UserMessage msg) {
        oNode.set("role", msg.getRole().name().toLowerCase());
        if (msg.isMultiModal() == false) {
            //单模态
            oNode.set("content", msg.getContent());
        } else {
            //多模态：content 为字符串，媒体走侧车数组
            oNode.set("content", msg.getContent());
            appendOllamaMediaArrays(oNode, msg.getBlocks());
        }
    }

    /**
     * Assistant 回传对齐 Ollama：content 字符串 + images/audios/videos，而非 OpenAI content 数组。
     *
     * @since 3.9
     */
    @Override
    protected void buildAssistantMessageNodeDo(ChatConfig config, ONode oNode, AssistantMessage msg) {
        oNode.set("role", msg.getRole().name().toLowerCase());

        if (Utils.isNotEmpty(msg.getText())) {
            oNode.set("content", msg.getText());
        } else {
            oNode.set("content", "");
        }

        if (msg.isMultiModal()) {
            appendOllamaMediaArrays(oNode, msg.getBlocks());
        }

        // Ollama 原生历史思考字段由目标方言固定为 thinking，不采用源消息字段名。
        if (Utils.isNotEmpty(msg.getThinking())) {
            oNode.set("thinking", msg.getThinking());
        }

        List<ToolCall> outboundToolCalls = ToolCallJsonSanitizer.resolveToolCalls(
                msg.getToolCalls(), msg.getToolCallsRaw());
        if (Utils.isNotEmpty(outboundToolCalls)) {
            oNode.getOrNew("tool_calls").asArray().then(array -> {
                for (ToolCall call : outboundToolCalls) {
                    ONode callNode = array.addNew();
                    if (Utils.isNotEmpty(call.getId())) {
                        callNode.set("id", call.getId());
                    }
                    callNode.set("type", "function");
                    callNode.getOrNew("function")
                            .set("name", call.getName())
                            .set("arguments", ONode.ofJson(ToolCallJsonSanitizer.sanitizeArguments(call)));
                }
            });
        }
    }

    /**
     * 解析 Assistant：补读 Ollama 侧车 images/audios/videos，以及 thinking 字段。
     *
     * @since 3.9
     */
    @Override
    public List<AssistantMessage> parseAssistantMessage(ChatAccumulator acc, ONode oMessage) {
        // Ollama think 模式字段为 thinking，映射到通用 reasoning 管线
        if (oMessage != null
                && !oMessage.hasKey("reasoning")
                && !oMessage.hasKey("reasoning_content")
                && oMessage.hasKey("thinking")) {
            String thinking = oMessage.get("thinking").getString();
            if (Utils.isNotEmpty(thinking)) {
                oMessage.set("reasoning", thinking);
            }
        }

        List<AssistantMessage> messageList = super.parseAssistantMessage(acc, oMessage);
        List<ContentBlock> mediaBlocks = parseOllamaMediaSidecars(oMessage);
        if (Utils.isEmpty(mediaBlocks)) {
            return messageList;
        }

        List<AssistantMessage> result = new ArrayList<>(messageList.size());
        boolean mediaMerged = false;
        for (AssistantMessage msg : messageList) {
            // 非流式完整响应可在同一消息保留文本、思考、工具与媒体；
            // 流式下仍让媒体避开 thinking/tool_calls 事件载体。
            if (!mediaMerged
                    && (!acc.isStream() || (Utils.isEmpty(msg.getThinkingRaw())
                    && Utils.isEmpty(msg.getToolCalls())))) {
                List<ContentBlock> blocks = new ArrayList<>();
                if (Utils.isNotEmpty(msg.getTextRaw())) {
                    blocks.add(TextBlock.of(msg.getTextRaw()));
                }
                // 保留已有 blocks （若有）再追加侧车媒体
                if (Utils.isNotEmpty(msg.getBlocks())) {
                    for (ContentBlock b : msg.getBlocks()) {
                        if (!(b instanceof TextBlock)) {
                            blocks.add(b);
                        }
                    }
                }
                blocks.addAll(mediaBlocks);
                AssistantMessage merged = AssistantMessage.snapshot(
                        msg.getTextRaw(),
                        msg.getThinkingRaw(),
                        msg.getToolCalls(),
                        blocks,
                        msg.getSearchResults(),
                        msg.getCitations(),
                        null,
                        msg.hasMetadata() ? msg.getMetadata() : null);
                result.add(merged);
                mediaMerged = true;
            } else {
                result.add(msg);
            }
        }

        // 若只有 thinking/tool 消息，补一条带媒体的空文本消息
        if (!mediaMerged) {
            result.add(new AssistantMessage("", "", null, mediaBlocks));
        }

        return result;
    }

    /**
     * 将 blocks 拆为 Ollama images/audios/videos 侧车数组。
     */
    private void appendOllamaMediaArrays(ONode oNode, List<ContentBlock> blocks) {
        if (Utils.isEmpty(blocks)) {
            return;
        }
        for (ContentBlock block1 : blocks) {
            // Session 截断后空媒体跳过，避免写出 null/空串侧车
            if (!isMediaBlockPlayable(block1)) {
                continue;
            }
            String data = block1.toDataString(false);
            if (Utils.isEmpty(data)) {
                continue;
            }
            if (block1 instanceof ImageBlock) {
                oNode.getOrNew("images").add(data);
            } else if (block1 instanceof AudioBlock) {
                oNode.getOrNew("audios").add(data);
            } else if (block1 instanceof VideoBlock) {
                oNode.getOrNew("videos").add(data);
            }
        }
    }

    /**
     * 解析 Ollama message 侧车媒体数组。
     */
    private List<ContentBlock> parseOllamaMediaSidecars(ONode oMessage) {
        List<ContentBlock> mediaBlocks = new ArrayList<>();
        if (oMessage == null) {
            return mediaBlocks;
        }

        appendSidecarMedia(mediaBlocks, oMessage.getOrNull("images"), "image");
        appendSidecarMedia(mediaBlocks, oMessage.getOrNull("audios"), "audio");
        appendSidecarMedia(mediaBlocks, oMessage.getOrNull("videos"), "video");
        return mediaBlocks;
    }

    private void appendSidecarMedia(List<ContentBlock> mediaBlocks, ONode arrayNode, String mediaType) {
        if (arrayNode == null || !arrayNode.isArray()) {
            return;
        }
        for (ONode item : arrayNode.getArray()) {
            String value = item.getString();
            if (Utils.isEmpty(value)) {
                continue;
            }
            // value 可能是纯 base64、data URL 或 http(s) URL
            ContentBlock block;
            if (value.startsWith("http://") || value.startsWith("https://") || value.startsWith("data:")) {
                block = createMediaBlock(mediaType, value, null, null);
            } else {
                block = createMediaBlock(mediaType, null, value, null);
            }
            if (block != null) {
                mediaBlocks.add(block);
            }
        }
    }

    @Override
    public ONode buildAssistantToolCallMessageNode(ChatAccumulator acc, Map<String, ToolCallBuilder> toolCallBuilders) {
        ONode oNode = new ONode();
        oNode.set("role", "assistant");
        oNode.set("content", acc.getAggregationText());
        if (Utils.isNotEmpty(acc.getAggregationThinking())) {
            oNode.set("thinking", acc.getAggregationThinking());
        }
        oNode.getOrNew("tool_calls").asArray().then(n1 -> {
            for (Map.Entry<String, ToolCallBuilder> kv : toolCallBuilders.entrySet()) {
                //有可能没有
                n1.addNew().set("id", kv.getValue().idBuilder.toString())
                        .set("type", "function")
                        .getOrNew("function").then(n2 -> {
                            n2.set("name", kv.getValue().nameBuilder.toString());
                            // 流式聚合出口净化：截断串会导致 ofJson 异常或非法节点，禁止原样入历史
                            n2.set("arguments", ONode.ofJson(ToolCallJsonSanitizer.sanitizeArguments(
                                    kv.getValue().argumentsBuilder.toString(),
                                    kv.getValue().nameBuilder.toString())));
                        });
            }
        });

        return oNode;
    }

    /**
     * 解析响应（事件形态）
     *
     * <p>Ollama chat 协议的流式帧只承载内容增量（正文 / 思考 / 工具调用分片），
     * <p>正文、思考与工具调用由方言直接翻译为 TEXT_DELTA / THINKING_DELTA / TOOL_CALL_*，
     * 终态统一由 ChatAccumulator 聚合。</p>
     *
     * @since 4.1
     */
    @Override
    public void parseResponseJson(ChatStreamContext ctx, String data) {
        ChatAccumulator acc = ctx.getAccumulator();

        //解析（每帧只解析一次 JSON：正文解析与错误事件共用同一份节点）
        ONode oResp = ONode.ofJson(data);

        if (oResp.isObject() == false) {
            return;
        }

        parseFrameNode(ctx, acc, oResp);

        if (acc.getError() != null) {
            ctx.emit(ctx.event(org.noear.solon.ai.chat.event.ChatEventType.ERROR)
                    .rawType("error")
                    .error(acc.getError())
                    .raw(oResp)
                    .build());
        }
    }

    /**
     * 解析一帧（已解析好的 JSON 节点）
     *
     * @since 4.1
     */
    private void parseFrameNode(ChatStreamContext ctx, ChatAccumulator acc, ONode oResp) {
        if (oResp.hasKey("error")) {
            acc.setError(new ChatException(oResp.get("error").getString()));
        } else {
            acc.setModel(oResp.get("model").getString());
            acc.setFinished(oResp.get("done").getBoolean());
            String done_reason = oResp.get("done_reason").getString();

            List<AssistantMessage> messageList = parseAssistantMessage(acc, oResp.get("message"));
            Map<String, Integer> frameOccurrences = new LinkedHashMap<>();
            for (AssistantMessage msg1 : messageList) {
                if (acc.isStream()) {
                    // Ollama 部分实现返回 arguments 累计快照；仅在显式 snapshot 模式下裁剪。
                    AssistantMessage eventMessage = normalizeToolCallArgumentDeltas(ctx, msg1);
                    AssistantMessage eventCarrier = withoutMediaBlocks(eventMessage);
                    publishAssistantMessageEvents(ctx, eventCarrier);
                    if (eventMessage != msg1) {
                        // 参数事件只携带增量；终态工具载体仍保留服务端当前完整值。
                        acc.mergeTerminalMessage(withoutMediaBlocks(msg1));
                    }
                    emitMediaDoneEvents(ctx, msg1.getBlocks(), frameOccurrences);
                } else {
                    acc.setTerminalMessage(msg1);
                    emitMediaDoneEvents(ctx, msg1.getBlocks(), frameOccurrences);
                }
            }

            if (Utils.isNotEmpty(done_reason)) {
                acc.lastFinishReason = done_reason;
            }

            if (acc.isFinished()) {
                if (oResp.hasKey("prompt_eval_count") || oResp.hasKey("eval_count")) {
                    long promptTokens = oResp.get("prompt_eval_count").getLong();
                    long completionTokens = oResp.get("eval_count").getLong();
                    long totalTokens = promptTokens + completionTokens;

                    acc.setUsage(new AiUsage(promptTokens, 0L, completionTokens, totalTokens, oResp));
                }

                if (acc.isStream() == false && acc.isTerminalMessagePresent() == false) {
                    acc.setTerminalMessage(new AssistantMessage(""));
                }
            }
        }
    }

    @Override
    protected List<ToolCall> parseToolCalls(ChatAccumulator acc, ONode toolCallsNode) {
        if (toolCallsNode == null || toolCallsNode.isArray() == false) {
            return null;
        }

        List<ToolCall> toolCalls = new ArrayList<>();
        int position = 0;
        for (ONode toolCallNode : toolCallsNode.getArray()) {
            toolCalls.add(parseToolCall(acc, toolCallNode, position++));
        }
        return toolCalls;
    }

    @Override
    protected ToolCall parseToolCall(ChatAccumulator acc, ONode n1) {
        return parseToolCall(acc, n1, 0);
    }

    private ToolCall parseToolCall(ChatAccumulator acc, ONode n1, int position) {
        String callId = n1.get("id").getString();//可能是空的

        ONode n1f = n1.get("function");
        String name = n1f.get("name").getString();
        ONode n1fArgs = n1f.get("arguments");
        boolean structuredSnapshot = n1fArgs.isObject();
        String argStr = n1fArgs.getString();

        // 调用身份：顶层 index > function.index > 稳定 id > 当前帧数组位置。
        // 函数名不是调用身份，否则同名并行调用会错误合并。
        String index = readToolCallIndex(n1.getOrNull("index"));
        if (index == null) {
            index = readToolCallIndex(n1f.getOrNull("index"));
        }
        if (index == null) {
            index = Utils.isNotEmpty(callId) ? callId : "idx:" + position;
        }

        if (n1fArgs.isValue()) {
            //有可能是 json string（还可能只是流的中间消息）
            if (hasNestedJsonBlock(argStr)) {
                JsonReader reader = new JsonReader(argStr, Options.of(Feature.Read_AutoRepair));
                n1fArgs = reader.readLast();

                if (n1fArgs == null) {
                    LOG.warn("Parse tool arguments failed: {}", argStr);
                }
            }
        }

        Map<String, Object> argMap = null;
        if (n1fArgs != null) {
            if (n1fArgs.isObject()) {
                argMap = n1fArgs.toBean(Map.class);
            }
        }

        return new OllamaToolCall(index, callId, name, argStr, argMap, structuredSnapshot);
    }

    private String readToolCallIndex(ONode indexNode) {
        if (indexNode == null || indexNode.isValue() == false) {
            return null;
        }

        String value = indexNode.getString();
        if (Utils.isEmpty(value)) {
            return null;
        }
        return value.startsWith("idx:") ? value : "idx:" + value;
    }

    private AssistantMessage normalizeToolCallArgumentDeltas(ChatStreamContext ctx, AssistantMessage message) {
        if (Utils.isEmpty(message.getToolCalls())) {
            return message;
        }

        Map<String, ToolArgumentsState> states = ctx.attrIfAbsent(
                "__ollamaToolArgumentStates", k -> new LinkedHashMap<String, ToolArgumentsState>());
        boolean snapshotMode = "snapshot".equalsIgnoreCase(String.valueOf(
                ctx.getRequest().getOptions().option("ollama_tool_arguments_mode")));
        List<ToolCall> deltaCalls = new ArrayList<>(message.getToolCalls().size());
        for (ToolCall call : message.getToolCalls()) {
            String stateKey = ctx.getStep() + ":" + call.getIndex();
            ToolArgumentsState state = states.computeIfAbsent(stateKey, k -> new ToolArgumentsState(snapshotMode));
            String delta = state.normalize(call.getArgumentsStr(),
                    call instanceof OllamaToolCall && ((OllamaToolCall) call).structuredSnapshot);
            ToolCall deltaCall = new ToolCall(call.getIndex(), call.getId(), call.getName(), delta, call.getArguments());
            deltaCalls.add(deltaCall);
        }

        return AssistantMessage.snapshot(
                message.getTextRaw(),
                message.getThinkingRaw(),
                deltaCalls,
                message.getBlocks(),
                message.getSearchResults(),
                message.getCitations(),
                null,
                message.hasMetadata() ? message.getMetadata() : null);
    }

    private AssistantMessage withoutMediaBlocks(AssistantMessage message) {
        if (message == null || Utils.isEmpty(message.getBlocks())) {
            return message;
        }
        return AssistantMessage.snapshot(
                message.getTextRaw(),
                message.getThinkingRaw(),
                ToolCallJsonSanitizer.resolveToolCalls(
                        message.getToolCalls(), message.getToolCallsRaw()),
                null,
                message.resolveSearchResults(),
                message.getCitations(),
                null,
                message.hasMetadata() ? message.getMetadata() : null);
    }

    private void emitMediaDoneEvents(ChatStreamContext ctx, List<ContentBlock> blocks,
                                     Map<String, Integer> frameOccurrences) {
        if (Utils.isEmpty(blocks)) {
            return;
        }

        boolean deltaMode = "delta".equalsIgnoreCase(String.valueOf(
                ctx.getRequest().getOptions().option("ollama_media_mode")));
        Set<String> emitted = deltaMode ? null
                : ctx.attrIfAbsent("__ollamaEmittedMedia", k -> new LinkedHashSet<String>());
        int index = 0;
        for (ContentBlock block : blocks) {
            if (block == null || block instanceof TextBlock) {
                index++;
                continue;
            }
            String baseKey = block.getClass().getName() + ':' + block.getMimeType() + ':' + block.getContent();
            Integer occurrence = frameOccurrences.get(baseKey);
            int rank = occurrence == null ? 0 : occurrence;
            frameOccurrences.put(baseKey, rank + 1);
            String key = baseKey + ':' + rank;
            if (emitted == null || emitted.add(key)) {
                ctx.emit(ctx.event(ChatEventType.MEDIA_DONE).index(index).block(block).build());
            }
            index++;
        }
    }

    private static class OllamaToolCall extends ToolCall {
        private final boolean structuredSnapshot;

        private OllamaToolCall(String index, String id, String name, String argumentsStr,
                               Map<String, Object> arguments, boolean structuredSnapshot) {
            super(index, id, name, argumentsStr, arguments);
            this.structuredSnapshot = structuredSnapshot;
        }
    }

    private static class ToolArgumentsState {
        private final StringBuilder accumulated = new StringBuilder();
        private boolean snapshotMode;

        private ToolArgumentsState(boolean snapshotMode) {
            this.snapshotMode = snapshotMode;
        }

        private String normalize(String current, boolean structuredSnapshot) {
            if (Utils.isEmpty(current)) {
                return current;
            }

            String delta = current;
            int len = accumulated.length();
            if (len > 0) {
                if ((snapshotMode || structuredSnapshot) && startsWithAccumulated(current)) {
                    snapshotMode = true;
                    delta = current.substring(len);
                }
            }

            accumulated.append(delta);
            return delta;
        }

        private boolean startsWithAccumulated(String value) {
            int len = accumulated.length();
            if (value.length() < len) {
                return false;
            }
            for (int i = 0; i < len; i++) {
                if (value.charAt(i) != accumulated.charAt(i)) {
                    return false;
                }
            }
            return true;
        }
    }

    @Override
    public ONode buildRequestJson(ChatConfig config, ChatOptions options, List<ChatMessage> messages, boolean isStream) {
        return new ONode().then(n -> {
            // 复用父类的通用逻辑
            if (Utils.isNotEmpty(config.getModel())) {
                n.set("model", config.getModel());
            }

            n.getOrNew("messages").then(n1 -> {
                for (ChatMessage m1 : messages) {
                    n1.add(buildChatMessageNode(config, m1));
                }
            });

            n.set("stream", isStream);

            CacheControl cacheControl = options.cacheControl();

            // ⭐ Ollama 模型驻留时间控制（keep_alive 决定 KV Cache 的保留时间）
            //    默认 5 分钟，确保后续请求能复用缓存
            Object keepAlive = options.option("keep_alive");
            if (keepAlive != null) {
                n.set("keep_alive", keepAlive);
            } else {
                if (cacheControl != null && cacheControl.getTtl() != null) {
                    n.set("keep_alive", cacheControl.getTtl()); // 5m
                }
            }

            // ⭐ 支持 prompt_cache_key (OpenAI 兼容模式下的 Prompt Caching)
            if (cacheControl != null && Utils.isNotEmpty(cacheControl.getPromptCacheKey())) {
                n.set("prompt_cache_key", cacheControl.getPromptCacheKey());
            }

            for (Map.Entry<String, Object> kv : options.options().entrySet()) {
                String key = kv.getKey();
                Object value = kv.getValue();
                // 统一 thinking(Boolean) 映射到 Ollama 原生 think；内部键不得透传。
                // 调用方显式 optionSet("think", ...) 时保留其原生值并优先。
                if ("thinking".equals(key)) {
                    if (value instanceof Boolean && options.options().containsKey("think") == false) {
                        n.set("think", value);
                    }
                    continue;
                }
                // 方言本地控制项只影响入站解析，不能透传给 Ollama 供应商。
                if ("ollama_tool_arguments_mode".equals(key)
                        || "ollama_media_mode".equals(key)) {
                    continue;
                }
                n.set(key, ONode.ofBean(value));
            }

            ChatMessage lastMessage = messages.get(messages.size() - 1);
            buildReqToolsNode(n, config, options, lastMessage);
        });
    }
}