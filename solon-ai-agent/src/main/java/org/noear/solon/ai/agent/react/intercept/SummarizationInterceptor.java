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
package org.noear.solon.ai.agent.react.intercept;

import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.message.*;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Preview;

import java.util.ArrayList;
import java.util.List;

/**
 * 智能上下文压缩拦截器（基于 Token 与 逻辑对齐）
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public class SummarizationInterceptor implements ReActInterceptor {
    private final int maxMessages;   // 最大保留条数
    private final int maxTokens;     // 最大 Token 预算（例如 4096）
    private static final String TRIM_MARKER = "[Historical context trimmed]";

    public SummarizationInterceptor(int maxMessages, int maxTokens) {
        this.maxMessages = Math.max(4, maxMessages);
        this.maxTokens = Math.max(1000, maxTokens);
    }

    public SummarizationInterceptor() {
        this(10, 4000); // 默认 10 条或 4k Token
    }

    @Override
    public void onObservation(ReActTrace trace, String result) {
        List<ChatMessage> messages = trace.getMessages();
        if (messages.size() < 4) return;

        // 1. 检查是否达到压缩阈值（增加 2 条的缓冲，避免频繁触发）
        int currentTokens = estimateTokens(messages);
        if (messages.size() <= maxMessages + 2 && currentTokens <= maxTokens) {
            return;
        }

        // 2. 寻找任务锚点
        ChatMessage firstUserMsg = null;
        for (ChatMessage m : messages) {
            if (m instanceof UserMessage) {
                firstUserMsg = m;
                break;
            }
        }

        // 3. 确定初步窗口起点（保留最近的 N 条）
        int startIdx = Math.max(0, messages.size() - maxMessages);

        // 4. [关键加固] 逻辑对齐：处理 Tool 调用链
        // 必须确保 ToolMessage(Observation) 之前一定跟着 AssistantMessage(Action)
        while (startIdx > 0) {
            ChatMessage msg = messages.get(startIdx);
            if (msg instanceof ToolMessage) {
                startIdx--; // 必须向前找 Action
            } else if (msg instanceof AssistantMessage && Assert.isNotEmpty(((AssistantMessage) msg).getToolCalls())) {
                break; // 找到了完整的 Action-Tool 起点
            } else {
                // Thought 或 User 消息，允许作为起点
                break;
            }
        }

        // 5. 再次校验 Token 预算，如果对齐后还是超标，则强制进一步截断（从下一组 Action 开始）
        // 此处可根据实际需求微调

        // 6. 执行重组
        List<ChatMessage> compressed = new ArrayList<>();
        if (firstUserMsg != null) {
            compressed.add(firstUserMsg);
        }

        // 记录丢弃数量（不含 System 和 第一条 User）
        int dropCount = startIdx;
        if (firstUserMsg != null && messages.indexOf(firstUserMsg) < startIdx) {
            dropCount--;
        }

        if (dropCount > 0) {
            compressed.add(ChatMessage.ofSystem(TRIM_MARKER + " (Dropped " + dropCount + " messages for context optimization)"));
        }

        compressed.addAll(messages.subList(startIdx, messages.size()));

        trace.replaceMessages(compressed);
    }

    /**
     * 粗略估算消息列表的 Token 数
     */
    private int estimateTokens(List<ChatMessage> messages) {
        return messages.stream().mapToInt(this::estimateTokens).sum();
    }

    /**
     * 单条消息 Token 估算逻辑
     */
    private int estimateTokens(ChatMessage msg) {
        if (msg == null || msg.getContent() == null) return 0;
        String content = msg.getContent();
        // 估算公式：(中文数 * 1.5) + (非中文单词数 * 1.3)
        // 简单工程做法：字符数 / 1.5 (针对中英混合场景的保守估计)
        int length = content.length();
        if (msg instanceof AssistantMessage) {
            // 如果有工具调用，额外增加固定开销
            List<?> toolCalls = ((AssistantMessage) msg).getToolCalls();
            if (Assert.isNotEmpty(toolCalls)) {
                length += toolCalls.size() * 100;
            }
        }
        return (int) (length / 1.5);
    }
}