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
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Preview;

import java.util.ArrayList;
import java.util.List;

/**
 * 消息压缩拦截器
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public class SummarizationInterceptor implements ReActInterceptor {
    private final int messagesToKeep;

    public SummarizationInterceptor(int messagesToKeep) {
        this.messagesToKeep = Math.min(10, messagesToKeep);
    }

    /**
     * 上下文压缩核心逻辑
     * * 策略：
     * 1. 强力保留首条 User 消息（确保原始任务目标不丢失）。
     * 2. 滑动窗口保留最近的交互记录（默认 10 条左右）。
     * 3. 边界对齐：确保不从 Observation 开始截断，维护 Assistant-Action-Observation 的完整逻辑链。
     */
    @Override
    public void onObservation(ReActTrace trace, String result) {
        List<ChatMessage> messages = trace.getMessages();
        int totalSize = messages.size();
        if (totalSize <= 12) return;

        List<ChatMessage> compressed = new ArrayList<>();

        // 1. 保留任务源头：第一条用户消息
        for (ChatMessage msg : messages) {
            if (msg instanceof UserMessage) {
                compressed.add(msg);
                break;
            }
        }

        // 2. 确定滑动窗口边界
        int keepCount = Math.min(messagesToKeep, totalSize);
        int startIdx = totalSize - keepCount;

        // 3. 逻辑对齐：若起点是工具反馈，则需包含其对应的 Action 指令，防止模型因上下文缺失产生解析异常
        while (startIdx > 0 && isToolMessage(messages.get(startIdx))) {
            startIdx--;
        }

        // 4. 获取活跃窗口数据
        List<ChatMessage> recent = messages.subList(startIdx, totalSize);

        // 5. 计算修剪深度，并向模型注入系统级提示以告知上下文已被压缩
        int compressedCount = startIdx;
        if (!compressed.isEmpty() && messages.indexOf(compressed.get(0)) < startIdx) {
            compressedCount--;
        }

        if (compressedCount > 0) {
            compressed.add(ChatMessage.ofSystem(
                    String.format("[Historical context trimmed: %d messages]", compressedCount)));
        }

        // 6. 重组消息序列
        compressed.addAll(recent);
        trace.replaceMessages(compressed);
    }

    /**
     * 校验消息是否属于工具执行相关的关键链路节点
     */
    private boolean isToolMessage(ChatMessage msg) {
        if (msg instanceof ToolMessage) {
            return true;
        }
        if (msg instanceof AssistantMessage) {
            return Assert.isNotEmpty(((AssistantMessage) msg).getToolCalls());
        }
        return false;
    }
}
