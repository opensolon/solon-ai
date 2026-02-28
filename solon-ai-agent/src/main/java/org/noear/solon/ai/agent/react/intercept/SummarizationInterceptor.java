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
        this.maxMessages = Math.max(6, maxMessages);
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
        // 预留缓冲，避免频繁重构
        if (messages.size() <= maxMessages + 2) return;

        // 1. 寻找核心锚点（初心：首个 UserMessage）
        ChatMessage firstUserMsg = null;
        int firstUserIdx = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof UserMessage) {
                firstUserMsg = messages.get(i);
                firstUserIdx = i;
                break;
            }
        }

        // 2. 确定截断起始点
        int targetIdx = messages.size() - maxMessages;

        // 3. 增强版原子对齐 (Atomic Alignment)
        // 确保 Action 和 Observation 不被分离，且不越过 User 任务开始点
        while (targetIdx > 0 && targetIdx > firstUserIdx + 1) {
            ChatMessage msg = messages.get(targetIdx);
            if (msg instanceof ToolMessage || isObservation(msg)) {
                targetIdx--;
            } else if (msg instanceof AssistantMessage && Assert.isNotEmpty(((AssistantMessage) msg).getToolCalls())) {
                targetIdx--;
            } else {
                break;
            }
        }

        // 4. 语义连贯补齐 (Semantic Completion)
        // 尝试以“Thought”作为历史的首条消息
        if (targetIdx > firstUserIdx + 1) {
            ChatMessage prev = messages.get(targetIdx - 1);
            if (prev instanceof AssistantMessage && Assert.isEmpty(((AssistantMessage) prev).getToolCalls())) {
                targetIdx--;
            }
        }

        // 5. 重构 WorkingMemory
        List<ChatMessage> compressed = new ArrayList<>();

        // 策略 A: 保持全局系统角色（抓取第一条原始指令）
        messages.stream()
                .filter(m -> m instanceof SystemMessage)
                .findFirst()
                .ifPresent(compressed::add);

        // 策略 B: 保持原始任务初心
        if (firstUserMsg != null && !compressed.contains(firstUserMsg)) {
            compressed.add(firstUserMsg);
        }

        // 策略 C: 语义总结或注入物理断裂标记
        if (targetIdx > (firstUserIdx + 1)) {
            if (summarizationStrategy != null) {
                // 提取裁减区进行摘要加工
                List<ChatMessage> expired = messages.subList(firstUserIdx + 1, targetIdx);
                ChatMessage summaryMsg = summarizationStrategy.summarize(trace, expired);
                if (summaryMsg != null) {
                    compressed.add(summaryMsg);
                }
            } else {String marker = "--- [Historical context trimmed for optimization.";
                if (trace.hasPlans()) {
                    marker += " Refer to current plans for state.] ---";
                } else {
                    marker += " Focus on the latest conversation.] ---";
                }
                compressed.add(ChatMessage.ofSystem(marker));
            }
        }

        // 策略 D: 装载滑动窗口内的活跃消息
        compressed.addAll(messages.subList(targetIdx, messages.size()));

        if (log.isDebugEnabled()) {
            log.debug("ReActAgent [{}] summarized context: {} -> {} messages (aligned at index {})",
                    trace.getAgentName(), messages.size(), compressed.size(), targetIdx);
        }

        // 6. 更新工作区
        trace.getWorkingMemory().replaceMessages(compressed);
    }

    private boolean isObservation(ChatMessage msg) {
        if (msg instanceof ToolMessage) {
            return true;
        } else {
            // 兼容非 Native Tool 模式下的 ReAct 文本协议
            return msg instanceof UserMessage && msg.getContent() != null && msg.getContent().startsWith("Observation:");
        }
    }
}