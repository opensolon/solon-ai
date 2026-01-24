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
 * 智能上下文压缩拦截器 (Context Compressor)
 * <p>通过滑动窗口机制截断历史消息，同时保护任务锚点并确保 Action-Observation 逻辑对齐。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class SummarizationInterceptor implements ReActInterceptor {
    private static final Logger log = LoggerFactory.getLogger(SummarizationInterceptor.class);

    /** 窗口保留的最大消息数 */
    private final int maxMessages;
    /** Token 预算阈值 */
    private final int maxTokens;
    /** 截断标识符 */
    private static final String TRIM_MARKER = "[Historical context trimmed]";

    /**
     * @param maxMessages 保留条数阈值 (建议 >= 10)
     * @param maxTokens   Token 预算阈值 (建议 >= 4000)
     */
    public SummarizationInterceptor(int maxMessages, int maxTokens) {
        this.maxMessages = Math.max(4, maxMessages);
        this.maxTokens = Math.max(1000, maxTokens);
    }

    public SummarizationInterceptor() {
        this(10, 4000);
    }

    @Override
    public void onObservation(ReActTrace trace, String result) {
        if (trace.getWorkingMemory().size() < 4) return;

        // 1. 触发检查：缓冲 2 条消息，避免频繁重组
        List<ChatMessage> messages = trace.getWorkingMemory().getMessages();
        int currentTokens = estimateTokens(messages);
        if (messages.size() <= maxMessages + 2 && currentTokens <= maxTokens) {
            return;
        }

        // 2. 识别任务锚点：首条 User 消息通常包含原始任务定义
        ChatMessage firstUserMsg = null;
        for (ChatMessage m : messages) {
            if (m instanceof UserMessage) {
                firstUserMsg = m;
                break;
            }
        }

        // 3. 确定逻辑对齐截断点
        int startIdx = Math.max(0, messages.size() - maxMessages);

        // 关键逻辑：防止在 Action 和 Observation 之间断开，必须确保它们成对被保留或丢弃
        while (startIdx > 0) {
            ChatMessage msg = messages.get(startIdx);
            if (msg instanceof ToolMessage) {
                startIdx--; // 当前是结果，继续向前寻找动作
            } else if (msg instanceof AssistantMessage && Assert.isNotEmpty(((AssistantMessage) msg).getToolCalls())) {
                break; // 找到了完整的 Action-Observation 对的起点
            } else {
                break; // 正常的 Thought 或 User 消息
            }
        }

        // 4. 重组上下文
        List<ChatMessage> compressed = new ArrayList<>();

        // 保留全局指令 (System) 和原始任务 (First User)
        messages.stream().filter(m -> m instanceof SystemMessage).forEach(compressed::add);
        if (firstUserMsg != null && !(firstUserMsg instanceof SystemMessage)) {
            compressed.add(firstUserMsg);
        }

        int dropCount = startIdx;
        if (firstUserMsg != null && messages.indexOf(firstUserMsg) < startIdx) {
            dropCount--;
        }

        // 5. 应用压缩
        if (dropCount > 0) {
            if (log.isDebugEnabled()) {
                log.debug("ReActAgent [{}] context compressed: dropped {} messages", trace.getAgentName(), dropCount);
            }
            compressed.add(ChatMessage.ofSystem(TRIM_MARKER + " (Dropped " + dropCount + " messages for context optimization)"));
        }

        compressed.addAll(messages.subList(startIdx, messages.size()));
        trace.getWorkingMemory().replaceMessages(compressed);
    }

    /**
     * 粗略估算 Token (中英混排按 1.5 字符/Token 估算)
     */
    private int estimateTokens(List<ChatMessage> messages) {
        return messages.stream().mapToInt(this::estimateTokens).sum();
    }

    private int estimateTokens(ChatMessage msg) {
        if (msg == null || msg.getContent() == null) return 0;
        String content = msg.getContent();

        int length = content.length();
        if (msg instanceof AssistantMessage) {
            List<?> toolCalls = ((AssistantMessage) msg).getToolCalls();
            if (Assert.isNotEmpty(toolCalls)) {
                length += toolCalls.size() * 100; // 补偿 JSON 元数据开销
            }
        }
        return (int) (length / 1.5);
    }
}