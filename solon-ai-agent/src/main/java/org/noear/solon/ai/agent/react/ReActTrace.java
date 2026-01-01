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
import org.noear.solon.flow.Node;
import org.noear.solon.flow.NodeTrace;
import org.noear.solon.lang.Preview;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ReAct 运行记录（承载记忆、状态与推理轨迹）
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public class ReActTrace {
    private Prompt prompt;
    private volatile List<ChatMessage> messages;

    private AtomicInteger stepCounter;
    private volatile String route;

    private String finalAnswer;
    private String lastResponse;
    private NodeTrace lastNode;
    private final ReActMetrics metrics = new ReActMetrics();

    public ReActTrace() {
        this.stepCounter = new AtomicInteger(0);
        this.messages = new ArrayList<>();
        this.route = Agent.ID_REASON;
    }

    public ReActTrace(Prompt prompt) {
        this();
        this.prompt = prompt;
    }


    // --- Getters & Setters ---


    public ReActMetrics getMetrics() {
        return metrics;
    }

    public Prompt getPrompt() {
        return prompt;
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

    public NodeTrace getLastNode() {
        return lastNode;
    }

    public String getLastNodeId() {
        if (lastNode == null) {
            return null;
        }

        return lastNode.getId();
    }

    public void setLastNode(NodeTrace lastNode) {
        this.lastNode = lastNode;
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
        if (message == null) return;
        messages.add(message);

        if (messages.size() > 20) {
            compact();
        }
    }

    public synchronized void appendMessage(Prompt prompt) {
        if (prompt == null) return;
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
        if (messages.size() <= 12) return; // 提高阈值

        List<ChatMessage> compressed = new ArrayList<>();

        // 1. 保留第一条用户消息
        ChatMessage firstUser = messages.stream()
                .filter(m -> m instanceof UserMessage)
                .findFirst()
                .orElse(null);
        if (firstUser != null) {
            compressed.add(firstUser);
        }

        // 2. 保留最后10条消息（确保工具调用链完整）
        int keepCount = Math.min(10, messages.size());
        int startIdx = messages.size() - keepCount;

        // 3. 确保不会切断工具调用链
        while (startIdx > 0 && isPartOfToolChain(startIdx)) {
            startIdx--;
        }

        List<ChatMessage> recent = messages.subList(startIdx, messages.size());
        compressed.add(ChatMessage.ofSystem("[Context trimmed: " + (messages.size() - recent.size()) + " messages compressed]"));
        compressed.addAll(recent);

        messages = compressed;
    }

    private boolean isPartOfToolChain(int index) {
        if (index >= messages.size()) return false;

        ChatMessage current = messages.get(index);

        if (current instanceof ToolMessage) {
            return true;
        }

        if (current instanceof AssistantMessage) {
            return Assert.isNotEmpty(((AssistantMessage) current).getToolCalls());
        }
        return false;
    }
}