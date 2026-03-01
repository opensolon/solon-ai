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
        this.maxMessages = Math.max(12, maxMessages);
        this.summarizationStrategy = summarizationStrategy;
    }

    public SummarizationInterceptor(int maxMessages) {
        this(maxMessages, null);
    }

    public SummarizationInterceptor() {
        this(12, null);
    }

    @Override
    public void onObservation(ReActTrace trace, String toolName, String result, long durationMs) {
        List<ChatMessage> messages = trace.getWorkingMemory().getMessages();

        // 预留缓冲，避免频繁重构 (maxMessages + 触发阈值)
        if (messages.size() <= maxMessages + 2) return;

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
        while (targetIdx > 0 && targetIdx > lastFirstIdx + 1) {
            ChatMessage msg = messages.get(targetIdx);
            if (msg instanceof ToolMessage || isObservation(msg)) {
                // 如果当前是 Observation，必须向前找对应的 Action
                targetIdx--;
            } else if (msg instanceof AssistantMessage && Assert.isNotEmpty(((AssistantMessage) msg).getToolCalls())) {
                // 如果当前是带工具调用的 Action，尝试看其之后是否对齐
                // 实际上对齐逻辑主要靠向前回溯
                targetIdx--;
            } else {
                break;
            }
        }

        // 4. 语义连贯补齐 (Semantic Completion)
        if (targetIdx > lastFirstIdx + 1) {
            ChatMessage prev = messages.get(targetIdx - 1);
            // 如果前一条是纯文本 Assistant 消息（Thought），包含进来作为上下文起始
            if (prev instanceof AssistantMessage && Assert.isEmpty(((AssistantMessage) prev).getToolCalls())) {
                targetIdx--;
            }
        }

        // 5. 重构 WorkingMemory
        List<ChatMessage> compressed = new ArrayList<>();

        // 策略 A: 保持 SystemMessage (全局约束)
        messages.stream()
                .filter(m -> m instanceof SystemMessage && !m.hasMetadata("_first"))
                .findFirst()
                .ifPresent(compressed::add);

        // 策略 B: 注入“初心链” (通过 metadata _first 标记的所有历史记录)
        for (ChatMessage firstMsg : firstList) {
            if (!compressed.contains(firstMsg)) {
                compressed.add(firstMsg);
            }
        }

        // 策略 C: 语义总结或物理断裂标记
        if (targetIdx > (lastFirstIdx + 1)) {
            if (summarizationStrategy != null) {
                // 提取“初心链”之后、活跃窗口之前的内容进行摘要
                List<ChatMessage> expired = messages.subList(lastFirstIdx + 1, targetIdx);
                ChatMessage summaryMsg = summarizationStrategy.summarize(trace, expired);
                if (summaryMsg != null) {
                    compressed.add(summaryMsg);
                }
            } else {
                String marker = "--- [Historical context optimized. ";
                marker += (trace.hasPlans() ? "Refer to plans for progress.] ---" : "Focus on recent steps.] ---");
                compressed.add(ChatMessage.ofSystem(marker));
            }
        }

        // 策略 D: 装载活跃窗口消息
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