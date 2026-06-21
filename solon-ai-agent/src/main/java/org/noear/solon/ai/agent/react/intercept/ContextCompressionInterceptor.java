/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.agent.react.intercept;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.ModelType;
import org.noear.solon.Utils;
import org.noear.solon.ai.agent.AgentChunk;
import org.noear.solon.ai.agent.AgentTrace;
import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.ReActStyle;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.intercept.compress.CompositeCompressionStrategy;
import org.noear.solon.ai.agent.react.intercept.compress.HierarchicalCompressionStrategy;
import org.noear.solon.ai.agent.react.intercept.compress.LLMCompressionStrategy;
import org.noear.solon.ai.agent.react.intercept.compress.VectorStoreCompressionStrategy;
import org.noear.solon.ai.chat.CacheControl;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.message.*;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.FluxSink;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 语义保护型上下文压缩拦截器
 *
 * <p>在 Agent 推理开始前（{@code onReasonStart}），当消息数量或 Token 数超过阈值时，
 * 自动对工作记忆区进行无损（或近无损）压缩。核心目标：
 * <ul>
 *   <li><b>初心链保护</b>：标记为 {@code META_FIRST} 的消息（如 system prompt、用户原始问题）永不压缩</li>
 *   <li><b>Tool-use 原子对保护</b>：{@code Assistant(with tool_calls)} ↔ {@code ToolMessage} 的调用-结果配对不会被拆散</li>
 *   <li><b>多轮追溯保留</b>：当最后一条是 ToolMessage 时，向前追溯至完整的源头 Assistant(with tool_calls)，
 *       确保工具调用链的完整性</li>
 *   <li><b>Token 预算控制</b>：通过 {@link #estimateTokens} 精确计算消息 Token 开销，
 *       预留摘要空间后按双维度（数量+Token）确定保留窗口</li>
 * </ul>
 *
 * <p>支持四种压缩策略（通过 {@link CompressionStrategy} 注入）：
 * <ul>
 *   <li>{@code null}（默认）—— 不调用 LLM，仅执行原子对齐的纯裁剪（fallback 零成本路径）</li>
 *   <li>{@link LLMCompressionStrategy} —— LLM 生成摘要</li>
 *   <li>{@link org.noear.solon.ai.agent.react.intercept.compress.KeyInfoExtractionStrategy} —— 关键信息提取</li>
 *   <li>{@link HierarchicalCompressionStrategy} —— 分层摘要</li>
 *   <li>{@link VectorStoreCompressionStrategy} —— 向量存储检索</li>
 *   <li>{@link CompositeCompressionStrategy} —— 组合策略</li>
 * </ul>
 *
 * @author noear
 * @since 3.9.4
 * @since 4.0.0
 */
@Preview("3.8.2")
public class ContextCompressionInterceptor implements ReActInterceptor {
    private static final Logger log = LoggerFactory.getLogger(ContextCompressionInterceptor.class);

    public final static String META_COMPRESSED = "_compressed";

    // 在类中预加载注册表
    private static final EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
    // 适配 GPT-4o (o200k_base)，对 DeepSeek 等使用 cl100k_base 的模型有微小偏差（通常 <5%）
    private static final Encoding encoding = registry.getEncodingForModel(ModelType.GPT_4O);
    private static final String META_TOKEN_SIZE = "token_size";

    // 保留窗口的最大消息数（默认 15）
    private int maxMessages;
    // 保留窗口的最大 Token 数（默认 15_000）
    private int maxTokens;
    // 保留窗口的最小消息数下限（默认 maxMessages / 3，最低 3）
    // 防止 Token 维度截断导致保留窗口被压缩到只剩 1~2 条消息
    private int minReservedMessages;
    // 重试次数
    private int maxRetries = 3;
    // 压缩策略
    private final CompressionStrategy compressionStrategy;
    // llm 动态提供者
    private final Supplier<ChatModel> chatModelSupplier;

    public void setMaxMessages(int maxMessages) {
        this.maxMessages = Math.max(10, maxMessages);
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = Math.max(10_000, maxTokens);
    }

    public void setMinReservedMessages(int minReservedMessages) {
        this.minReservedMessages = Math.max(3, minReservedMessages);
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public ContextCompressionInterceptor(int maxMessages, int maxTokens, Supplier<ChatModel> chatModelSupplier, CompressionStrategy compressionStrategy) {
        this.maxMessages = Math.max(10, maxMessages);
        this.maxTokens = Math.max(10_000, maxTokens);
        this.minReservedMessages = Math.max(3, this.maxMessages / 3);
        this.chatModelSupplier = chatModelSupplier;
        this.compressionStrategy = compressionStrategy;
    }

    public ContextCompressionInterceptor(int maxMessages, int maxTokens, int maxRetries, Supplier<ChatModel> chatModelSupplier, CompressionStrategy compressionStrategy) {
        this.maxMessages = Math.max(10, maxMessages);
        this.maxTokens = Math.max(10_000, maxTokens);
        this.minReservedMessages = Math.max(3, this.maxMessages / 3);
        this.maxRetries = maxRetries;
        this.chatModelSupplier = chatModelSupplier;
        this.compressionStrategy = compressionStrategy;
    }

    public ContextCompressionInterceptor(){
        this(15, 15_000, null, null);
    }

    /**
     * 复制实例，并使用新的限制
     */
    public ContextCompressionInterceptor copyWith(int maxMessages, int maxTokens) {
        ContextCompressionInterceptor tmp = new ContextCompressionInterceptor(
                maxMessages,
                maxTokens,
                this.chatModelSupplier,
                this.compressionStrategy);

        return tmp;
    }

    @Override
    public void onReasonStart(ReActTrace trace, StringBuilder systemPromptBuf) {
        List<ChatMessage> messages = trace.getWorkingMemory().getMessages();
        String systemPrompt = (systemPromptBuf == null ? null : systemPromptBuf.toString());

        // ⭐ 标记静态上下文边界（为 Dialect 层的 cache_control 提供依据）
        //    当配置了 CacheControl 时，确保系统提示词和工具定义作为一个完整的
        //    可缓存块被保留，不被压缩逻辑破坏其结构性完整性。
        markStaticContextBoundary(trace, systemPrompt);

        // 0. ⭐ 单条消息硬上限兜底（内容级截断）
        //    消息级压缩无法处理“单条消息自身就超过窗口”的情况（如工具读取了超大文件/二进制）。
        //    在任何裁剪逻辑之前，先把超限的单条消息内容截断，确保不变式：
        //    任何单条消息都不会独占超过窗口的份额，从根本上避免 context_length_exceeded。
        messages = enforcePerMessageCap(trace, messages);

        // 收集 tools 元信息（仅 NATIVE_TOOL 模式下 LLM 会接收 tools 定义）
        int toolsTokens = 0;
        if (trace.getConfig().getStyle() == ReActStyle.NATIVE_TOOL) {
            List<FunctionTool> toolsDef = new ArrayList<>();
            toolsDef.addAll(trace.getOptions().getTools());
            toolsDef.addAll(trace.getProtocolTools());

            if (Assert.isNotEmpty(toolsDef)) {
                toolsTokens = estimateToolsTokens(toolsDef);
            }
        }

        long messageSize = messages.stream()
                .filter(m -> !m.hasMetadata(AgentTrace.META_FIRST))
                .count();

        int currentTokens = estimateTokens(messages, systemPrompt) + toolsTokens;

        // 预留缓冲，避免频繁重构
        if (messageSize <= maxMessages && currentTokens <= (maxTokens * 0.8)) {
            pushContextChunk(trace, messages.size(), currentTokens, false, 0, 0, 0, 0);
            return;
        }

        // 1. 提取“初心链”
        List<ChatMessage> firstList = new ArrayList<>();
        int lastFirstIdx = -1;
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            if (msg.hasMetadata(AgentTrace.META_FIRST)) {
                firstList.add(msg);
                lastFirstIdx = i;
            }
        }

        // 2. 计算固定开销（不可压缩部分：systemPrompt + 初心链 + tools定义） —— 【已引入完备兜底】
        int fixedTokens = 0;
        if (!Assert.isEmpty(systemPrompt)) {
            fixedTokens += encoding.countTokens(systemPrompt) + 4;
        }
        fixedTokens += toolsTokens;

        for (ChatMessage firstMsg : firstList) {
            Integer cachedSize = firstMsg.getMetadataAs(META_TOKEN_SIZE);
            if (cachedSize == null) {
                cachedSize = 0;
                if (firstMsg.getContent() != null) {
                    cachedSize += encoding.countTokens(firstMsg.getContent());
                }
                if (firstMsg instanceof AssistantMessage) {
                    AssistantMessage am = (AssistantMessage) firstMsg;
                    if (Assert.isNotEmpty(am.getToolCalls())) {
                        for (ToolCall tc : am.getToolCalls()) {
                            String name = tc.getName() != null ? tc.getName() : "";
                            String args = tc.getArgumentsStr() != null ? tc.getArgumentsStr() : "";
                            cachedSize += encoding.countTokens(name + args) + 10;
                        }
                    }
                }
                firstMsg.addMetadata(META_TOKEN_SIZE, cachedSize);
            }
            fixedTokens += cachedSize + 4;
        }

        // 极端场景防御
        if (fixedTokens >= maxTokens) {
            if (log.isWarnEnabled()) {
                log.warn("ReActAgent [{}] first chain + systemPrompt ({} tokens) exceeds maxTokens ({}), keep first chain only",
                        trace.getAgentName(), fixedTokens, maxTokens);
            }
            if (firstList.size() < messages.size()) {
                trace.getWorkingMemory().replaceMessages(new ArrayList<>(firstList));
            }
            return;
        }

        // 3. 为压缩消息预留空间
        int availableTokens = maxTokens - fixedTokens;
        int summaryReserve = Math.max(200, (int) (availableTokens * 0.1));
        int windowBudget = availableTokens - summaryReserve;

        // 4. 双维度确定截断点
        //    - targetByCount ：按消息数量维度的截断位置
        //    - targetByTokens：按 Token 预算维度的截断位置（从尾向前累加）
        //    - minReservedIdx：保留窗口的绝对下限，防止 Token 维度过度截断
        int targetByCount = Math.max(lastFirstIdx + 1, messages.size() - maxMessages);

        int targetByTokens = lastFirstIdx + 1;
        int runningTokens = 0;
        for (int i = messages.size() - 1; i > lastFirstIdx; i--) {
            ChatMessage msg = messages.get(i);
            Integer cachedSize = msg.getMetadataAs(META_TOKEN_SIZE);
            runningTokens += (cachedSize != null ? cachedSize : 0) + 4;
            if (runningTokens > windowBudget) {
                targetByTokens = Math.max(lastFirstIdx + 1, i);
                break;
            }
        }

        // ⭐ 保留窗口绝对下限保护
        //    无论 Token 预算多紧张，保留窗口至少保留 minReservedMessages 条消息。
        //    这样可以避免单条大消息（如文件读取结果）占满预算后，
        //    Agent 的历史上下文被压缩到只剩 1~2 条消息，导致之前的工作白费。
        int minReservedIdx = Math.max(lastFirstIdx + 1, messages.size() - minReservedMessages);

        int rawTargetByTokens = targetByTokens;  // 保存原始Token截断位置，供步骤6.1使用
        int targetIdx = Math.max(targetByCount, Math.min(targetByTokens, minReservedIdx));
        targetIdx = alignStartToToolCallGroup(messages, targetIdx, lastFirstIdx + 1);

        // 5. ⭐ 原子对对齐（防止 tool-use 原子对被截断为两段）
        //    若 targetIdx 落在 ToolMessage 或 Observation 上，向前回退至配套的 Assistant(with tool_calls)
        //    若落在 Assistant(with tool_calls) 上，保留（这是原子对的正确起点）
        //    若落在普通消息上（User / Assistant thought），保留（不存在配对问题）
        while (targetIdx > (lastFirstIdx + 1) && targetIdx < messages.size()) {
            ChatMessage msg = messages.get(targetIdx);
            if (msg instanceof ToolMessage || isObservation(msg)) {
                targetIdx--; // 向前追溯匹配的源头 Assistant(with tool_calls)
            } else if (msg instanceof AssistantMessage && Assert.isNotEmpty(((AssistantMessage) msg).getToolCalls())) {
                break; // 已定位到源头，停止回退
            } else {
                break; // 无关消息，无需处理
            }
        }

        // 6. 语义连贯补齐：若截断点前一条为空想的 Assistant thought，一并纳入保留区
        //    使 LLM 获得完整的推理上下文
        if (targetIdx > (lastFirstIdx + 1)) {
            ChatMessage prev = messages.get(targetIdx - 1);
            if (prev instanceof AssistantMessage && Assert.isEmpty(((AssistantMessage) prev).getToolCalls())) {
                targetIdx--;
            }
        }

        // 6.1 Token 预算补偿校验 + 保护段摘要标记
        //    语义补齐（步骤 5-6）或 minReserved 保护可能导致保留窗口超出 Token 预算。
        //    需要区分两种场景：
        //    - 保护起效了（rawTargetByTokens > minReservedIdx）
        //      对保护段 [targetIdx..rawTargetByTokens) 做摘要压缩，不调整 targetIdx
        //    - 保护没起效
        //      回归 Token 预算截断位置，跳过 ToolMessage 头部
        boolean protectedSummaryApplied = false;
        int actualWindowTokens = 0;
        for (int i = targetIdx; i < messages.size(); i++) {
            Integer cachedSize = messages.get(i).getMetadataAs(META_TOKEN_SIZE);
            actualWindowTokens += (cachedSize != null ? cachedSize : 0) + 4;
        }
        if (actualWindowTokens > windowBudget) {
            if (rawTargetByTokens > minReservedIdx) {
                // 保护起效但预算超了 → 标记后续对保护段做摘要压缩，不调整 targetIdx
                protectedSummaryApplied = true;
                rawTargetByTokens = alignStartToToolCallGroup(messages, rawTargetByTokens, targetIdx);
            } else {
                // 保护没起效 → 回退到预算截断点，并按 tool-use 原子组向前补齐
                targetIdx = alignStartToToolCallGroup(messages, Math.max(lastFirstIdx + 1, targetByTokens), lastFirstIdx + 1);
            }
        }

        // 7. 重构 WorkingMemory
        List<ChatMessage> compressed = new ArrayList<>();
        compressed.addAll(firstList);

        if (targetIdx > (lastFirstIdx + 1) && targetIdx <= messages.size()) {
            List<ChatMessage> expired = new ArrayList<>(messages.subList(lastFirstIdx + 1, targetIdx));
            List<ChatMessage> pureHistory = expired.stream()
                    .filter(m -> !m.hasMetadata(META_COMPRESSED))
                    .collect(Collectors.toList());

            if (!pureHistory.isEmpty()) {
                if (compressionStrategy != null) {
                    ChatModel chatModel = chatModelSupplier.get();

                    ChatMessage summaryMsg = compressionStrategy.compress(chatModel, maxRetries, trace, pureHistory);
                    if (summaryMsg != null) {
                        compressed.add(summaryMsg);
                    }
                } else {
                    // ⭐ fallback 原子序列追溯（零成本裁剪路径，不调用 LLM 生成摘要）
                    //
                    // 问题背景：过期区（expired）可能包含不完整的 tool-use 原子对。
                    // 例如 [Assistant(tc=[search]), Tool(res)] 被整体移入过期区，
                    // 若直接丢弃、仅保留保留窗口内的后续轮次，恢复后的上下文会变成：
                    //   [摘要] → [Tool(res)] → [下一个 Assistant(tc=...)]
                    // 此时 Tool(res) 找不到配对的 tool_calls → LLM 感知异常。
                    //
                    // 追溯策略：从过期区末尾向前查找，找到最后一个完整的 tool-use 序列。
                    // 一条 Assistant(with tool_calls) 可能触发多个 ToolMessage，
                    // 甚至多轮交错调用如 [Assistant(tc=A), Tool(A1), Assistant(tc=B), Tool(B)]。
                    // 需要保留从最后一个源头 Assistant(with tc) 到末尾的全部消息。
                    int captureStart = pureHistory.size() - 1;
                    while (captureStart > 0) {
                        ChatMessage msg = pureHistory.get(captureStart);
                        if (msg instanceof ToolMessage || isObservation(msg)) {
                            captureStart--; // ToolMessage → 继续向前追溯源头
                        } else if (msg instanceof AssistantMessage
                                && Assert.isNotEmpty(((AssistantMessage) msg).getToolCalls())) {
                            break; // 找到源头 Assistant(with tc)，保留[captureStart..末尾]序列
                        } else {
                            // 遇到无关消息（User、普通 Assistant thought），
                            // 回退一步，至少保留一个 ToolMessage（宁可保留畸形的，不送孤立 ToolMessage）
                            if (captureStart < pureHistory.size() - 1) {
                                captureStart++;
                            }
                            break;
                        }
                    }

                    for (int i = captureStart; i < pureHistory.size(); i++) {
                        compressed.add(pureHistory.get(i));
                    }
                }
            }
        }

        // 7.1 ⭐ 保护段摘要压缩（minReserved 保护起效且预算超限时触发）
        //    当 minReserved 保护将本应被 Token 截断的消息保留下来，
        //    但保留窗口实际 Token 超预算时，需要对保护段 [targetIdx..rawTargetByTokens)
        //    做摘要压缩，而非简单丢弃。这样代码 Agent 之前读取的文件内容可以通过摘要保留关键信息。
        if (protectedSummaryApplied) {
            List<ChatMessage> protectedSegment = new ArrayList<>(messages.subList(targetIdx, rawTargetByTokens));
            List<ChatMessage> pureProtected = protectedSegment.stream()
                    .filter(m -> !m.hasMetadata(META_COMPRESSED))
                    .collect(Collectors.toList());

            if (!pureProtected.isEmpty()) {
                if (compressionStrategy != null) {
                    ChatModel chatModel = chatModelSupplier.get();
                    ChatMessage summaryMsg = compressionStrategy.compress(chatModel, maxRetries, trace, pureProtected);
                    if (summaryMsg != null) {
                        compressed.add(summaryMsg);
                    }
                } else {
                    // fallback：原子序列追溯（零成本裁剪路径，不调用 LLM）
                    int captureStart = pureProtected.size() - 1;
                    while (captureStart > 0) {
                        ChatMessage msg = pureProtected.get(captureStart);
                        if (msg instanceof ToolMessage || isObservation(msg)) {
                            captureStart--;
                        } else if (msg instanceof AssistantMessage
                                && Assert.isNotEmpty(((AssistantMessage) msg).getToolCalls())) {
                            break;
                        } else {
                            if (captureStart < pureProtected.size() - 1) {
                                captureStart++;
                            }
                            break;
                        }
                    }
                    for (int i = captureStart; i < pureProtected.size(); i++) {
                        compressed.add(pureProtected.get(i));
                    }
                }
            }

            // 保留窗口尾部（rawTargetByTokens..end）
            compressed.addAll(messages.subList(rawTargetByTokens, messages.size()));
        } else {
            compressed.addAll(messages.subList(targetIdx, messages.size()));
        }

        // 8. 更新工作区
        int beforeSize = messages.size();
        compressed = removeDanglingToolOutputs(compressed);
        if (!compressed.equals(messages)) {
            trace.getWorkingMemory().replaceMessages(compressed);

            if (log.isDebugEnabled()) {
                log.debug("ReActAgent [{}] compressed: {} -> {} messages (FirstChain size: {})",
                        trace.getAgentName(), beforeSize, compressed.size(), firstList.size());
            }

            int afterTokens = estimateTokens(compressed, null) + toolsTokens;
            pushContextChunk(trace, compressed.size(), afterTokens, true,
                            beforeSize, compressed.size(),
                            currentTokens, afterTokens);
        } else {
            // 压缩条件触发但实际未变更（兜底），仍推送当前状态
            pushContextChunk(trace, messages.size(), currentTokens, false, 0, 0, 0, 0);
        }
    }

    private int alignStartToToolCallGroup(List<ChatMessage> messages, int startIdx, int minIdx) {
        if (startIdx <= minIdx || startIdx >= messages.size()) {
            return startIdx;
        }

        ChatMessage msg = messages.get(startIdx);
        if (msg instanceof ToolMessage) {
            String toolCallId = ((ToolMessage) msg).getToolCallId();
            for (int i = startIdx - 1; i >= minIdx; i--) {
                ChatMessage prev = messages.get(i);
                if (prev instanceof AssistantMessage && Assert.isNotEmpty(((AssistantMessage) prev).getToolCalls())) {
                    if (toolCallId == null || hasToolCallId((AssistantMessage) prev, toolCallId)) {
                        return i;
                    }
                }

                if (!(prev instanceof ToolMessage) && !isObservation(prev)) {
                    break;
                }
            }
        } else if (isObservation(msg)) {
            for (int i = startIdx - 1; i >= minIdx; i--) {
                ChatMessage prev = messages.get(i);
                if (prev instanceof AssistantMessage && Assert.isNotEmpty(((AssistantMessage) prev).getToolCalls())) {
                    return i;
                }

                if (!(prev instanceof ToolMessage) && !isObservation(prev)) {
                    break;
                }
            }
        }

        return startIdx;
    }

    private List<ChatMessage> removeDanglingToolOutputs(List<ChatMessage> messages) {
        List<ChatMessage> result = new ArrayList<>(messages.size());

        for (int i = 0; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);

            if (msg instanceof AssistantMessage && Assert.isNotEmpty(((AssistantMessage) msg).getToolCalls())) {
                AssistantMessage assistant = (AssistantMessage) msg;
                List<ChatMessage> toolOutputs = new ArrayList<>();
                List<String> requiredCallIds = new ArrayList<>();

                for (ToolCall tc : assistant.getToolCalls()) {
                    if (tc.getId() != null) {
                        requiredCallIds.add(tc.getId());
                    }
                }

                int j = i + 1;
                for (; j < messages.size(); j++) {
                    ChatMessage next = messages.get(j);
                    if (next instanceof ToolMessage || isObservation(next)) {
                        toolOutputs.add(next);
                    } else {
                        break;
                    }
                }

                boolean completed = false;
                if (Assert.isNotEmpty(requiredCallIds)) {
                    List<String> remainingCallIds = new ArrayList<>(requiredCallIds);
                    for (ChatMessage toolOutput : toolOutputs) {
                        if (toolOutput instanceof ToolMessage) {
                            String toolCallId = ((ToolMessage) toolOutput).getToolCallId();
                            if (toolCallId != null) {
                                remainingCallIds.remove(toolCallId);
                            }
                        }
                    }
                    completed = remainingCallIds.isEmpty();
                } else {
                    completed = Assert.isNotEmpty(toolOutputs);
                }

                if (completed) {
                    result.add(msg);
                    for (ChatMessage toolOutput : toolOutputs) {
                        if (toolOutput instanceof ToolMessage) {
                            String toolCallId = ((ToolMessage) toolOutput).getToolCallId();
                            if (toolCallId == null || requiredCallIds.isEmpty() || requiredCallIds.contains(toolCallId)) {
                                result.add(toolOutput);
                            }
                        } else {
                            result.add(toolOutput);
                        }
                    }
                }

                i = j - 1;
                continue;
            }

            if (msg instanceof ToolMessage || isObservation(msg)) {
                continue;
            }

            result.add(msg);
        }

        return result;
    }

    private boolean hasToolCallId(AssistantMessage assistantMessage, String toolCallId) {
        for (ToolCall tc : assistantMessage.getToolCalls()) {
            if (toolCallId.equals(tc.getId())) {
                return true;
            }
        }

        return false;
    }

    /**
     * 单条消息硬上限兜底：对内容 Token 数超过 {@code perMessageCap} 的单条消息做头尾截断。
     *
     * <p>消息级压缩（裁剪/摘要历史）无法解决“单条消息自身超过上下文窗口”的问题——
     * 例如工具读取了一个超大文件或二进制流，产生一条数十万 Token 的 ToolMessage。
     * 此时无论怎么删别的消息，这一条都会把请求顶爆。故在所有裁剪逻辑之前先做内容级截断。</p>
     *
     * <p>仅处理文本型消息；多模态消息（含图片块等）跳过，避免破坏 ContentBlock 结构。
     * 截断后清除该消息的 {@code META_TOKEN_SIZE} 缓存以触发重算。</p>
     *
     * @return 处理后的消息列表（若发生截断会写回 WorkingMemory）
     */
    private List<ChatMessage> enforcePerMessageCap(ReActTrace trace, List<ChatMessage> messages) {
        // 单条消息最多占用半个窗口预算（且不低于绝对下限），超出即视为异常的超大消息
        int perMessageCap = Math.max(2_000, maxTokens / 2);

        boolean changed = false;
        List<ChatMessage> result = new ArrayList<>(messages.size());

        for (ChatMessage msg : messages) {
            // 初心链消息不截断（system prompt / 用户原始问题需完整保留）
            if (msg.hasMetadata(AgentTrace.META_FIRST)) {
                result.add(msg);
                continue;
            }

            String content = msg.getContent();
            if (content == null || content.isEmpty()) {
                result.add(msg);
                continue;
            }

            // 多模态消息跳过（避免破坏图片等非文本块）
            if ((msg instanceof ToolMessage && ((ToolMessage) msg).isMultiModal())
                    || (msg instanceof UserMessage && ((UserMessage) msg).isMultiModal())) {
                result.add(msg);
                continue;
            }

            // ⭐ 性能：优先用缓存初筛，避免每轮对全部消息做全量 BPE 编码。
            //    缓存值（含 toolCalls 开销）可能略大于纯 content token，作为“是否超限”的
            //    初筛是安全方向（宁可多检一条，不会漏检超限消息）。仅在缓存缺失或疑似
            //    超限时才精确 countTokens 确认。
            Integer cachedTokens = msg.getMetadataAs(META_TOKEN_SIZE);
            if (cachedTokens != null && cachedTokens <= perMessageCap) {
                result.add(msg);
                continue;
            }

            int contentTokens = encoding.countTokens(content);
            if (contentTokens <= perMessageCap) {
                result.add(msg);
                continue;
            }

            String truncated = truncateTextToTokens(content, perMessageCap);
            ChatMessage replacement = rebuildWithContent(msg, truncated);
            if (replacement == null) {
                // 无法安全重建（类型不支持）→ 保持原样，交由后续逻辑处理
                result.add(msg);
                continue;
            }

            result.add(replacement);
            changed = true;

            if (log.isWarnEnabled()) {
                log.warn("ReActAgent [{}] single message too large ({} tokens > cap {}), content truncated",
                        trace.getAgentName(), contentTokens, perMessageCap);
            }
        }

        if (changed) {
            trace.getWorkingMemory().replaceMessages(result);
            return result;
        }
        return messages;
    }

    /**
     * 重建一条带有新内容的消息，复制原 metadata（但清除 token_size 以触发重算）。
     * 支持的类型：
     * <ul>
     *   <li>{@link ToolMessage} —— 超大单条消息的主要来源（工具读取大文件）</li>
     *   <li>{@link UserMessage} —— 用户直接粘贴超长日志/文件（保留 Observation 前缀，因头部从索引 0 起截断）</li>
     *   <li>{@link AssistantMessage} —— 仅纯文本 thought（无 toolCalls）；含 toolCalls 的不重建，避免损坏推理链</li>
     * </ul>
     * 其它类型返回 null。
     */
    private ChatMessage rebuildWithContent(ChatMessage origin, String newContent) {
        if (origin instanceof ToolMessage) {
            ToolMessage tm = (ToolMessage) origin;
            ToolMessage rebuilt = new ToolMessage(
                    new org.noear.solon.ai.chat.tool.ToolResult(newContent),
                    tm.getName(),
                    tm.getToolCallId(),
                    tm.isReturnDirect());

            copyMetadataExceptTokenSize(origin, rebuilt);
            return rebuilt;
        }

        if (origin instanceof UserMessage) {
            // 用户粘贴的超长文本。多模态已在调用前跳过，这里按纯文本重建。
            UserMessage rebuilt = (UserMessage) ChatMessage.ofUser(newContent);
            copyMetadataExceptTokenSize(origin, rebuilt);
            return rebuilt;
        }

        if (origin instanceof AssistantMessage) {
            AssistantMessage am = (AssistantMessage) origin;
            // 含 toolCalls 的不截断 content（体积主要在 args，截断正文可能损坏推理链/原子对）
            if (Assert.isNotEmpty(am.getToolCalls())) {
                return null;
            }
            AssistantMessage rebuilt = new AssistantMessage(newContent, am.isThinking());
            copyMetadataExceptTokenSize(origin, rebuilt);
            return rebuilt;
        }

        return null;
    }

    /**
     * 复制原消息 metadata 到目标消息，跳过 {@link #META_TOKEN_SIZE}（让其按新内容重算）。
     */
    private void copyMetadataExceptTokenSize(ChatMessage origin, ChatMessage target) {
        Map<String, Object> meta = origin.getMetadata();
        if (meta != null) {
            for (Map.Entry<String, Object> e : meta.entrySet()) {
                if (META_TOKEN_SIZE.equals(e.getKey())) {
                    continue;
                }
                target.addMetadata(e.getKey(), e.getValue());
            }
        }
    }

    /**
     * 将文本按 Token 预算做头尾保留截断（中间插入显式占位）。
     * 以字符切分逼近，再用编码器精确收敛，确保结果不超过 maxTokens。
     */
    private String truncateTextToTokens(String text, int maxTokens) {
        if (text == null || encoding.countTokens(text) <= maxTokens) {
            return text;
        }

        String marker = "\n... [内容过大已截断：单条消息超过上下文预算，省略中间部分，仅保留首尾。"
                + "如需完整内容，请用分页方式重新获取] ...\n";
        int markerTokens = encoding.countTokens(marker);
        int budget = Math.max(0, maxTokens - markerTokens);
        int headTokens = budget / 2;
        int tailTokens = budget - headTokens;

        // 字符与 Token 的经验比例（保守取 3 字符/Token），逐步收敛避免超限
        int headChars = Math.min(text.length(), headTokens * 3);
        int tailChars = Math.min(text.length() - headChars, tailTokens * 3);

        String head = text.substring(0, headChars);
        while (encoding.countTokens(head) > headTokens && head.length() > 0) {
            int newLen = Math.min(head.length() - 1, head.length() * 9 / 10);
            head = head.substring(0, Math.max(0, newLen));
        }

        String tail = text.substring(text.length() - tailChars);
        while (encoding.countTokens(tail) > tailTokens && tail.length() > 0) {
            int cut = Math.max(1, tail.length() / 10);
            tail = tail.substring(cut);
        }

        return head + marker + tail;
    }

    private int estimateTokens(List<ChatMessage> messages, String systemPrompt) {
        int totalTokens = 0;
        for (ChatMessage m : messages) {
            // 尝试从元数据获取缓存值
            Integer cachedCount = m.getMetadataAs(META_TOKEN_SIZE);

            if (cachedCount == null) {
                cachedCount = 0;
                if (m.getContent() != null) {
                    cachedCount += encoding.countTokens(m.getContent());
                }

                // 补算 AssistantMessage 的 toolCalls 序列化开销
                if (m instanceof AssistantMessage) {
                    AssistantMessage am = (AssistantMessage) m;
                    if (Assert.isNotEmpty(am.getToolCalls())) {
                        for (ToolCall tc : am.getToolCalls()) {
                            String name = tc.getName() != null ? tc.getName() : "";
                            String args = tc.getArgumentsStr() != null ? tc.getArgumentsStr() : "";

                            cachedCount += encoding.countTokens(name + args);
                            cachedCount += 10; // id + JSON 结构开销
                        }
                    }
                }

                // 将计算结果回填到消息元数据中
                m.addMetadata(META_TOKEN_SIZE, cachedCount);
            }

            totalTokens += cachedCount + 4; // Overhead
        }

        // systemPrompt 的 token 开销
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            totalTokens += encoding.countTokens(systemPrompt) + 4;
        }

        return totalTokens + 3;
    }

    /**
     * 估算 tools 定义（FunctionTool 的 name + description + inputSchema）的 Token 开销。
     * 这些定义在每次 LLM 调用时都会作为请求的一部分发送，属于固定开销。
     */
    private int estimateToolsTokens(Collection<FunctionTool> tools) {
        int tokens = 0;
        for (FunctionTool tool : tools) {
            // name
            if (tool.name() != null) {
                tokens += encoding.countTokens(tool.name());
            }
            // description（含 meta 信息）
            if (tool.descriptionAndMeta() != null) {
                tokens += encoding.countTokens(tool.descriptionAndMeta());
            }
            // inputSchema（JSON Schema 定义）
            if (tool.inputSchema() != null) {
                tokens += encoding.countTokens(tool.inputSchema());
            }
            tokens += 15; // JSON 结构开销（type, function, parameters 等字段）
        }
        return tokens;
    }

    private boolean isObservation(ChatMessage msg) {
        return (msg instanceof ToolMessage) ||
                (msg instanceof UserMessage && msg.getContent() != null && msg.getContent().startsWith("Observation:"));
    }

    /**
     * ⭐ 标记静态上下文的边界，为 Dialect 层的 cache_control 提供依据。
     *
     * <p>静态上下文包含系统提示词（systemPrompt）和工具定义（tool definitions），
     * 这些内容在会话中不会频繁变化，最适合 LLM 提供商的原生 Prompt Caching。</p>
     *
     * <p>当用户在 ChatOptions 中配置了 cacheControl（Anthropic 风格）或
     * promptCacheKey（OpenAI 风格）时，此方法确保静态上下文在压缩过程中
     * 保持结构完整性，不会被摘要或裁剪破坏其内容边界。</p>
     *
     * <p>当前设计：
     * <ul>
     *   <li>系统提示词通过 systemPromptBuf 维护，不在消息列表中压缩</li>
     *   <li>工具定义由 Dialect 层（如 AnthropicRequestBuilder）在构建请求时添加 cache_control</li>
     *   <li>此方法仅在开启缓存时输出调试日志，帮助验证缓存策略是否正确生效</li>
     * </ul>
     * </p>
     *
     * @param trace        当前 ReAct 追踪
     * @param systemPrompt 系统提示词内容（可能为 null）
     */
    private void markStaticContextBoundary(ReActTrace trace, String systemPrompt) {
        // 通过 ChatModel -> ChatConfigReadonly -> ChatOptions 获取缓存配置
        CacheControl cacheControl = trace.getOptions().getChatModel().getConfig().getCacheControl();
        if (cacheControl != null) {
            // 静态上下文就绪：不修改消息内容，仅输出调试信息
            // Dialect 层在构建 HTTP 请求时会根据 ChatOptions 中的 cacheControl/promptCacheKey
            // 自动在系统提示词和工具定义上添加 cache_control 标记
            if (log.isDebugEnabled()) {
                String cacheType = (cacheControl != null)
                        ? "Anthropic cache_control=" + cacheControl.getType()
                        : "OpenAI prompt_cache_key=" + cacheControl.getPromptCacheKey();
                log.debug("ReActAgent [{}] static context boundary marked for LLM prompt caching ({})",
                        trace.getAgentName(), cacheType);
            }
        }
    }

    private void pushContextChunk(ReActTrace trace, int msgCount, int tokenCount,
                                  boolean compressed,
                                  int beforeMessageCount, int afterMessageCount,
                                  int beforeTokenCount, int afterTokenCount) {
        try {
            FluxSink<AgentChunk> sink = trace.getOptions().getStreamSink();
            if (sink != null && !sink.isCancelled()) {
                sink.next(new ContextSizeChunk(trace, msgCount, tokenCount, compressed,
                        beforeMessageCount, afterMessageCount,
                        beforeTokenCount, afterTokenCount));
            }
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("ReActAgent [{}] failed to push ContextChunk: {}",
                        trace.getAgentName(), e.getMessage());
            }
        }
    }
}