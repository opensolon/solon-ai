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

import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.message.*;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 语义保护型上下文压缩拦截器 (Atomic & Semantic Context Compressor)
 *
 * @author noear
 * @since 3.9.4
 */
@Preview("3.8.2")
public class SummarizationInterceptor implements ReActInterceptor {
    private static final Logger log = LoggerFactory.getLogger(SummarizationInterceptor.class);

    private final int maxMessages;
    private final SummarizationStrategy summarizationStrategy;

    public SummarizationInterceptor(int maxMessages, SummarizationStrategy summarizationStrategy) {
        this.maxMessages = Math.max(6, maxMessages);
        this.summarizationStrategy = summarizationStrategy;
    }

    public SummarizationInterceptor(int maxMessages) {
        this(maxMessages, null);
    }

    public SummarizationInterceptor() {
        /**
         * 仅使用 LLMSummarization / HierarchicalSummarization：maxMessages: 10 - 14
         * 仅使用 KeyInfoExtraction：maxMessages: 15 - 20
         * 仅使用 VectorStoreSummarization：maxMessages: 18 - 25
         * 推荐 maxMessages: 10 - 12
         * */

        this(12, null);
    }

    @Override
    public void onObservation(ReActTrace trace, String toolName, String result, long durationMs) {
        List<ChatMessage> messages = trace.getWorkingMemory().getMessages();

        long messageSize = messages.stream()
                .filter(m -> !m.hasMetadata(ReActAgent.META_FIRST))
                .count();

        // 预留缓冲，避免频繁重构 (maxMessages + 触发阈值)
        if (messageSize <= maxMessages) return;

        // 1. 提取“初心链” (The Original Intent Chain)
        List<ChatMessage> firstList = new ArrayList<>();
        int lastFirstIdx = -1;
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            if (msg.hasMetadata(ReActAgent.META_FIRST)) {
                firstList.add(msg);
                lastFirstIdx = i;
            }
        }

        // 2. 确定截断起始点 (Sliding Window Start)
        int targetIdx = messages.size() - maxMessages;

        // 3. 增强版原子对齐 (Atomic Alignment)
        while (targetIdx > (lastFirstIdx + 1) && targetIdx < messages.size()) {
            ChatMessage msg = messages.get(targetIdx);
            if (msg instanceof ToolMessage || isObservation(msg)) {
                targetIdx--;
            } else if (msg instanceof AssistantMessage && Assert.isNotEmpty(((AssistantMessage) msg).getToolCalls())) {
                // 停止回溯，这是一个 Action 节点
                break;
            } else {
                break;
            }
        }

        // 4. 语义连贯补齐 (Semantic Completion)
        if (targetIdx > (lastFirstIdx + 1)) {
            ChatMessage prev = messages.get(targetIdx - 1);
            if (prev instanceof AssistantMessage && Assert.isEmpty(((AssistantMessage) prev).getToolCalls())) {
                targetIdx--;
            }
        }

        // 5. 重构 WorkingMemory
        List<ChatMessage> compressed = new ArrayList<>();

        compressed.addAll(firstList);

        if (targetIdx > (lastFirstIdx + 1)) {
            List<ChatMessage> expired = messages.subList(lastFirstIdx + 1, targetIdx);
            // 过滤掉 expired 中可能存在的旧摘要标记消息，避免“摘要的摘要”产生标题堆叠
            List<ChatMessage> pureHistory = expired.stream()
                    .filter(m -> !m.hasMetadata(ReActAgent.META_SUMMARY))
                    .collect(Collectors.toList());

            if (summarizationStrategy != null && !pureHistory.isEmpty()) {
                ChatMessage summaryMsg = summarizationStrategy.summarize(trace, pureHistory);
                if (summaryMsg != null) {
                    compressed.add(summaryMsg);
                }
            }
        }

        compressed.addAll(messages.subList(targetIdx, messages.size()));

        // 6. 更新工作区
        if (compressed.size() < messages.size()) {
            trace.getWorkingMemory().replaceMessages(compressed);

            if (log.isDebugEnabled()) {
                log.debug("ReActAgent [{}] summarized: {} -> {} messages (FirstChain size: {})",
                        trace.getAgentName(), messages.size(), compressed.size(), firstList.size());
            }
        }
    }

    private boolean isObservation(ChatMessage msg) {
        return (msg instanceof ToolMessage) ||
                (msg instanceof UserMessage && msg.getContent() != null && msg.getContent().startsWith("Observation:"));
    }
}