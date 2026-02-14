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
 */
@Preview("3.8.1")
public class SummarizationInterceptor implements ReActInterceptor {
    private static final Logger log = LoggerFactory.getLogger(SummarizationInterceptor.class);

    private final int maxMessages;
    // 优化点 1: 提供更具引导性的标记，告诉模型历史已被压缩
    private static final String TRIM_MARKER = "--- [Historical context trimmed for optimization. Refer to current execution plans for state.] ---";

    public SummarizationInterceptor(int maxMessages) {
        this.maxMessages = Math.max(6, maxMessages);
    }

    public SummarizationInterceptor() {
        this(12);
    }

    @Override
    public void onObservation(ReActTrace trace, String toolName, String result) {
        List<ChatMessage> messages = trace.getWorkingMemory().getMessages();
        // 预留缓冲位，防止频繁触发压缩
        if (messages.size() <= maxMessages + 2) return;

        // 1. 寻找核心锚点（定位首个 UserMessage 作为任务目标）
        ChatMessage firstUserMsg = null;
        int firstUserIdx = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof UserMessage) {
                firstUserMsg = messages.get(i);
                firstUserIdx = i;
                break;
            }
        }

        // 2. 确定初始截断起始点
        int targetIdx = messages.size() - maxMessages;

        // 3. 增强版原子对齐
        // 策略：如果截断点落在 Action 或 Observation 中间，则不断前移，确保工具调用链完整
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

        // 4. 语义补齐：确保历史以 Thought 开始，而非以“结果”硬生生开始
        // 优化点：如果前一条消息是纯 Thought（Assistant 且无工具调用），则将其包含进来
        if (targetIdx > firstUserIdx + 1) {
            ChatMessage prev = messages.get(targetIdx - 1);
            if (prev instanceof AssistantMessage && Assert.isEmpty(((AssistantMessage) prev).getToolCalls())) {
                targetIdx--;
            }
        }

        // 5. 重构 WorkingMemory
        List<ChatMessage> compressed = new ArrayList<>();

        // 策略 A: 保持全局系统角色（去重，只留第一条核心指令）
        messages.stream()
                .filter(m -> m instanceof SystemMessage)
                .findFirst()
                .ifPresent(compressed::add);

        // 策略 B: 保持原始任务定义
        if (firstUserMsg != null && !compressed.contains(firstUserMsg)) {
            compressed.add(firstUserMsg);
        }

        // 策略 C: 注入断裂感知标记
        if (targetIdx > (firstUserIdx + 1)) {
            compressed.add(ChatMessage.ofSystem(TRIM_MARKER));
        }

        // 策略 D: 加入保留的滑动窗口消息
        compressed.addAll(messages.subList(targetIdx, messages.size()));

        if (log.isDebugEnabled()) {
            log.debug("ReActAgent [{}] summarized context: preserved {}/{} messages, aligned at idx: {}",
                    trace.getAgentName(), compressed.size(), messages.size(), targetIdx);
        }

        // 6.更新工作区记忆
        trace.getWorkingMemory().replaceMessages(compressed);
    }

    private boolean isObservation(ChatMessage msg) {
        if (msg instanceof ToolMessage) {
            return true;
        } else {
            // 兼容非 NATIVE_TOOL 模式下的文本协议
            return msg instanceof UserMessage && msg.getContent() != null && msg.getContent().startsWith("Observation:");
        }
    }
}