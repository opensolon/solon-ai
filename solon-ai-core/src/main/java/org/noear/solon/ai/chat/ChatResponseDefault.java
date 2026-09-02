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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.noear.solon.ai.chat.tool.ToolCall;

/**
 * 聊天响应实现（不可变结果）
 *
 * <p>它只承担一个角色：<b>模型调用的结果</b>。所有字段在构造期定死，构造后不可再变。</p>
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
     * 结果消息（构造期算定：终态为完整聚合，分片帧为当帧分片）
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

    protected ChatResponseDefault(ChatAccumulator acc, boolean terminal) {
        this.terminal = terminal;

        this.frameRaw = acc.getFrameRaw();
        this.model = acc.getModel();
        this.error = acc.getError();
        this.usage = acc.getUsage();
        this.events = acc.getEvents().isEmpty()
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(acc.getEvents()));

        if (terminal) {
            this.message = buildAggregationMessage(acc);
            //终态契约：无任何 finishReason 信号时默认正常结束（与旧 getLastFinishReasonNormalized 一致）
            String rawFinish = ChatAccumulator.normalizeFinishReason(acc.lastFinishReason);
            this.finishReason = rawFinish != null ? rawFinish : "stop";
        } else {
            this.message = frameMessage(acc);
            this.finishReason = ChatAccumulator.normalizeFinishReason(acc.lastFinishReason);
        }
    }

    /**
     * 分片帧的消息：优先当帧分片；流式仅 media 尚无内容项时，回落聚合消息
     */
    private static AssistantMessage frameMessage(ChatAccumulator acc) {
        if (acc.hasContentItems()) {
            return acc.lastItem();
        }

        // Responses 流式 image_generation_call 等：只收 mediaBlocks 不推内容项
        if (acc.isStream() && Utils.isNotEmpty(acc.getMediaBlocks())) {
            return buildAggregationMessage(acc);
        }

        return null;
    }

    /**
     * 终态聚合消息（逻辑与旧 getAggregationMessage 一致，构造期执行一次）
     */
    private static AssistantMessage buildAggregationMessage(ChatAccumulator acc) {
        if (acc.hasContentItems()) {
            AssistantMessage lastMsg = acc.lastItem();

            if (acc.isStream()) {
                List<ContentBlock> aggBlocks = buildAggregationBlocks(acc, lastMsg);

                return new AssistantMessage(
                        acc.getAggregationText(),
                        acc.getAggregationThinking(),
                        acc.getAggregationText().length() == 0 && acc.getAggregationThinking().length() > 0,
                        lastMsg.getContentRaw(),
                        lastMsg.getToolCallsRaw(),
                        lastMsg.getToolCalls(),
                        lastMsg.getSearchResultsRaw(),
                        aggBlocks
                ).reasoningFieldName(lastMsg.getReasoningFieldName())
                        .addMetadata(acc.getAggregationMetadata());
            } else {
                // 非流式：一次响应就是一条结果。方言可能把它拆成多条内容项（如思考项 + 工具调用项），
                // 取末条——工具调用总在最后一项上，取首条会丢掉 toolCalls。
                // 这也与 ChatRequestDescDefault 写入记忆时的取值（lastItem）保持同一来源
                return lastMsg;
            }
        } else {
            if (acc.getAggregationText().length() > 0
                    || acc.getAggregationThinking().length() > 0
                    || Utils.isNotEmpty(acc.getMediaBlocks())) {
                List<ContentBlock> aggBlocks = buildAggregationBlocks(acc, null);

                return new AssistantMessage(
                        acc.getAggregationText(),
                        acc.getAggregationThinking(),
                        false,
                        null, null, null, null,
                        aggBlocks)
                        .reasoningFieldName(acc.reasoning_field_name)
                        .addMetadata(acc.getAggregationMetadata());
            } else {
                return null;
            }
        }
    }

    /**
     * 构建聚合消息的 blocks：文本投影 + 流中媒体 + 最后一条消息媒体
     */
    private static List<ContentBlock> buildAggregationBlocks(ChatAccumulator acc, AssistantMessage last) {
        List<ContentBlock> agg = new ArrayList<>();

        // 优先使用流式过程中已收集的 mediaBlocks（publishChoice / 方言终态写入）。
        // 不再与 last.blocks 叠加，避免同一媒体被聚合两次。
        if (Utils.isNotEmpty(acc.getMediaBlocks())) {
            agg.addAll(acc.getMediaBlocks());
        } else if (last != null && last.hasMedia()) {
            // 兜底：媒体只挂在最后一条消息、未进入 mediaBlocks 的路径
            for (ContentBlock block : last.getBlocks()) {
                if (!(block instanceof TextBlock)) {
                    agg.add(block);
                }
            }
        }

        if (Utils.isEmpty(agg)) {
            // 纯文本保持旧形态：不填充 blocks
            return null;
        }

        List<ContentBlock> result = new ArrayList<>();
        if (Utils.isNotEmpty(acc.getAggregationText())) {
            result.add(TextBlock.of(acc.getAggregationText()));
        }
        result.addAll(agg);
        return result;
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

        return message.getContent() == null
                && Utils.isEmpty(message.getToolCalls())
                && Utils.isEmpty(message.getBlocks());
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
