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
package org.noear.solon.ai.chat;

import org.noear.solon.Utils;
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.event.ChatEvent;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.MessageProtocolState;
import org.noear.solon.ai.chat.source.Citation;
import org.noear.solon.ai.chat.source.SearchResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.noear.solon.ai.chat.tool.ToolCall;

/**
 * 聊天响应实现（只读快照）
 *
 * <p>它只承担一个角色：<b>模型调用的结果</b>。所有顶层字段在构造期确定，且不提供写入方法；
 * 完整消息由 {@link AssistantMessage#snapshot(String, String, List, List, List, List, Map, Map)}
 * 在终态边界一次性构造。</p>
 *
 * <p>历史沿革：4.1 之前本类同时是结果对象、可变累积器与协议状态袋；拆分后累积器职责在
 * {@link ChatAccumulator}（框架内部），本类不再有任何写入方法。</p>
 *
 * @author noear
 * @since 3.1
 */
public class ChatResponseDefault implements ChatResponse {
    private final boolean terminal;

    private final String frameRaw;
    private final String model;
    private final ChatException error;
    private final AiUsage usage;

    /**
     * 结果消息（构造期算定；仅终态快照包含完整聚合消息）
     */
    private final AssistantMessage message;
    /**
     * 完成原因（已归一化，构造期算定）
     */
    private final String finishReason;
    /**
     * 非流式路径上方言产出的语义事件（构造期快照）
     */
    private final List<ChatEvent> events;

    /** 创建带跨步骤累计 usage 的终态响应副本。 */
    protected ChatResponseDefault(ChatResponse source, AiUsage usage) {
        this.terminal = true;
        this.frameRaw = source == null ? null : source.getFrameRaw();
        this.model = source == null ? null : source.getModel();
        this.error = source == null ? null : source.getError();
        this.usage = usage;
        this.message = source == null ? null : source.getMessage();
        this.finishReason = source == null ? "stop" : source.getFinishReason();
        this.events = source == null || source.getEvents().isEmpty()
                ? Collections.emptyList() : source.getEvents();
    }

    protected ChatResponseDefault(ChatAccumulator acc, boolean terminal) {
        this(acc, terminal, null);
    }

    /**
     * 创建响应快照，并可为全流终态覆盖跨步骤累计用量。
     */
    protected ChatResponseDefault(ChatAccumulator acc, boolean terminal, AiUsage usageOverride) {
        this.terminal = terminal;

        this.frameRaw = acc.getFrameRaw();
        this.model = acc.getModel();
        this.error = acc.getError();
        this.usage = usageOverride == null ? acc.getUsage() : usageOverride;
        this.events = acc.getEvents().isEmpty()
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(acc.getEvents()));

        if (terminal) {
            this.message = buildAggregationMessage(acc);
            //终态契约：无任何 finishReason 信号时默认正常结束（与旧 getLastFinishReasonNormalized 一致）
            String rawFinish = ChatAccumulator.normalizeFinishReason(acc.lastFinishReason);
            this.finishReason = rawFinish != null ? rawFinish : "stop";
        } else {
            // 流式语义只由 ChatEvent 承载；分片快照永不伪装成最终 AssistantMessage。
            this.message = null;
            this.finishReason = ChatAccumulator.normalizeFinishReason(acc.lastFinishReason);
        }
    }

    /**
     * 终态聚合消息（逻辑与旧 getAggregationMessage 一致，构造期执行一次）
     */
    private static AssistantMessage buildAggregationMessage(ChatAccumulator acc) {
        // Event-first：正文与思考优先取事件聚合；完整终态载体只作为旧方言/协议解析的兼容回退。
        // 不再按 stream 分叉，否则相同事件在 call()/stream() 下会得到不同终态。
        String text = acc.getAggregationText();
        if (Utils.isEmpty(text) && acc.getTerminalText() != null) {
            text = acc.getTerminalText();
        }
        String thinking = acc.getAggregationThinking();
        if (Utils.isEmpty(thinking) && acc.getTerminalThinking() != null) {
            thinking = acc.getTerminalThinking();
        }
        if (text == null) text = "";
        if (thinking == null) thinking = "";

        boolean present = acc.isTerminalMessagePresent()
                || text.length() > 0
                || thinking.length() > 0
                || Utils.isNotEmpty(acc.getMediaBlocks())
                || Utils.isNotEmpty(acc.getTerminalMediaBlocks())
                || Utils.isNotEmpty(acc.getAggregationSearchResults())
                || Utils.isNotEmpty(acc.getTerminalSearchResults())
                || Utils.isNotEmpty(acc.getAggregationCitations())
                || Utils.isNotEmpty(acc.getTerminalCitations())
                || Utils.isNotEmpty(acc.getTerminalProtocolStates());
        if (!present) {
            return null;
        }

        List<ContentBlock> aggBlocks = buildAggregationBlocks(acc, text);
        java.util.Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        if (Utils.isNotEmpty(acc.getAggregationMetadata())) metadata.putAll(acc.getAggregationMetadata());
        if (Utils.isNotEmpty(acc.getTerminalMetadata())) metadata.putAll(acc.getTerminalMetadata());

        AssistantMessage message;
        boolean legacyCarrier = acc.getTerminalContentRaw() != null
                || acc.getTerminalToolCallsRaw() != null
                || acc.getTerminalSearchResultsRaw() != null
                || acc.getTerminalReasoningFieldName() != null;
        List<SearchResult> searchResults = Utils.isNotEmpty(acc.getAggregationSearchResults())
                ? new ArrayList<>(acc.getAggregationSearchResults())
                : copyOrNull(acc.getTerminalSearchResults());
        List<Citation> citations = Utils.isNotEmpty(acc.getAggregationCitations())
                ? new ArrayList<>(acc.getAggregationCitations())
                : copyOrNull(acc.getTerminalCitations());
        if (legacyCarrier) {
            message = AssistantMessage.legacySnapshot(
                    text,
                    thinking,
                    acc.getTerminalContentRaw(),
                    acc.getTerminalToolCallsRaw(),
                    acc.getTerminalToolCalls(),
                    acc.getTerminalSearchResultsRaw(),
                    acc.getTerminalReasoningFieldName(),
                    aggBlocks,
                    searchResults,
                    citations,
                    acc.getTerminalProtocolStates(),
                    metadata);
        } else {
            message = AssistantMessage.snapshot(
                    text,
                    thinking,
                    acc.getTerminalToolCalls(),
                    aggBlocks,
                    searchResults,
                    citations,
                    acc.getTerminalProtocolStates(),
                    metadata);
        }
        return message;
    }



    private static <T> List<T> copyOrNull(List<T> source) {
        return Utils.isEmpty(source) ? null : new ArrayList<>(source);
    }

    /**
     * 构建聚合消息的 blocks。
     * <p>{@code MEDIA_DONE} 聚合结果是流式与非流式共同的权威来源；仅在没有媒体事件时，
     * 才回退到完整终态载体中的媒体。方言若从完整消息投影媒体，必须为每个块发出一次
     * {@code MEDIA_DONE}，因此不能再把两份列表简单相加。</p>
     */
    private static List<ContentBlock> buildAggregationBlocks(ChatAccumulator acc, String text) {
        List<ContentBlock> agg = new ArrayList<>();

        if (Utils.isNotEmpty(acc.getMediaBlocks())) {
            // 事件是流式语义的权威来源；正文与媒体按事件到达顺序保留。
            if (Utils.isNotEmpty(acc.getOrderedBlocks())) {
                List<ContentBlock> ordered = new ArrayList<>(acc.getOrderedBlocks());
                if (Utils.isNotEmpty(text) && containsTextBlock(ordered) == false) {
                    // 非流式兼容方言可能只把媒体投影为事件、正文仍保存在完整终态载体中。
                    // 此时不能让媒体事件遮蔽正文块；流式正文已有 TEXT_DELTA 时 ordered 中本就包含 TextBlock。
                    ordered.add(0, TextBlock.of(text));
                }
                return ordered;
            }
            agg.addAll(acc.getMediaBlocks());
        } else if (Utils.isNotEmpty(acc.getTerminalMediaBlocks())) {
            // 完整终态载体中的 blocks 已有协议顺序，必须原样保留 TextBlock 与媒体的相对位置。
            return new ArrayList<>(acc.getTerminalMediaBlocks());
        }

        if (Utils.isEmpty(agg)) {
            // 纯文本保持旧形态：不填充 blocks
            return null;
        }

        List<ContentBlock> result = new ArrayList<>();
        if (Utils.isNotEmpty(text)) {
            result.add(TextBlock.of(text));
        }
        result.addAll(agg);
        return result;
    }

    private static boolean containsTextBlock(List<ContentBlock> blocks) {
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否为终态（true 时 getMessage() 即完整聚合）
     */
    @Override
    public boolean isTerminal() {
        return terminal;
    }

    @Override
    public String getFrameRaw() {
        return frameRaw;
    }

    @Override
    public String getModel() {
        return model;
    }

    @Override
    public ChatException getError() {
        return error;
    }

    @Override
    public AssistantMessage getMessage() {
        return message;
    }

    @Override
    public boolean isEmpty() {
        if (message == null) {
            return true;
        }

        return Utils.isEmpty(message.getContent())
                && !message.hasThinking()
                && Utils.isEmpty(message.getToolCalls())
                && Utils.isEmpty(message.getBlocks())
                && Utils.isEmpty(message.resolveSearchResults())
                && Utils.isEmpty(message.getCitations())
                && !message.hasProtocolStates();
    }

    @Override
    public boolean hasContent() {
        return message != null && message.hasContent();
    }

    @Override
    public String getContent() {
        return message == null ? null : message.getContent();
    }

    @Override
    public String getText() {
        return message == null ? null : message.getText();
    }

    @Override
    public String getThinking() {
        return message == null ? null : message.getThinking();
    }

    @Override
    public List<ToolCall> getToolCalls() {
        if (message == null || Utils.isEmpty(message.getToolCalls())) {
            return Collections.emptyList();
        }

        return Collections.unmodifiableList(message.getToolCalls());
    }

    @Override
    public String getFinishReason() {
        return finishReason;
    }

    @Override
    public List<ContentBlock> getBlocks() {
        if (message == null || Utils.isEmpty(message.getBlocks())) {
            return Collections.emptyList();
        }

        return Collections.unmodifiableList(message.getBlocks());
    }

    @Override
    public List<SearchResult> getSearchResults() {
        if (message == null) {
            return Collections.emptyList();
        }
        List<SearchResult> results = message.resolveSearchResults();
        return results.isEmpty() ? Collections.<SearchResult>emptyList()
                : Collections.unmodifiableList(results);
    }

    @Override
    public List<Citation> getCitations() {
        if (message == null || Utils.isEmpty(message.getCitations())) {
            return Collections.emptyList();
        }

        return Collections.unmodifiableList(message.getCitations());
    }

    @Override
    public AiUsage getUsage() {
        return usage;
    }

    @Override
    public List<ChatEvent> getEvents() {
        return events;
    }

    @Override
    public String toString() {
        return "ChatResponse{" +
                (terminal ? "terminal" : "frame") +
                ", model='" + model + '\'' +
                ", finishReason='" + finishReason + '\'' +
                ", message=" + message +
                '}';
    }
}
