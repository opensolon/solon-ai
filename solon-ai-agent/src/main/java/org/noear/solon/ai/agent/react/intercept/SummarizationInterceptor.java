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
 * <p>确保 Action-Observation 原子对齐，并显式保留执行计划（Plans）以维持逻辑连贯性。</p>
 */
@Preview("3.8.1")
public class SummarizationInterceptor implements ReActInterceptor {
    private static final Logger log = LoggerFactory.getLogger(SummarizationInterceptor.class);

    private final int maxMessages;
    private static final String TRIM_MARKER = "[Historical context trimmed for optimization]";

    public SummarizationInterceptor(int maxMessages) {
        this.maxMessages = Math.max(6, maxMessages);
    }

    public SummarizationInterceptor() {
        this(12);
    }

    @Override
    public void onObservation(ReActTrace trace, String toolName, String result) {
        List<ChatMessage> messages = trace.getWorkingMemory().getMessages();
        if (messages.size() <= maxMessages + 2) return;

        // 1. 寻找核心锚点
        ChatMessage firstUserMsg = messages.stream().filter(m -> m instanceof UserMessage).findFirst().orElse(null);

        // 2. 确定截断起始点
        int targetIdx = messages.size() - maxMessages;

        // 3. 增强版原子对齐
        while (targetIdx > 0) {
            ChatMessage msg = messages.get(targetIdx);
            if (msg instanceof ToolMessage || isObservation(msg)) {
                targetIdx--; // 是结果，往前退
            } else if (msg instanceof AssistantMessage && Assert.isNotEmpty(((AssistantMessage) msg).getToolCalls())) {
                targetIdx--; // 是动作发起，也要往前退，防止切断在动作和结果中间
            } else {
                break; // 对齐到一个相对独立的 Thought 或 User 消息
            }
        }

        // 4. 重构 WorkingMemory
        List<ChatMessage> compressed = new ArrayList<>();

        // 策略 A: 保持系统角色
        messages.stream().filter(m -> m instanceof SystemMessage).forEach(compressed::add);

        // 策略 B: 保持原始任务定义
        if (firstUserMsg != null && !compressed.contains(firstUserMsg)) {
            compressed.add(firstUserMsg);
        }

        // 策略 D: 加入保留的滑动窗口消息
        compressed.addAll(messages.subList(targetIdx, messages.size()));

        if (log.isDebugEnabled()) {
            log.debug("ReActAgent [{}] summarized context: dropped {} messages, preserved plans: {}",
                    trace.getAgentName(), (messages.size() - compressed.size()), trace.hasPlans());
        }

        trace.getWorkingMemory().replaceMessages(compressed);
    }

    private boolean isObservation(ChatMessage msg) {
        if (msg instanceof ToolMessage) {
            return true;
        } else {
            return msg instanceof UserMessage && msg.getContent() != null && msg.getContent().startsWith("Observation:");
        }
    }
}