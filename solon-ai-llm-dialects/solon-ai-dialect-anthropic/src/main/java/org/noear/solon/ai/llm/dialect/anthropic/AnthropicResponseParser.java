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
import org.noear.solon.ai.chat.ChatChoice;
import org.noear.solon.ai.chat.ChatException;
import org.noear.solon.ai.chat.ChatResponseDefault;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.content.TextBlock;
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
    static Map<Integer, StreamToolState> toolStates(ChatResponseDefault resp, boolean create) {
        Map<Integer, StreamToolState> states = resp.attrAs(STREAM_TOOL_STATE_KEY);
        if (states == null && create) {
            states = new HashMap<>();
            resp.attrPut(STREAM_TOOL_STATE_KEY, states);
        }
        return states;
    }

    private Map<Integer, StreamToolState> getToolStates(ChatResponseDefault resp, boolean create) {
        return toolStates(resp, create);
    }

    /**
     * redacted_thinking 分块列表（协议要求逐块原样回传，不可拼接）。
     */
    static final String REDACTED_BLOCKS_KEY = "RedactedThinkingBlocks";

    @SuppressWarnings("unchecked")
    static List<String> getRedactedBlocks(ChatResponseDefault resp, boolean create) {
        List<String> blocks = resp.attrAs(REDACTED_BLOCKS_KEY);
        if (blocks == null && create) {
            blocks = new ArrayList<>();
            resp.attrPut(REDACTED_BLOCKS_KEY, blocks);
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
     * @param resp 聊天响应对象
     * @param json 响应 JSON 字符串
     * @return 是否有有效的选择
     * @author oisin lu
     * @date 2026年1月27日
     */
    public boolean parseResponse(ChatResponseDefault resp, String json) {
        if (resp.isStream()) {
            return parseStreamResponse(resp, json);
        } else {
            return parseNonStreamResponse(resp, json);
        }
    }

    /**
     * 解析流式响应
     *
     * @param resp 聊天响应对象
     * @param json 响应 JSON 字符串
     * @return 是否有有效的选择
     * @author oisin lu
     * @date 2026年1月27日
     */
    public boolean parseStreamResponse(ChatResponseDefault resp, String json) {
        if (json == null || json.isEmpty()) {
            return false;
        }

        StringBuilder redactedThinkingData = resp.attrIfAbsent(REDACTED_THINKING_DATA_KEY, (k) -> new StringBuilder());

        String[] lines = json.split("\n");
        boolean hasChoices = false;

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
                resp.attrRemove(STREAM_TOOL_STATE_KEY);
                emitTerminalFrameOnce(resp);
                return true;
            }

            ONode oResp = new JsonReader(jsonData).readNext();
            if (oResp.isObject() == false) {
                continue;
            }

            if (oResp.hasKey("error")) {
                resp.setError(new ChatException(oResp.get("error").getString()));
                return true;
            }

            // Claude 流式响应事件类型
            String eventType = oResp.get("type").getString();
            if ("error".equals(eventType)) {
                resp.attrRemove(STREAM_TOOL_STATE_KEY);

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

                resp.setError(new ChatException(detailedError));
                return true;
            } else if ("message_start".equals(eventType)) {
                // 消息开始，可以设置模型信息和初始 usage
                ONode message = oResp.get("message");
                if (message != null) {
                    resp.setModel(message.get("model").getString());

                    // 某些情况下 message_start 也包含初始 usage 信息
                    AiUsage usage = parseUsage(message.getOrNull("usage"));
                    if (usage != null) {
                        resp.setUsage(usage);
                    }
                }
            } else if ("content_block_start".equals(eventType)) {
                ONode contentBlock = oResp.get("content_block");
                if (contentBlock != null) {
                    String blockType = contentBlock.get("type").getString();
                    if ("thinking".equals(blockType)) {
                        // 思考内容块开始
                        if (!resp.in_thinking) {
                            // 第一次进入思考模式，添加开始标记；同步统一推理字段名（供后续闭合帧/聚合复用）
                            resp.reasoning_field_name = REASONING_FIELD_THINKING;
                            resp.addChoice(new ChatChoice(0, new Date(), null,
                                    new AssistantMessage("", "", true).reasoningFieldName(REASONING_FIELD_THINKING)));
                            resp.in_thinking = true;
                            hasChoices = true;
                        }
                        String thinking = contentBlock.get("thinking").getString();
                        if (Utils.isNotEmpty(thinking)) {
                            resp.addChoice(new ChatChoice(0, new Date(), null,
                                    new AssistantMessage("", thinking, true).reasoningFieldName(REASONING_FIELD_THINKING)));
                            hasChoices = true;
                        }
                    } else if ("text".equals(blockType)) {
                        // 如果之前在思考模式，添加结束标记
                        if (resp.in_thinking) {
                            resp.addChoice(new ChatChoice(0, new Date(), null,
                                    new AssistantMessage("", "", true).reasoningFieldName(resp.reasoning_field_name)));
                            resp.in_thinking = false;
                            hasChoices = true;
                        }
                        String text = contentBlock.get("text").getString();
                        if (Utils.isNotEmpty(text)) {
                            resp.addChoice(new ChatChoice(0, new Date(), null,
                                    new AssistantMessage(text, "", false).reasoningFieldName(resp.reasoning_field_name)));
                            hasChoices = true;
                        }
                    } else if ("tool_use".equals(blockType)) {
                        // 如果之前在思考模式，添加结束标记
                        if (resp.in_thinking) {
                            resp.addChoice(new ChatChoice(0, new Date(), null,
                                    new AssistantMessage("", "", true).reasoningFieldName(resp.reasoning_field_name)));
                            resp.in_thinking = false;
                            hasChoices = true;
                        }
                        StreamToolState state = new StreamToolState();
                        state.toolUseId = contentBlock.get("id").getString();
                        state.toolName = contentBlock.get("name").getString();
                        state.toolInput = new StringBuilder();

                        // 按块 index 存储（协议：事件均携带 index，支持多块并行）
                        int blockIdx = oResp.get("index").getInt();
                        getToolStates(resp, true).put(blockIdx, state);
                    } else if ("redacted_thinking".equals(blockType)) {
                        // 安全过滤的推理内容块，原样保留供多轮回传（对齐 Anthropic SDK）
                        String data = contentBlock.get("data").getString();
                        if (Utils.isNotEmpty(data)) {
                            redactedThinkingData.append(data);
                            // opaque 数据块必须独立分块回传，拼接会损坏 base64（对齐 SDK：逐块保留）
                            getRedactedBlocks(resp, true).add(data);
                        }
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
                            resp.addChoice(new ChatChoice(0, new Date(), null,
                                    new AssistantMessage("", thinking, true).reasoningFieldName(REASONING_FIELD_THINKING)));
                            hasChoices = true;
                        }
                    } else if ("signature_delta".equals(deltaType)) {
                        String signature = delta.get("signature").getString();
                        if (Utils.isNotEmpty(signature)) {
                            resp.thinkingSignature = signature;
                        }
                    } else if ("text_delta".equals(deltaType)) {
                        String text = delta.get("text").getString();
                        if (Utils.isNotEmpty(text)) {
                            resp.addChoice(new ChatChoice(0, new Date(), null,
                                    new AssistantMessage(text, "", false).reasoningFieldName(resp.reasoning_field_name)));
                            hasChoices = true;
                        }
                    } else if ("input_json_delta".equals(deltaType)) {
                        // 工具调用参数增量更新，按需从 map 获取状态
                        String partialJson = delta.get("partial_json").getString();
                        if (Utils.isNotEmpty(partialJson)) {
                            Map<Integer, StreamToolState> states = getToolStates(resp, false);
                            if (states != null) {
                                // 按事件携带的 index 定位所属工具块
                                StreamToolState state = states.get(oResp.get("index").getInt());
                                if (state != null) {
                                    state.toolInput.append(partialJson);
                                }
                            }
                        }
                    }
                }
            } else if ("content_block_stop".equals(eventType)) {
                // 内容块结束：按 index 精确定位并清理对应工具块状态
                Map<Integer, StreamToolState> states = getToolStates(resp, false);
                StreamToolState state = null;
                if (states != null) {
                    state = states.remove(oResp.get("index").getInt());
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
                                toolCalls, null).reasoningFieldName(resp.reasoning_field_name);
                        resp.addChoice(new ChatChoice(0, new Date(), null, assistantMessage));
                        hasChoices = true;
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
                    AiUsage prev = resp.getUsage();
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
                    resp.setUsage(usage);
                }

                ONode stopReason = oResp.get("delta");
                if (stopReason != null) {
                    String finishReason = stopReason.get("stop_reason").getString();
                    if (Utils.isNotEmpty(finishReason)) {
                        resp.setFinished(true);
                        resp.lastFinishReason = finishReason;
                    }
                }
            } else if ("message_stop".equals(eventType)) {
                // 消息结束，清理状态并补齐终态帧（finishReason 透传 / thinkingSignature 载体 / thinking 边界闭合）
                resp.attrRemove(STREAM_TOOL_STATE_KEY);
                emitTerminalFrameOnce(resp);

                resp.setFinished(true);
                hasChoices = true;
            } else if ("ping".equals(eventType)) {
                // 心跳消息，忽略
                continue;
            }
        }

        return hasChoices;
    }

    /**
     * 发射终态帧（幂等）：message_stop 与兼容网关的 [DONE] 双发场景下仅补一次。
     *
     * <p>不依赖 resp.isFinished() 判断：message_delta(stop_reason) 会先行置 finished，
     * 若以它为标记，后续 [DONE] 里的终态补齐（thinkingSignature 载体）会被误跳过。</p>
     *
     * @since 4.0.4
     */
    private void emitTerminalFrameOnce(ChatResponseDefault resp) {
        if (resp.attrAs(TERMINAL_FRAME_EMITTED_KEY) == null) {
            resp.attrPut(TERMINAL_FRAME_EMITTED_KEY, Boolean.TRUE);
            emitTerminalFrame(resp);
        }
        resp.setFinished(true);
    }

    /**
     * 流式终态补齐（对齐 AssistantMessage 双字段接口 82119a72e 后的聚合链路）：
     * <ol>
     *   <li>thinking 边界闭合：仅思考无正文的流（max_tokens 截断等）没有后续 text/tool_use
     *       块可借道闭合，此处补齐思考结束信号，保证下游逐帧订阅者收到完整边界；</li>
     *   <li>终态补位：无任何 choice 时补空帧透传 finishReason（原有行为）；</li>
     *   <li>thinkingSignature 载体：core 的 getAggregationMessage() 以 last choice 的 contentRaw
     *       为聚合消息 contentRaw，流式 signature 若只存 resp.thinkingSignature，非工具多轮对话的
     *       聚合消息会丢失签名 → 历史回传时 thinking 块被丢弃，多轮 extended thinking 上下文断裂。
     *       此处以携带 {thinkingSignature} contentRaw 的空帧收官，使聚合消息拿到签名。</li>
     * </ol>
     *
     * @since 4.0.4
     */
    private void emitTerminalFrame(ChatResponseDefault resp) {
        // 1) 仅思考无正文的流：补思考闭合信号（空帧、无内容累加，仅边界语义）
        if (resp.in_thinking) {
            resp.addChoice(new ChatChoice(0, new Date(), null,
                    new AssistantMessage("", "", true).reasoningFieldName(resp.reasoning_field_name)));
            resp.in_thinking = false;
        }

        // 2) 终态补位：空流补空帧透传 finishReason；有 signature 时补载体帧（二选一命中即补）
        if (resp.hasChoices() == false || Utils.isNotEmpty(resp.thinkingSignature)) {
            Map<String, Object> contentRaw = null;
            if (Utils.isNotEmpty(resp.thinkingSignature)) {
                // 供 getAggregationMessage() 取 last.getContentRaw() 时携带签名，多轮回传时可重建 thinking 块
                contentRaw = new LinkedHashMap<>();
                contentRaw.put("thinkingSignature", resp.thinkingSignature);
            }

            AssistantMessage placeholder = new AssistantMessage("", "",
                    false, contentRaw, null, null, null, null)
                    .reasoningFieldName(resp.reasoning_field_name);
            resp.addChoice(new ChatChoice(0, new Date(), resp.getLastFinishReasonNormalized(), placeholder));
        }
    }

    /**
     * 解析非流式响应
     *
     * @param resp 聊天响应对象
     * @param json 响应 JSON 字符串
     * @return 解析是否成功
     * @author oisin lu
     * @date 2026年1月27日
     */
    public boolean parseNonStreamResponse(ChatResponseDefault resp, String json) {
        if ("[DONE]".equals(json)) {
            if (resp.isFinished() == false) {
                resp.addChoice(new ChatChoice(0, new Date(), resp.getLastFinishReasonNormalized(),
                        new AssistantMessage("").reasoningFieldName(resp.reasoning_field_name)));
                resp.setFinished(true);
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
            resp.setError(new ChatException(detailedError));
            return true;
        }

        StringBuilder redactedThinkingData = resp.attrIfAbsent(REDACTED_THINKING_DATA_KEY, (k) -> new StringBuilder());

        // 设置模型信息
        resp.setModel(oResp.get("model").getString());
        Date created = new Date();
        if (oResp.hasKey("created")) {
            created = new Date(oResp.get("created").getLong() * 1000);
        }
        // 先解析 stop_reason，供 choice.finishReason 与 lastFinishReason 共用
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

            for (ONode contentItem : contentArray.getArray()) {
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
                    }
                } else if ("server_tool_use".equals(contentType)) {
                    // server-side tool（web_search/code_execution 等）：降级为文本摘要，避免内容静默丢失
                    String name = contentItem.get("name").getString();
//                    if (normalContent.length() > 0) {
//                        normalContent.append("\n");
//                    }
                    normalContent.append("[server tool: ").append(name).append("]");
                } else if (contentType != null && contentType.endsWith("_tool_result")) {
                    // web_search_tool_result / web_fetch_tool_result 等：提取其 content 内的文本
                    ONode resultContent = contentItem.getOrNull("content");
                    if (resultContent != null && resultContent.isArray()) {
                        for (ONode rb : resultContent.getArray()) {
                            String text = rb.get("text").getString();
                            if (Utils.isNotEmpty(text)) {
//                                if (normalContent.length() > 0) {
//                                    normalContent.append("\n");
//                                }
                                normalContent.append(text);
                            }
                        }
                    }
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
                resp.addMediaBlocks(mediaBlocks);
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
                resp.addChoice(new ChatChoice(0, created, choiceFinishReason, msg));
            } else if (Utils.isNotEmpty(textStr) || contentRaw != null || blocksForMsg != null) {
                AssistantMessage msg = new AssistantMessage(textStr, thinkingStr,
                        thinkingOnly, contentRaw, null, null, null, blocksForMsg)
                        .reasoningFieldName("thinking");
                resp.addChoice(new ChatChoice(0, created, choiceFinishReason, msg));
            }
        }
        // 同步 lastFinishReason（复用已算好的 choiceFinishReason，避免重复计算）
        resp.lastFinishReason = choiceFinishReason;

        // 解析用量信息
        AiUsage usage = parseUsage(oResp.getOrNull("usage"));
        if (usage != null) {
            resp.setUsage(usage);
        }
        resp.setFinished(true);
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