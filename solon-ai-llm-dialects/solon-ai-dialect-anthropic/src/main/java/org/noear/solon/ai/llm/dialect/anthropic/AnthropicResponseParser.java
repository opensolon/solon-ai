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

import org.noear.snack4.Feature;
import org.noear.snack4.ONode;
import org.noear.snack4.Options;
import org.noear.snack4.json.JsonReader;
import org.noear.snack4.json.util.FormatUtil;
import org.noear.solon.Utils;
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.chat.ChatException;
import org.noear.solon.ai.chat.ChatAccumulator;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.event.ChatEventType;
import org.noear.solon.ai.chat.event.ChatStreamContext;
import org.noear.solon.ai.chat.message.AssistantMessage;
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

    /**
     * Claude 思考内容对应的统一推理字段名（与非流式 parseNonStreamResponse 保持一致）
     */
    private static final String REASONING_FIELD_THINKING = "thinking";

    /**
     * 终态帧已发射标记（message_stop 与兼容网关 [DONE] 双发时防重复补位）
     */
    private static final String TERMINAL_FRAME_EMITTED_KEY = "AnthropicTerminalFrameEmitted";

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
     * 解析 usage 信息（包含 Prompt Caching 统计）
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
        if (usageNode.hasKey("cache_creation_input_tokens")) {
            cacheCreationInputTokens = usageNode.get("cache_creation_input_tokens").getLong();
        }
        if (usageNode.hasKey("cache_read_input_tokens")) {
            cacheReadInputTokens = usageNode.get("cache_read_input_tokens").getLong();
        }
        // Anthropic 的 input_tokens 不含缓存部分，需将 cache 两项并入，归一为“全部输入 token”语义（与 OpenAI prompt_tokens 对齐），
        // 否则下游 cacheRate = cacheRead / promptTokens 会被高估并恒定 100%
        long totalInputTokens = inputTokens + cacheCreationInputTokens + cacheReadInputTokens;
        // 只有在有实际 token 消耗时才返回 usage
        if (inputTokens > 0 || outputTokens > 0 || cacheCreationInputTokens > 0 || cacheReadInputTokens > 0) {
            return new AiUsage(totalInputTokens, 0L, outputTokens, totalInputTokens + outputTokens,
                    cacheCreationInputTokens, cacheReadInputTokens, usageNode);
        }

        return null;
    }

    /**
     * 深度合并两个 usage source 节点：数值字段取 max，对象字段递归合并，
     * 保留 message_start 中的嵌套计费明细（cache_creation/server_tool_use/output_tokens_details）。
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
            ONode oldValue = merged.getOrNull(kv.getKey());
            ONode newValue = kv.getValue();
            if (oldValue == null || oldValue.isNull()) {
                merged.set(kv.getKey(), ONode.ofJson(newValue.toJson()));
            } else if (oldValue.isObject() && newValue.isObject()) {
                merged.set(kv.getKey(), mergeUsageSource(oldValue, newValue));
            } else if (oldValue.isNumber() && newValue.isNumber()) {
                merged.set(kv.getKey(), Math.max(oldValue.getLong(), newValue.getLong()));
            }
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

    /**
     * 解析流式响应
     *
     * <p>内容主干（正文 / 思考 / 工具调用）仍以内容项表达，由核心统一转事件与边界；
     * 本方法额外发射「旧实现下只能丢弃、拼进正文或寄生 contentRaw」的事件。</p>
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
                acc.attrRemove(STREAM_TOOL_STATE_KEY);
                emitTerminalFrameOnce(ctx, "[DONE]");
                return true;
            }

            ONode oResp = new JsonReader(jsonData).readNext();
            if (oResp.isObject() == false) {
                continue;
            }

            if (oResp.hasKey("error")) {
                acc.setError(new ChatException(oResp.get("error").getString()));
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
                String errorType = oError.get("type").getString();
                String errorMsg = oError.get("message").getString();
                if (Utils.isEmpty(errorMsg)) {
                    errorMsg = oError.getString();
                }

                // 构建详细的错误信息
                String detailedError = errorMsg;
                if (Utils.isNotEmpty(errorType)) {
                    detailedError = String.format("[%s] %s", errorType, errorMsg);
                }

                acc.setError(new ChatException(detailedError));
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

                    // 某些情况下 message_start 也包含初始 usage 信息
                    AiUsage usage = parseUsage(message.getOrNull("usage"));
                    if (usage != null) {
                        acc.setUsage(usage);
                    }
                }
            } else if ("content_block_start".equals(eventType)) {
                ONode contentBlock = oResp.get("content_block");
                if (contentBlock != null) {
                    String blockType = contentBlock.get("type").getString();
                    if ("thinking".equals(blockType)) {
                        // 思考内容块开始
                        if (!acc.in_thinking) {
                            // 第一次进入思考模式，添加开始标记；同步统一推理字段名（供后续闭合帧/聚合复用）
                            acc.reasoning_field_name = REASONING_FIELD_THINKING;
                            acc.addContentItem(new AssistantMessage("", "", true).reasoningFieldName(REASONING_FIELD_THINKING));
                            acc.in_thinking = true;
                            hasContent = true;
                        }
                        String thinking = contentBlock.get("thinking").getString();
                        if (Utils.isNotEmpty(thinking)) {
                            acc.addContentItem(new AssistantMessage("", thinking, true).reasoningFieldName(REASONING_FIELD_THINKING));
                            hasContent = true;
                        }
                    } else if ("text".equals(blockType)) {
                        // 如果之前在思考模式，添加结束标记
                        if (acc.in_thinking) {
                            acc.addContentItem(new AssistantMessage("", "", true).reasoningFieldName(acc.reasoning_field_name));
                            acc.in_thinking = false;
                            hasContent = true;
                        }
                        String text = contentBlock.get("text").getString();
                        if (Utils.isNotEmpty(text)) {
                            acc.addContentItem(new AssistantMessage(text, "", false).reasoningFieldName(acc.reasoning_field_name));
                            hasContent = true;
                        }
                    } else if ("tool_use".equals(blockType)) {
                        // 如果之前在思考模式，添加结束标记
                        if (acc.in_thinking) {
                            acc.addContentItem(new AssistantMessage("", "", true).reasoningFieldName(acc.reasoning_field_name));
                            acc.in_thinking = false;
                            hasContent = true;
                        }
                        StreamToolState state = new StreamToolState();
                        state.toolUseId = contentBlock.get("id").getString();
                        state.toolName = contentBlock.get("name").getString();
                        state.toolInput = new StringBuilder();

                        // 按块 index 存储（协议：事件均携带 index，支持多块并行）
                        int blockIdx = oResp.get("index").getInt();
                        getToolStates(acc, true).put(blockIdx, state);
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
                    } else if ("server_tool_use".equals(blockType)) {
                        ctx.emit(ctx.event(ChatEventType.SERVER_TOOL_START)
                                .rawType(eventType)
                                .subType(contentBlock.get("name").getString())
                                .itemId(contentBlock.get("id").getString())
                                .index(oResp.get("index").getInt())
                                .raw(oResp)
                                .build());
                        StreamToolState serverState = new StreamToolState();
                        serverState.serverTool = true;
                        serverState.toolUseId = contentBlock.get("id").getString();
                        serverState.toolName = contentBlock.get("name").getString();
                        serverState.toolInput = new StringBuilder();
                        getToolStates(acc, true).put(oResp.get("index").getInt(), serverState);
                    } else if (blockType != null && blockType.endsWith("_tool_result")) {
                        // 服务端工具结果（web_search_tool_result / web_fetch_tool_result 等）：
                        // 旧实现把结果内容直接拼进正文，订阅方无法与模型自述区分
                        ctx.emit(ctx.event(ChatEventType.SERVER_TOOL_RESULT)
                                .rawType(eventType)
                                .subType(blockType)
                                .itemId(contentBlock.get("tool_use_id").getString())
                                .index(oResp.get("index").getInt())
                                .text(extractToolResultText(contentBlock))
                                .raw(oResp)
                                .build());
                    }
                }
            } else if ("content_block_delta".equals(eventType)) {
                // 内容块增量更新
                ONode delta = oResp.get("delta");
                if (delta != null) {
                    String deltaType = delta.get("type").getString();
                    if ("thinking_delta".equals(deltaType)) {
                        // 思考内容增量更新
                        // 注意：不能在此处直接 append reasoningBuilder。core 的 publishResponse 会通过
                        // AssistantMessage(thinking, true).getReasoning() 统一追加一次，若此处再追加，
                        // 同一 chunk 会被写两次，导致推理内容逐块重复（如 "用户用户要求要求"、
                        // "solsolononcodecode"），且换网关依旧复现（根因在解析器与 core 的交互）。
                        String thinking = delta.get("thinking").getString();
                        if (Utils.isNotEmpty(thinking)) {
                            acc.addContentItem(new AssistantMessage("", thinking, true).reasoningFieldName(REASONING_FIELD_THINKING));
                            hasContent = true;
                        }
                    } else if ("signature_delta".equals(deltaType)) {
                        String signature = delta.get("signature").getString();
                        if (Utils.isNotEmpty(signature)) {
                            // 幂等覆盖，不能追加：签名是整个 thinking 块的一次性凭证，
                            // 拼接后的值在下一轮回传时会被服务端判为无效，多轮思考链直接断裂
                            acc.thinkingSignature = signature;

                            // 旧实现下签名只能寄生在 contentRaw 的 "thinkingSignature" 键上，
                            // 订阅方无从辨识；此处给出专用事件通道（与 contentRaw 出站通道并存）
                            ctx.emit(ctx.event(ChatEventType.THINKING_SIGNATURE)
                                    .rawType(eventType)
                                    .index(oResp.get("index").getInt())
                                    .text(signature)
                                    .raw(oResp)
                                    .build());
                        }
                    } else if ("text_delta".equals(deltaType)) {
                        String text = delta.get("text").getString();
                        if (Utils.isNotEmpty(text)) {
                            acc.addContentItem(new AssistantMessage(text, "", false).reasoningFieldName(acc.reasoning_field_name));
                            hasContent = true;
                        }
                    } else if ("citations_delta".equals(deltaType) || "citation_delta".equals(deltaType)) {
                        ONode citation = delta.getOrNull("citation");
                        String citationText = citation == null ? null : citation.get("url").getString();
                        ctx.emit(ctx.event(ChatEventType.CITATION)
                                .rawType(eventType)
                                .index(oResp.get("index").getInt())
                                .text(citationText)
                                .raw(oResp)
                                .build());
                    } else if ("input_json_delta".equals(deltaType)) {
                        // 工具调用参数增量更新，按需从 map 获取状态
                        String partialJson = delta.get("partial_json").getString();
                        if (Utils.isNotEmpty(partialJson)) {
                            Map<Integer, StreamToolState> states = getToolStates(acc, false);
                            if (states != null) {
                                // 按事件携带的 index 定位所属工具块
                                StreamToolState state = states.get(oResp.get("index").getInt());
                                if (state != null) {
                                    state.toolInput.append(partialJson);
                                    if (state.serverTool) {
                                        ctx.emit(ctx.event(ChatEventType.SERVER_TOOL_ARGS_DELTA)
                                                .rawType(eventType)
                                                .toolCallId(state.toolUseId)
                                                .itemId(state.toolUseId)
                                                .index(oResp.get("index").getInt())
                                                .text(partialJson)
                                                .raw(oResp)
                                                .build());
                                    }
                                }
                            }
                        }
                    }
                }
            } else if ("content_block_stop".equals(eventType)) {
                // 内容块结束：按 index 精确定位并清理对应工具块状态
                Map<Integer, StreamToolState> states = getToolStates(acc, false);
                StreamToolState state = null;
                if (states != null) {
                    state = states.remove(oResp.get("index").getInt());
                }
                if (state != null && state.serverTool) {
                    // 服务端工具不是本地 function call，不应被拼入 ChatAccumulator 的工具调用。
                    continue;
                }
                if (state != null) {
                    try {
                        // 流式解析出口净化：截断损坏的 arguments 禁止入历史（input_json_delta 中断场景）
                        String argStr = ToolCallJsonSanitizer.sanitizeArguments(
                                state.toolInput.toString(), state.toolName);
                        Map<String, Object> arguments = new HashMap<>();

                        if (FormatUtil.hasNestedJsonBlock(argStr)) {
                            JsonReader reader = new JsonReader(argStr, Options.of(Feature.Read_AutoRepair));
                            ONode n1fArgs = reader.readLast();

                            if (n1fArgs == null) {
                                LOG.warn("Parse tool arguments failed: {}", argStr);
                            } else if (n1fArgs.isObject()) {
                                arguments = n1fArgs.toBean(Map.class);
                            }
                        }

                        // 创建工具调用对象
                        ToolCall toolCall = new ToolCall(state.toolUseId, state.toolUseId, state.toolName, argStr, arguments);

                        // 创建带有工具调用的助手消息
                        List<Map> toolCallsRaw = new ArrayList<>();
                        Map<String, Object> toolCallRaw = new HashMap<>();
                        toolCallRaw.put("id", state.toolUseId);
                        toolCallRaw.put("type", "function");
                        Map<String, Object> functionData = new HashMap<>();
                        functionData.put("name", state.toolName);
                        functionData.put("arguments", argStr);
                        toolCallRaw.put("function", functionData);
                        toolCallsRaw.add(toolCallRaw);

                        List<ToolCall> toolCalls = new ArrayList<>();
                        toolCalls.add(toolCall);
                        // 终态工具消息同样携带统一推理字段名，保证聚合消息与非流式行为一致
                        AssistantMessage assistantMessage = new AssistantMessage("", "",
                                false, null, toolCallsRaw,
                                toolCalls, null).reasoningFieldName(acc.reasoning_field_name);
                        acc.addContentItem(assistantMessage);
                        hasContent = true;
                    } catch (Exception e) {
                        LOG.warn("Failed to parse tool call in stream mode", e);
                    }
                }
            } else if ("message_delta".equals(eventType)) {
                // 消息增量更新，包含停止原因和用量信息
                // 协议规范（RawMessageStreamEvent）：message_delta.usage 仅携带累计的 output_tokens，
                // input_tokens / cache_* 统计只在 message_start.usage 中出现，直接覆盖会丢失输入侧计费数据，
                // 因此按字段取 max 合并（output_tokens 为累计值只会增大；input/cache 字段沿用 message_start 的值）
                AiUsage usage = parseUsage(oResp.get("usage"));
                if (usage != null) {
                    AiUsage prev = acc.getUsage();
                    if (prev != null) {
                        usage = new AiUsage(
                                Math.max(prev.promptTokens(), usage.promptTokens()),
                                0L,
                                Math.max(prev.completionTokens(), usage.completionTokens()),
                                Math.max(prev.totalTokens(), usage.totalTokens()),
                                Math.max(prev.cacheCreationInputTokens(), usage.cacheCreationInputTokens()),
                                Math.max(prev.cacheReadInputTokens(), usage.cacheReadInputTokens()),
                                // 保留 message_start 中的嵌套计费明细（cache_creation/server_tool_use/output_tokens_details）
                                mergeUsageSource(prev.getSource(), usage.getSource()));
                    }
                    acc.setUsage(usage);
                }

                ONode stopReason = oResp.get("delta");
                if (stopReason != null) {
                    String finishReason = stopReason.get("stop_reason").getString();
                    if (Utils.isNotEmpty(finishReason)) {
                        acc.setFinished(true);
                        acc.lastFinishReason = finishReason;
                    }
                }
            } else if ("message_stop".equals(eventType)) {
                // 消息结束：清理状态并收口终态（thinking 边界闭合走事件，签名载体走内容项）
                acc.attrRemove(STREAM_TOOL_STATE_KEY);
                emitTerminalFrameOnce(ctx, eventType);

                acc.setFinished(true);
                hasContent = true;
            } else if ("ping".equals(eventType)) {
                // 心跳：旧实现直接 continue（整帧丢弃）；现在以事件透出（默认不投递给订阅方）
                ctx.emit(ctx.event(ChatEventType.HEARTBEAT)
                        .rawType(eventType)
                        .raw(oResp)
                        .build());
                continue;
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

    /**
     * 提取服务端工具结果块内的文本
     *
     * @since 4.1
     */
    private static String extractToolResultText(ONode contentBlock) {
        ONode resultContent = contentBlock.getOrNull("content");
        if (resultContent == null) {
            return null;
        }

        if (resultContent.isString()) {
            return resultContent.getString();
        }

        if (resultContent.isArray()) {
            StringBuilder buf = new StringBuilder();
            for (ONode rb : resultContent.getArray()) {
                String text = rb.get("text").getString();
                if (Utils.isNotEmpty(text)) {
                    buf.append(text);
                }
            }
            return buf.length() == 0 ? null : buf.toString();
        }

        return null;
    }

    /**
     * 流式终态收口（幂等）：message_stop 与兼容网关的 [DONE] 双发场景下仅执行一次。
     *
     * <p>不依赖 acc.isFinished() 判断：message_delta(stop_reason) 会先行置 finished，
     * 若以它为标记，后续 [DONE] 里的收口（thinkingSignature 载体）会被误跳过。</p>
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
     * 流式终态收口
     *
     * <p><b>为什么不再用「空 AssistantMessage」编码全部终态语义</b>：核心把内容项统一映射成
     * TEXT_DELTA / THINKING_DELTA（{@code ChatRequestDescDefault#buildItemEvent}），一个
     * {@code isThinking=false} 的空内容项因此会变成一条文本为空的幻影 TEXT_DELTA，污染订阅方的
     * 正文流；仅思考的流里它还会在流末凭空开出一个正文块。所以这里按语义分流：</p>
     * <ol>
     *   <li><b>thinking 边界闭合</b> → 显式 {@code THINKING_END} 事件。END 相位不计入核心
     *       「本帧已按事件形态表达内容」的门控（见 {@code ChatEventType#isMainContent}），
     *       不会连带丢弃本帧内容项；若此刻没有未闭合的思考块，归一化器会自行丢弃它，安全。</li>
     *   <li><b>finishReason 透传</b> → 不再补帧。finishReason 走 {@code acc.lastFinishReason}
     *       进入 STEP_END / RESPONSE_END 的终态聚合，空帧对它没有任何贡献。</li>
     *   <li><b>thinkingSignature 载体</b> → 仍必须留在内容项通道：核心终态聚合以
     *       {@code lastItem().getContentRaw()} 作为聚合消息的 contentRaw，出站侧
     *       {@code AnthropicRequestBuilder#resolveThinkingSignature} 正是从这里取签名重建 thinking 块；
     *       而累积器每帧被 reset，只有「终态帧里的最后一个内容项」能活到聚合期——载体既不能提前，
     *       也不能取消，否则非工具的多轮 extended thinking 断链。</li>
     *   <li><b>空流补位</b> → 整条流什么都没产出时保留一个空内容项，使聚合消息不为 null
     *       （否则记忆与 STEP_END 的 getMessage() 从「空消息」变成 null，属于对外契约变更）。</li>
     * </ol>
     *
     * <p><b>残留代价</b>：载体 / 补位帧本身仍会被核心映射成一条空内容事件——只要内容项还在，
     * 方言侧就无法在不改核心的前提下让它不产生事件（核心的门控只认 START/DELTA/CHUNK 主干事件，
     * 为触发门控而补发一个假的主干事件比幻影本身更糟）。因此这里把它归到「流末当前所在的块」：
     * 思考中就发思考帧、正文中就发正文帧，至少不会关掉当前块又开一个新块。</p>
     *
     * @since 4.0.4
     */
    private void emitTerminalFrame(ChatStreamContext ctx, ChatAccumulator acc, String rawType) {
        boolean wasThinking = acc.in_thinking;
        // 思考边界在此收口：核心 onEventEnd 见到 in_thinking=false 就不会再补一帧
        acc.in_thinking = false;

        // 工具多轮走另一条出站路径（buildAssistantToolCallMessageNode 直接取 acc.thinkingSignature
        // 与聚合思考文本），终态内容项对它毫无贡献，只会多一条空内容事件
        boolean toolPath = acc.hasToolCallBuilders();

        // (a) 签名载体：非工具的多轮回传只能从聚合消息的 contentRaw 取签名
        boolean signatureCarrier = Utils.isNotEmpty(acc.thinkingSignature);
        // (b) 仅思考无正文：核心的终态聚合只在「有内容项」分支按 text/thinking 计算 isThinking，
        //     一个内容项都没有时会硬编码 false，聚合消息的 getContent() 会从思考文本变成空串
        boolean thinkingOnly = acc.getAggregationText().isEmpty()
                && acc.getAggregationThinking().isEmpty() == false;
        // (c) 空流补位：本帧内容项、聚合正文/思考、媒体全空，才算「整条流什么都没产出」
        //     （hasContentItems 判本帧：核心逐帧 reset，方言单测直连解析入口时不会 reset）
        boolean emptyStream = acc.hasContentItems() == false
                && acc.getAggregationText().isEmpty()
                && acc.getAggregationThinking().isEmpty()
                && Utils.isEmpty(acc.getMediaBlocks());

        if (toolPath == false && (signatureCarrier || thinkingOnly || emptyStream)) {
            Map<String, Object> contentRaw = null;
            if (signatureCarrier) {
                // 供核心终态聚合取 lastItem().getContentRaw() 时携带签名，下一轮据此重建 thinking 块
                contentRaw = new LinkedHashMap<>();
                contentRaw.put("thinkingSignature", acc.thinkingSignature);
            }

            // isThinking 跟随流末所在的块，避免为一个空帧关掉当前块又开一个新块；
            // 这也意味着此处不能再发 THINKING_END：载体帧随后会被核心映射成 THINKING_DELTA，
            // 排在 END 之后会让归一化器重开一个思考块（订阅方多看到一对空的思考边界）
            AssistantMessage carrier = new AssistantMessage("", "",
                    wasThinking, contentRaw, null, null, null, null)
                    .reasoningFieldName(acc.reasoning_field_name);
            acc.addContentItem(carrier);
        } else if (wasThinking) {
            // 没有载体帧的仅思考流（max_tokens 截断等）：思考闭合改用显式事件表达，
            // 不再拿空 AssistantMessage 编码边界（那会被核心映射成幻影 TEXT_DELTA）
            ctx.emit(ctx.event(ChatEventType.THINKING_END)
                    .rawType(rawType)
                    .build());
        }
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
                acc.addContentItem(new AssistantMessage("").reasoningFieldName(acc.reasoning_field_name));
                acc.setFinished(true);
            }
            return true;
        }

        ONode oResp = new JsonReader(json).readNext();
        if (oResp.isObject() == false) {
            return false;
        }

        if (oResp.hasKey("error") && !oResp.get("error").isNull()) {
            ONode oError = oResp.get("error");
            String errorType = oError.get("type").getString();
            String errorMsg = oError.get("message").getString();
            if (Utils.isEmpty(errorMsg)) {
                errorMsg = oError.getString();
            }
            // 构建详细的错误信息
            String detailedError = errorMsg;
            if (Utils.isNotEmpty(errorType)) {
                detailedError = String.format("[%s] %s", errorType, errorMsg);
            }
            acc.setError(new ChatException(detailedError));
            ctx.emit(ctx.event(ChatEventType.ERROR)
                    .rawType("error")
                    .error(acc.getError())
                    .raw(oResp)
                    .build());
            return true;
        }

        StringBuilder redactedThinkingData = acc.attrIfAbsent(REDACTED_THINKING_DATA_KEY, (k) -> new StringBuilder());

        // 设置模型信息
        acc.setModel(oResp.get("model").getString());
        // 先解析 stop_reason，供 lastFinishReason 使用
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
            List<Map> allToolCallsRaw = new ArrayList<>();
            List<String> redactedBlocks = new ArrayList<>();
            //服务端工具块标记：正文可能为空，但仍需保留内容项以透传 finishReason
            boolean hasServerToolBlocks = false;
            int blockIndex = -1;

            for (ONode contentItem : contentArray.getArray()) {
                blockIndex++;
                String contentType = contentItem.get("type").getString();
                if ("thinking".equals(contentType)) {
                    String thinking = contentItem.get("thinking").getString();
                    if (Utils.isNotEmpty(thinking)) {
//                        if (thinkingContent.length() > 0) {
//                            thinkingContent.append("\n");
//                        }
                        thinkingContent.append(thinking);
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
//                        if (normalContent.length() > 0) {
//                            normalContent.append("\n");
//                        }
                        normalContent.append(text);
                    }
                } else if ("image".equals(contentType)) {
                    ContentBlock imageBlock = parseClaudeImageBlock(contentItem);
                    if (imageBlock != null) {
                        mediaBlocks.add(imageBlock);
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

                    allToolCalls.add(new ToolCall(toolId, toolId, toolName, inputJson, arguments));

                    Map<String, Object> toolCallRaw = new HashMap<>();
                    toolCallRaw.put("id", toolId);
                    toolCallRaw.put("type", "function");
                    Map<String, Object> functionData = new HashMap<>();
                    functionData.put("name", toolName);
                    functionData.put("arguments", inputJson);
                    toolCallRaw.put("function", functionData);
                    allToolCallsRaw.add(toolCallRaw);
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
                } else if ("server_tool_use".equals(contentType)) {
                    // 服务端工具（web_search/code_execution 等）：走事件通道。
                    // 旧实现在此拼 "[server tool: name]" 进正文，导致 call() 与 stream() 的聚合正文分叉
                    hasServerToolBlocks = true;
                    ctx.emit(ctx.event(ChatEventType.SERVER_TOOL_START)
                            .rawType(contentType)
                            .subType(contentItem.get("name").getString())
                            .itemId(contentItem.get("id").getString())
                            .index(blockIndex)
                            .raw(contentItem)
                            .build());
                } else if (contentType != null && contentType.endsWith("_tool_result")) {
                    // web_search_tool_result / web_fetch_tool_result 等：
                    // 旧实现把结果原文拍平进正文，订阅方无法与模型自述区分
                    hasServerToolBlocks = true;
                    ctx.emit(ctx.event(ChatEventType.SERVER_TOOL_RESULT)
                            .rawType(contentType)
                            .subType(contentType)
                            .itemId(contentItem.get("tool_use_id").getString())
                            .index(blockIndex)
                            .text(extractToolResultText(contentItem))
                            .raw(contentItem)
                            .build());
                }
            }

            // 构建 AssistantMessage：text/thinking 分离（新接口），不再注入 <think> 标签；
            // 仅思考无正文时 isThinking=true，与流式聚合（getAggregationMessage）语义对齐
            String textStr = normalContent.toString();
            String thinkingStr = thinkingContent.toString();
            boolean thinkingOnly = thinkingContent.length() > 0 && normalContent.length() == 0;

            Map<String, Object> contentRaw = null;
            if (thinkingContent.length() > 0) {
                contentRaw = new LinkedHashMap<>();
                contentRaw.put("thinking", thinkingContent.toString());
                if (Utils.isNotEmpty(thinkingSignature)) {
                    contentRaw.put("thinkingSignature", thinkingSignature);
                }
                if (normalContent.length() > 0) {
                    contentRaw.put("content", normalContent.toString());
                }
            }

            // redacted_thinking 分块列表透传到 contentRaw，供多轮逐块回传（拼接会损坏 opaque 数据）
            if (!redactedBlocks.isEmpty()) {
                if (contentRaw == null) {
                    contentRaw = new LinkedHashMap<>();
                }
                contentRaw.put("redactedThinkingBlocks", redactedBlocks);
            }

            List<ContentBlock> blocksForMsg = null;
            if (!mediaBlocks.isEmpty()) {
                blocksForMsg = new ArrayList<>();
                if (Utils.isNotEmpty(textStr)) {
                    // 多模态时用 result 文本投影（不含思考内容）
                    blocksForMsg.add(TextBlock.of(textStr));
                }
                blocksForMsg.addAll(mediaBlocks);
                acc.addMediaBlocks(mediaBlocks);
            }

            // finishReason：优先用真实 stop_reason；tool 场景兜底 tool_use
            choiceFinishReason = Utils.isNotEmpty(stopReason)
                    ? stopReason
                    : (!allToolCalls.isEmpty() ? "tool_use" : "stop");

            // 将所有工具调用合并到一个 AssistantMessage 中（带 tool 的终态消息 isThinking=false，确保历史回传不被跳过）
            if (!allToolCalls.isEmpty()) {
                AssistantMessage msg = new AssistantMessage(textStr, thinkingStr,
                        false, contentRaw, allToolCallsRaw, allToolCalls, null, blocksForMsg)
                        .reasoningFieldName("thinking");
                acc.addContentItem(msg);
            } else if (Utils.isNotEmpty(textStr) || contentRaw != null || blocksForMsg != null
                    || hasServerToolBlocks) {
                // hasServerToolBlocks：服务端工具内容已改走事件通道，正文可能为空；
                // 仍补一个内容项，保证 finishReason 与 usage 有承载（对齐迁移前 hasContent 语义）
                AssistantMessage msg = new AssistantMessage(textStr, thinkingStr,
                        thinkingOnly, contentRaw, null, null, null, blocksForMsg)
                        .reasoningFieldName("thinking");
                acc.addContentItem(msg);
            }
        }
        // 同步 lastFinishReason（复用已算好的 choiceFinishReason，避免重复计算）
        acc.lastFinishReason = choiceFinishReason;

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