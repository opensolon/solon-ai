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

import java.util.ArrayList;
import java.util.List;

/**
 * 智能上下文压缩拦截器 (Smart Context Compressor)
 * <p>该拦截器通过“滑动窗口”机制，在保证 ReAct 逻辑链完整性的前提下，对历史上下文进行截断压缩。</p>
 *
 * <p><b>核心策略：</b></p>
 * <ul>
 * <li>1. <b>任务锚点保护</b>：始终保留用户的第一条指令（UserMessage），确保模型不丢失原始任务目标。</li>
 * <li>2. <b>逻辑原子对齐</b>：防止在 Tool 调用和返回结果之间进行截断。必须确保 Observation 消息与其对应的 Action 消息“同生共死”。</li>
 * <li>3. <b>双重阈值触发</b>：同时支持基于消息条数（Messages Count）和估算 Token 数（Token Budget）的压缩触发。</li>
 * </ul>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class SummarizationInterceptor implements ReActInterceptor {
    /** 窗口保留的最大消息条数 */
    private final int maxMessages;
    /** 最大 Token 预算阈值 */
    private final int maxTokens;
    /** 截断标识符，用于告知模型历史上下文已优化 */
    private static final String TRIM_MARKER = "[Historical context trimmed]";

    /**
     * @param maxMessages 建议保留 10 条以上，以维持必要的上下文感知
     * @param maxTokens   建议根据模型窗口大小设定，如 4000
     */
    public SummarizationInterceptor(int maxMessages, int maxTokens) {
        this.maxMessages = Math.max(4, maxMessages);
        this.maxTokens = Math.max(1000, maxTokens);
    }

    /**
     * 默认构造函数：保留最近 10 条消息或 4000 Token
     */
    public SummarizationInterceptor() {
        this(10, 4000);
    }

    /**
     * 在工具观测（Observation）完成后触发压缩检查
     */
    @Override
    public void onObservation(ReActTrace trace, String result) {
        // 消息量极小时不执行压缩计算
        if (trace.getMessagesSize() < 4) return;

        // 1. 阈值检查：增加缓冲窗口（2条），避免每一步都执行重组计算
        List<ChatMessage> messages = trace.getMessages();
        int currentTokens = estimateTokens(messages);
        if (messages.size() <= maxMessages + 2 && currentTokens <= maxTokens) {
            return;
        }

        // 2. 寻找并保护首条 User 消息（任务锚点）
        ChatMessage firstUserMsg = null;
        for (ChatMessage m : messages) {
            if (m instanceof UserMessage) {
                firstUserMsg = m;
                break;
            }
        }

        // 3. 计算初步截断起点
        int startIdx = Math.max(0, messages.size() - maxMessages);

        // 4. [关键] 逻辑对齐处理：
        // 在 ReAct 协议中，AssistantMessage(Action) 和 ToolMessage(Observation) 必须成对出现。
        // 如果截断点恰好落在 ToolMessage 上，必须向前回溯，确保保留对应的 Action。
        while (startIdx > 0) {
            ChatMessage msg = messages.get(startIdx);
            if (msg instanceof ToolMessage) {
                // 当前是观测结果，必须继续向前寻找其触发动作
                startIdx--;
            } else if (msg instanceof AssistantMessage && Assert.isNotEmpty(((AssistantMessage) msg).getToolCalls())) {
                // 找到了完整的 Action-Observation 链条起点，停止回溯
                break;
            } else {
                // Thought 消息或普通 User 消息，可以作为安全的滑动窗口起点
                break;
            }
        }

        // 5. 执行消息重组
        List<ChatMessage> compressed = new ArrayList<>();

        // 始终保留 System 消息（如果存在）和首条 User 任务描述
        messages.stream().filter(m -> m instanceof SystemMessage).forEach(compressed::add);
        if (firstUserMsg != null && !(firstUserMsg instanceof SystemMessage)) {
            compressed.add(firstUserMsg);
        }

        // 计算实际丢弃的消息数量（用于生成提示标记）
        int dropCount = startIdx;
        if (firstUserMsg != null && messages.indexOf(firstUserMsg) < startIdx) {
            dropCount--;
        }

        // 注入截断标识，引导模型感知上下文变化
        if (dropCount > 0) {
            compressed.add(ChatMessage.ofSystem(TRIM_MARKER + " (Dropped " + dropCount + " messages for context optimization)"));
        }

        // 将窗口内的有效消息合并
        compressed.addAll(messages.subList(startIdx, messages.size()));

        // 更新轨迹中的消息列表
        trace.replaceMessages(compressed);
    }

    /**
     * 粗略估算 Token 总数（用于触发决策，非精确计费）
     */
    private int estimateTokens(List<ChatMessage> messages) {
        return messages.stream().mapToInt(this::estimateTokens).sum();
    }

    /**
     * 单条消息 Token 估算：
     * 采用保守的字符/Token 转换比，并对工具调用信息增加额外开销预估
     */
    private int estimateTokens(ChatMessage msg) {
        if (msg == null || msg.getContent() == null) return 0;
        String content = msg.getContent();

        int length = content.length();
        if (msg instanceof AssistantMessage) {
            List<?> toolCalls = ((AssistantMessage) msg).getToolCalls();
            if (Assert.isNotEmpty(toolCalls)) {
                // 工具调用元数据在 JSON 序列化后会产生额外长度
                length += toolCalls.size() * 100;
            }
        }
        // 工程估算模型：针对中英混合场景，约 1.5 字符对应 1 Token
        return (int) (length / 1.5);
    }
}