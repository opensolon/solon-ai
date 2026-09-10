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
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.chat.*;
import org.noear.solon.ai.chat.dialect.AbstractChatDialect;
import org.noear.solon.ai.chat.event.ChatEventType;
import org.noear.solon.ai.chat.event.ChatStreamContext;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.tool.ToolCall;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Openai 聊天模型方言
 *
 * @author noear
 * @since 3.1
 */
public class OpenaiChatDialect extends AbstractChatDialect {
    /**
     * 本地方言指令角色策略：auto（默认）/ system / developer。
     * <p>该选项只控制 Chat Completions 出站角色，不会透传给服务端。</p>
     *
     * @since 4.1
     */
    public static final String OPTION_INSTRUCTION_ROLE = "openai_instruction_role";

    private static final OpenaiChatDialect instance = new OpenaiChatDialect();
    public static OpenaiChatDialect getInstance() {
        return instance;
    }

    private static final String SNAPSHOT_STATE_KEY = "OpenaiStreamSnapshotState";

    /**
     * 流式快照归一状态（按请求隔离；正文/思考按 choice.index 隔离，工具参数再按调用身份隔离）
     *
     * <p>与官方 SDK 的 ChatCompletionAccumulator 对齐：官方把 messageContents / toolCallBuilders
     * 全部按响应里的 {@code choices[].index} 建 Map，n&gt;1 时各路 choice 的文本互不干扰。若共用一份累积基准，
     * 多路交错下发会把基准搅成 c0f1+c1f1+c0f2…，快照判定失效且存在误截断风险。</p>
     *
     * <p>工具参数的通道键由 {@code choice.index + tool_call identity} 组成。调用身份优先使用
     * {@code tool_calls[].index}，其次为 id，最后才退回当前帧数组位置；函数名不参与身份选择，避免同名并行
     * 调用串线。位置别名同时用于承接 id/index 迟到的兼容端点，使身份补全前后的分片仍共享同一累积基准。</p>
     *
     * <p>注意：这里的 index 是<b>协议字段</b>，只用于本方言内部隔离累积基准；框架侧的内容项已不带
     * choice index（4.1 取消候选维度）。</p>
     */
    private static class SnapshotState {
        private final Map<Integer, SnapshotDeltaNormalizer> contents = new HashMap<>();
        private final Map<Integer, SnapshotDeltaNormalizer> reasonings = new HashMap<>();
        private final Map<String, SnapshotDeltaNormalizer> toolArguments = new HashMap<>();

        SnapshotDeltaNormalizer content(int index) {
            return contents.computeIfAbsent(index, k -> new SnapshotDeltaNormalizer());
        }

        SnapshotDeltaNormalizer reasoning(int index) {
            return reasonings.computeIfAbsent(index, k -> new SnapshotDeltaNormalizer());
        }

        SnapshotDeltaNormalizer toolArguments(int choiceIndex, ONode toolCall, int position) {
            String prefix = "choice:" + choiceIndex + ':';
            String positionKey = prefix + "position:" + position;
            String index = identityValue(toolCall.getOrNull("index"));
            String id = identityValue(toolCall.getOrNull("id"));
            String indexKey = index == null ? null : prefix + "index:" + index;
            String idKey = id == null ? null : prefix + "id:" + id;

            SnapshotDeltaNormalizer normalizer = indexKey == null ? null : toolArguments.get(indexKey);
            if (normalizer == null && idKey != null) {
                normalizer = toolArguments.get(idKey);
            }
            if (normalizer == null) {
                // 显式身份可能晚于首个参数分片；此时沿用先前按数组位置建立的状态。
                normalizer = toolArguments.get(positionKey);
            }
            if (normalizer == null) {
                normalizer = new SnapshotDeltaNormalizer();
            }

            if (indexKey != null) {
                toolArguments.put(indexKey, normalizer);
            }
            if (idKey != null) {
                toolArguments.put(idKey, normalizer);
            }
            // 数组位置是显式身份迟到或后续缺失时的桥接别名，必须始终与 index/id 指向同一状态。
            toolArguments.put(positionKey, normalizer);

            return normalizer;
        }

        private String identityValue(ONode node) {
            if (node == null || node.isValue() == false) {
                return null;
            }
            String value = node.getString();
            return Utils.isEmpty(value) ? null : value;
        }
    }

    @Override
    protected String getApiUrl(ChatConfig config) {
        return OpenaiDialectSupport.buildApiUrl(config.getApiUrl(), "chat/completions");
    }

    /**
     * 是否为默认
     */
    @Override
    public boolean isDefault() {
        return true;
    }

    /**
     * 匹配检测
     *
     * @param config 聊天配置
     */
    @Override
    public boolean matched(ChatConfig config) {
        return false;
    }

    @Override
    public ONode buildRequestJson(ChatConfig config, ChatOptions options, List<ChatMessage> messages, boolean isStream) {
        String instructionRole = resolveInstructionRole(config, options);
        ONode oNode = super.buildRequestJson(config, options, messages, isStream);

        // openai_instruction_role 是方言本地选项，不能作为 OpenAI 请求字段发送。
        oNode.remove(OPTION_INSTRUCTION_ROLE);
        if ("developer".equals(instructionRole)) {
            ONode messageNodes = oNode.getOrNull("messages");
            if (messageNodes != null && messageNodes.isArray()) {
                for (ONode messageNode : messageNodes.getArray()) {
                    if ("system".equals(messageNode.get("role").getString())) {
                        messageNode.set("role", "developer");
                    }
                }
            }
        }

        if (isStream) {
            ONode streamOptions = oNode.getOrNew("stream_options");
            if (streamOptions.hasKey("include_usage") == false) {
                streamOptions.set("include_usage", true);
            }
        }

        return oNode;
    }

    /**
     * 将统一的 SystemMessage 映射为 OpenAI Chat Completions 的线协议角色。
     * <p>OpenAI SDK 说明 o1 及更新模型以 developer 取代 system；自动模式只识别明确的新模型族，
     * 未知兼容模型保持 system。o1-preview/o1-mini 属于早期模型，不自动转换。</p>
     */
    private String resolveInstructionRole(ChatConfig config, ChatOptions options) {
        Object configured = options == null ? null : options.option(OPTION_INSTRUCTION_ROLE);
        String policy = configured == null ? "auto" : String.valueOf(configured).trim().toLowerCase(Locale.ROOT);
        if ("system".equals(policy) || "developer".equals(policy)) {
            return policy;
        }
        if ("auto".equals(policy) == false) {
            throw new IllegalArgumentException(OPTION_INSTRUCTION_ROLE
                    + " must be one of: auto, system, developer");
        }

        String model = config == null ? null : config.getModel();
        return prefersDeveloperRole(model) ? "developer" : "system";
    }

    private boolean prefersDeveloperRole(String model) {
        if (Utils.isEmpty(model)) {
            return false;
        }

        String modelName = model.trim().toLowerCase(Locale.ROOT);
        if (matchesModelFamily(modelName, "o1-preview") || matchesModelFamily(modelName, "o1-mini")) {
            return false;
        }

        return matchesModelFamily(modelName, "o1")
                || matchesModelFamily(modelName, "o3")
                || matchesModelFamily(modelName, "o4")
                || matchesModelFamily(modelName, "gpt-5")
                || matchesModelFamily(modelName, "gpt5")
                || matchesModelFamily(modelName, "gpt-6")
                || matchesModelFamily(modelName, "gpt6");
    }

    private boolean matchesModelFamily(String model, String family) {
        int fromIndex = 0;
        while (fromIndex < model.length()) {
            int start = model.indexOf(family, fromIndex);
            if (start < 0) {
                return false;
            }

            int end = start + family.length();
            boolean validPrefix = start == 0 || isProviderBoundary(model.charAt(start - 1));
            boolean validSuffix = end == model.length()
                    || model.charAt(end) == '-'
                    || model.charAt(end) == '.';
            if (validPrefix && validSuffix) {
                return true;
            }
            fromIndex = start + 1;
        }
        return false;
    }

    private boolean isProviderBoundary(char ch) {
        return ch == '/' || ch == ':' || ch == '.';
    }

    /**
     * 解析响应（事件形态）
     *
     * <p>OpenAI chat/completions 协议的流式帧承载正文、思考与工具调用增量；方言直接翻译为
     * TEXT_DELTA / THINKING_DELTA / TOOL_CALL_*，聚合统一由 ChatAccumulator.acceptEvent 完成，
     * 此处另行处理拒答与错误事件。</p>
     *
     * <p>每帧只解析一次 JSON：正文解析、拒答事件、错误事件共用同一份 {@link ONode}。</p>
     *
     * @since 4.1
     */
    @Override
    public void parseResponseJson(ChatStreamContext ctx, String data) {
        ChatAccumulator acc = ctx.getAccumulator();

        if ("[DONE]".equals(data)) { //不是数据结构
            acc.attrRemove(SNAPSHOT_STATE_KEY);
            if (acc.isFinished() == false) {
                // 终态由事件归一化与 accumulator 快照表达，不再追加空 AssistantMessage。
                acc.setFinished(true);
            }
            return;
        }

        //有些中转会直接输出："error xxx" 内容（非 JSON，不能进 ONode.ofJson）
        if (tryParseErrorText(acc, data)) {
            emitError(ctx, acc, null);
            return;
        }

        //解析
        ONode oResp = ONode.ofJson(data);

        if (oResp.isObject() == false) {
            return;
        }

        parseFrameNode(ctx, acc, oResp);
        emitRefusalEvents(ctx, oResp);

        if (acc.getError() != null) {
            emitError(ctx, acc, oResp);
        }
    }

    /**
     * 解析一帧（已解析好的 JSON 节点）
     *
     * @since 4.1
     */
    private void parseFrameNode(ChatStreamContext ctx, ChatAccumulator acc, ONode oResp) {
        // 非官方规范的顶层错误形态（个别兼容端点）与官方 {error:{message,type,code}} 统一走规范提取，
        // 避免 message 为对象时取出 null
        if ("error".equals(oResp.get("object").getString())) {
            acc.setError(new ChatException(OpenaiDialectSupport.extractErrorMessage(
                    oResp.hasKey("error") ? oResp.get("error") : oResp.getOrNull("message"))));
            return;
        } else if (oResp.hasKey("error")) {
            // 规范错误提取：error 为对象（{message,type,code}），不能整体序列化为字符串
            acc.setError(new ChatException(OpenaiDialectSupport.extractErrorMessage(oResp.get("error"))));
            return;
        }

        acc.setModel(oResp.get("model").getString());
        // 供应商响应标识（chatcmpl-xxx，同一响应的各 chunk 一致）：记录后本步事件自动预填
        ctx.setProviderResponseId(oResp.get("id").getString());

        // 官方 include_usage=true 时最后一个 usage chunk 的 choices 为空数组；个别端点可能缺省该字段，做防御
        ONode oChoices = oResp.getOrNull("choices");
        if (oChoices != null && oChoices.isArray()) {
            for (ONode oChoice1 : oChoices.getArray()) {
                int index = oChoice1.get("index").getInt();
                String finish_reason = oChoice1.get("finish_reason").getString();

                List<AssistantMessage> messageList;
                if (acc.isStream()) {   //object=chat.completion.chunk
                    // OpenAI 兼容端点中有少数实现把累计快照放在 delta.content/reasoning_content
                    // 中。核心聚合器按协议只接受增量，因此在方言边界把快照转换为增量。
                    ONode normalized = normalizeStreamDelta(acc, index, oChoice1);
                    if (normalized == null) {
                        // 整帧都是已交付过的快照重复：不解析（避免污染 in_thinking 状态机）、不推空内容项。
                        // 仍带 finish_reason 时要继续走完成流程，由下方补位逻辑推结束帧
                        if (Utils.isEmpty(finish_reason)) {
                            continue;
                        }
                        messageList = Collections.emptyList();
                    } else {
                        messageList = parseAssistantMessage(acc, normalizeLegacyFunctionCall(acc, normalized.get("delta")));
                    }
                } else {
                    //object=chat.completion
                    messageList = parseAssistantMessage(acc, normalizeLegacyFunctionCall(acc, oChoice1.get("message")));
                }

                for (AssistantMessage msg1 : messageList) {
                    if (acc.isStream()) {
                    // 流式主干直接进入事件通道，聚合由 ChatAccumulator.acceptEvent 统一完成。
                        publishAssistantMessageEvents(ctx, msg1);
                        // publish 会把本帧作为事件载体合并到终态；参数归一后该载体只剩增量，
                        // 因而用 builder 中的完整累计值恢复终态 ToolCall，避免重复终帧覆盖最终参数。
                        restoreTerminalToolCallArguments(acc, msg1);
                    } else {
                        acc.setTerminalMessage(msg1);
                    }
                }

                if (Utils.isNotEmpty(finish_reason)) {
                    acc.setFinished(true);
                    acc.lastFinishReason = finish_reason;
                }

                // ChatResponse 是单结果模型：只消费供应商返回的首个 choice，避免把多个候选
                // 的正文、思考和工具调用拼成一条消息。
                break;
            }
        }

        if (acc.isStream() == false) {
            // 非流式：一次就是全部。部分兼容端点不回 finish_reason，此处统一标完成，
            // 与 Responses 方言的非流式语义保持一致，避免上层拿到 isFinished=false
            acc.setFinished(true);
        }

        if (acc.isFinished() && acc.isStream() == false && acc.isTerminalMessagePresent() == false) {
            // 非流式空结果仍需提交一个明确的终态消息。
            acc.setTerminalMessage(new AssistantMessage(""));
        }

        ONode oUsage = oResp.getOrNull("usage");
        if (oUsage != null) {
            long promptTokens = oUsage.get("prompt_tokens").getLong();
            long completionTokens = oUsage.get("completion_tokens").getLong();
            // 官方 SDK 中 total_tokens 为 optional（CompletionUsage），缺省时用输入+输出兜底
            long totalTokens = oUsage.hasKey("total_tokens")
                    ? oUsage.get("total_tokens").getLong() : (promptTokens + completionTokens);

            // 思考 token 统计：优先 DeepSeek 形态 completion_tokens_details.reasoning_tokens，兜底 think_tokens
            long thinkTokens = 0L;
            ONode completionTokensDetails = oUsage.getOrNull("completion_tokens_details");
            if (completionTokensDetails != null && completionTokensDetails.hasKey("reasoning_tokens")) {
                thinkTokens = completionTokensDetails.get("reasoning_tokens").getLong();
            } else if (oUsage.hasKey("think_tokens")) {
                thinkTokens = oUsage.get("think_tokens").getLong();
            }

            // 缓存 token 统计（官方 prompt_tokens_details 含 cached_tokens / cache_write_tokens；
            // 另兼容 DeepSeek 形态 prompt_cache_hit_tokens）
            long cacheReadInputTokens = 0L;
            long cacheCreationInputTokens = 0L;
            ONode promptTokensDetails = oUsage.getOrNull("prompt_tokens_details");
            if (promptTokensDetails != null) {
                if (promptTokensDetails.hasKey("cached_tokens")) {
                    cacheReadInputTokens = promptTokensDetails.get("cached_tokens").getLong();
                }
                if (promptTokensDetails.hasKey("cache_write_tokens")) {
                    cacheCreationInputTokens = promptTokensDetails.get("cache_write_tokens").getLong();
                }
            }
            if ((promptTokensDetails == null || !promptTokensDetails.hasKey("cached_tokens"))
                    && oUsage.hasKey("prompt_cache_hit_tokens")) {
                cacheReadInputTokens = oUsage.get("prompt_cache_hit_tokens").getLong();
            }

            acc.setUsage(new AiUsage(promptTokens, thinkTokens, completionTokens, totalTokens,
                    cacheCreationInputTokens, cacheReadInputTokens, oUsage));
        }
    }

    /**
     * 发射错误事件
     *
     * @since 4.1
     */
    private void emitError(ChatStreamContext ctx, ChatAccumulator acc, ONode raw) {
        ctx.emit(ctx.event(ChatEventType.ERROR)
                .rawType("error")
                .error(acc.getError())
                .raw(raw)
                .build());
    }

    private void emitRefusalEvent(ChatStreamContext ctx, String rawType, ONode message, ONode raw) {
        if (message == null || message.hasKey("refusal") == false) {
            return;
        }

        String refusal = message.get("refusal").getString();
        if (Utils.isNotEmpty(refusal)) {
            ctx.emit(ctx.event(ChatEventType.REFUSAL_DELTA)
                    .rawType(rawType)
                    .text(refusal)
                    .raw(raw)
                    .build());
        }
    }

    private void emitRefusalEvents(ChatStreamContext ctx, ONode response) {
        ONode choices = response.getOrNull("choices");
        if (choices == null || choices.isArray() == false) {
            return;
        }

        String rawType = response.hasKey("object")
                ? response.get("object").getString() : "chat.completion.chunk";
        for (ONode choice : choices.getArray()) {
            ONode message = choice.getOrNull("delta");
            if (message == null) {
                message = choice.getOrNull("message");
            }
            emitRefusalEvent(ctx, rawType, message, response);
        }
    }

    /**
     * 将旧版 function_call 兼容转换为统一的 tool_calls 形态。
     * openai-java 的 ChatCompletionAccumulator 同时支持两种字段，兼容网关仍可能返回旧字段。
     */
    private ONode normalizeLegacyFunctionCall(ChatAccumulator acc, ONode message) {
        if (message == null || message.hasKey("tool_calls") || !message.hasKey("function_call")) {
            return message;
        }
        ONode function = message.getOrNull("function_call");
        if (function == null || !function.isObject()) return message;
        String id = function.get("id").getString();
        if (Utils.isEmpty(id)) id = acc.lastToolCallId;
        if (Utils.isEmpty(id)) id = "legacy_function_call";
        ONode calls = new ONode().asArray();
        calls.addNew().set("id", id).set("index", 0).set("type", "function")
                .set("function", function);
        message.set("tool_calls", calls);
        return message;
    }

    /**
     * 将部分 OpenAI 兼容端点返回的累计快照转换为真正的流式增量。
     * 核心层无条件追加会得到成倍膨胀的文本或工具参数。判定与累积均由
     * {@link SnapshotDeltaNormalizer} 负责：按原始报文自行累积，且要求累积长度达阈值后才允许首次判定，
     * 避免改写官方 Chat Completions 的真实增量。</p>
     *
     * <p>覆盖正文、思考及 {@code delta.tool_calls[].function.arguments}。正文与思考按 choice 隔离；
     * 工具参数再按调用 index/id/数组位置隔离，既不因同名并行调用串线，也能承接 id 迟到。进入快照模式后，
     * 完全相等的重复终帧会归一为空参数增量。{@code delta.refusal} 是官方独有字段（兼容网关不实现，
     * 无快照风险），不做判定，但核心层会在正文为空时把它投影进文本，因此按同样条件记入正文累积基准，
     * 保证基准与「已交付文本」一致。</p>
     *
     * @param index choice 序号（n&gt;1 时各路 choice 的累积基准必须隔离，与官方 SDK 的按 index 累积一致）
     * @return 归一后的 choice；整帧文本都是已交付过的重复快照且无 tool_calls 时返回 null（表示可整帧丢弃）
     */
    private ONode normalizeStreamDelta(ChatAccumulator acc, int index, ONode choice) {
        if (choice == null || choice.hasKey("delta") == false) {
            return choice;
        }

        ONode delta = choice.get("delta");
        if (delta == null || delta.isObject() == false) {
            return choice;
        }

        String contentRaw = delta.get("content").getString();
        // 推理字段名与核心解析保持一致的优先级：reasoning_content 优先，其次 reasoning
        String reasoningKey = delta.hasKey("reasoning_content") ? "reasoning_content"
                : (delta.hasKey("reasoning") ? "reasoning" : null);
        String reasoningRaw = reasoningKey == null ? null : delta.get(reasoningKey).getString();
        String refusalRaw = delta.get("refusal").getString();
        ONode oToolCalls = delta.getOrNull("tool_calls");

        if (Utils.isEmpty(contentRaw) && Utils.isEmpty(reasoningRaw) && Utils.isEmpty(refusalRaw)
                && (oToolCalls == null || oToolCalls.isArray() == false)) {
            return choice; //无文本或工具参数可判定（role 帧）
        }

        SnapshotState state = acc.attrIfAbsent(SNAPSHOT_STATE_KEY, k -> new SnapshotState());

        boolean changed = normalizeToolCallArguments(state, index, oToolCalls);

        String contentDelta = contentRaw;
        if (Utils.isNotEmpty(contentRaw)) {
            contentDelta = state.content(index).normalize(contentRaw);
            changed |= (contentRaw.equals(contentDelta) == false);
        }

        String reasoningDelta = reasoningRaw;
        if (Utils.isNotEmpty(reasoningRaw)) {
            reasoningDelta = state.reasoning(index).normalize(reasoningRaw);
            changed |= (reasoningRaw.equals(reasoningDelta) == false);
        }

        // 与核心投影条件对齐：仅当本帧没有正文时，refusal 才会成为文本
        if (Utils.isNotEmpty(refusalRaw) && Utils.isEmpty(contentDelta)) {
            state.content(index).append(refusalRaw);
        }

        if (changed == false) {
            return choice;
        }

        if (Utils.isEmpty(contentDelta) && Utils.isEmpty(reasoningDelta) && Utils.isEmpty(refusalRaw)
                && (oToolCalls == null || oToolCalls.isNull())) {
            return null;
        }

        // 原地改写：该节点本轮解析完即弃，无需深拷贝
        if (Utils.isNotEmpty(contentRaw)) {
            delta.set("content", contentDelta);
        }
        if (Utils.isNotEmpty(reasoningRaw)) {
            delta.set(reasoningKey, reasoningDelta);
        }

        return choice;
    }

    private void restoreTerminalToolCallArguments(ChatAccumulator acc, AssistantMessage message) {
        if (message == null || Utils.isEmpty(message.getToolCalls())) {
            return;
        }
        List<ToolCall> terminalCalls = new ArrayList<>();
        for (ToolCall call : message.getToolCalls()) {
            if (call == null || Utils.isEmpty(call.getIndex())) {
                continue;
            }
            org.noear.solon.ai.chat.tool.ToolCallBuilder builder = acc.getToolCallBuilders().get(call.getIndex());
            if (builder == null || builder.argumentsBuilder.length() == 0) {
                continue;
            }
            ToolCall terminal = new ToolCall(call.getIndex(), call.getId(), call.getName(),
                    builder.argumentsBuilder.toString(), call.getArguments());
            terminalCalls.add(terminal);
        }
        if (terminalCalls.isEmpty() == false) {
            acc.mergeTerminalMessage(new AssistantMessage(null, null, terminalCalls, null));
        }
    }

    /**
     * 逐个归一工具调用参数。函数名不是调用身份；同一 choice 内优先按 call index/id 隔离，
     * 缺失显式身份时才使用数组位置，以覆盖同名并行与身份迟到。
     */
    private boolean normalizeToolCallArguments(SnapshotState state, int choiceIndex, ONode toolCalls) {
        if (toolCalls == null || toolCalls.isArray() == false) {
            return false;
        }

        boolean changed = false;
        int position = 0;
        for (ONode toolCall : toolCalls.getArray()) {
            ONode function = toolCall.getOrNull("function");
            if (function != null && function.isObject()) {
                ONode arguments = function.getOrNull("arguments");
                if (arguments != null && arguments.isString()) {
                    String raw = arguments.getString();
                    if (Utils.isNotEmpty(raw)) {
                        String normalized = state.toolArguments(choiceIndex, toolCall, position).normalize(raw);
                        if (raw.equals(normalized) == false) {
                            function.set("arguments", normalized);
                            changed = true;
                        }
                    }
                }
            }
            position++;
        }
        return changed;
    }
}
