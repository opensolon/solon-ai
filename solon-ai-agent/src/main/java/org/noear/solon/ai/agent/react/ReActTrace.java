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
package org.noear.solon.ai.agent.react;

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Preview;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;

/**
 * ReAct 运行记录（承载记忆、状态与推理轨迹）
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public class ReActTrace {
    private transient ReActConfig config;
    private Prompt prompt;

    private volatile List<ChatMessage> messages;
    private AtomicInteger stepCounter;
    private volatile String route;

    private String finalAnswer;
    private String lastResponse;
    private final ReActMetrics metrics = new ReActMetrics();

    public ReActTrace() {
        //用于反序列化
        this.stepCounter = new AtomicInteger(0);
        this.messages = new ArrayList<>();
        this.route = Agent.ID_REASON;
    }

    public ReActTrace(ReActConfig config, Prompt prompt) {
        this();
        this.config = config; //测试时，可能为 null
        this.prompt = prompt;
    }


    // --- Getters & Setters ---


    public ReActConfig getConfig() {
        return config;
    }

    protected void setConfig(ReActConfig config) {
        this.config = config;
    }

    public Prompt getPrompt() {
        return prompt;
    }

    protected void setPrompt(Prompt prompt) {
        this.prompt = prompt;
    }

    public ReActMetrics getMetrics() {
        return metrics;
    }

    public int getStepCount() {
        return stepCounter.get();
    }

    public int nextStep() {
        return stepCounter.incrementAndGet();
    }

    public String getRoute() {
        return route;
    }

    public void setRoute(String route) {
        this.route = route;
    }

    public String getFinalAnswer() {
        return finalAnswer;
    }

    public void setFinalAnswer(String finalAnswer) {
        this.finalAnswer = finalAnswer;
    }

    public String getLastResponse() {
        return lastResponse;
    }

    public void setLastResponse(String lastResponse) {
        this.lastResponse = lastResponse;
    }

    public synchronized List<ChatMessage> getMessages() {
        return new ArrayList<>(messages); // 返回副本保证安全
    }

    public synchronized ChatMessage getLastMessage() {
        if (messages.isEmpty()) {
            return null;
        }

        return messages.get(messages.size() - 1);
    }


    public synchronized void appendMessage(ChatMessage message) {
        if (message == null) {
            return;
        }

        messages.add(message);

        //动态压缩
        int maxHistory = (config != null) ? config.getMaxSteps() * 3 : 20;
        if (messages.size() > maxHistory) {
            compact();
        }
    }

    public synchronized void appendMessage(Prompt prompt) {
        if (prompt == null) {
            return;
        }

        for (ChatMessage m1 : prompt.getMessages()) {
            appendMessage(m1);
        }
    }

    // 在 ReActTrace 类中添加
    public String getFormattedHistory() {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage msg : messages) {
            if (msg instanceof UserMessage) {
                sb.append("[User] ").append(((UserMessage) msg).getContent()).append("\n");
            } else if (msg instanceof AssistantMessage) {
                AssistantMessage am = (AssistantMessage) msg;
                String content = am.getContent();
                if (Assert.isNotEmpty(content)) {
                    // 简单显示，不需要复杂提取
                    sb.append("[Assistant] ").append(content).append("\n");
                }
                if (Assert.isNotEmpty(am.getToolCalls())) {
                    for (ToolCall call : am.getToolCalls()) {
                        sb.append("[Action] ").append(call.name()).append(": ").append(call.arguments()).append("\n");
                    }
                }
            } else if (msg instanceof ToolMessage) {
                sb.append("[Observation] ").append(((ToolMessage) msg).getContent()).append("\n");
            }
        }
        return sb.toString();
    }

    public int getToolCallCount(){
        int count = 0;
        for (ChatMessage msg : messages) {
            if (msg instanceof AssistantMessage) {
                AssistantMessage am = (AssistantMessage) msg;
                if (Assert.isNotEmpty(am.getToolCalls())) {
                    count += am.getToolCalls().size();
                }
            }
        }
        return count;
    }

    private void compact() {
        int totalSize = messages.size();
        if (totalSize <= 12) return;

        List<ChatMessage> compressed = new ArrayList<>();

        // 1. 保留第一条用户消息（同方案a）
        for (ChatMessage msg : messages) {
            if (msg instanceof UserMessage) {
                compressed.add(msg);
                break;
            }
        }

        // 2. 计算保留数量（缓存变量，提高性能）
        int keepCount = Math.min(10, totalSize);
        int startIdx = totalSize - keepCount;

        // 3. 向前查找，确保不切断工具链（边界检查严谨）
        while (startIdx > 0 && isToolMessage(messages.get(startIdx))) {
            startIdx--;
        }

        // 4. 获取子列表
        List<ChatMessage> recent = messages.subList(startIdx, totalSize);

        // 5. 计算实际被压缩的消息数（考虑第一条用户消息）
        int compressedCount = startIdx;
        // 如果第一条用户消息在保留部分中，需要调整计数
        if (!compressed.isEmpty() && messages.indexOf(compressed.get(0)) < startIdx) {
            compressedCount--; // 第一条用户消息不算在压缩数量中
        }

        // 6. 添加压缩提示
        if (compressedCount > 0) {
            compressed.add(ChatMessage.ofSystem(
                    String.format("[Historical context trimmed: %d messages]", compressedCount)));
        }

        // 7. 合并
        compressed.addAll(recent);
        messages = compressed;
    }

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