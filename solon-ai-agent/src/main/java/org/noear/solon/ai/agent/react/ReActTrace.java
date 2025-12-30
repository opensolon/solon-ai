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

import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.core.util.Assert;
import org.noear.solon.flow.NodeTrace;
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
    public static final String ROUTE_REASON = "reason";
    public static final String ROUTE_ACTION = "action";
    public static final String ROUTE_END = "end";

    private String prompt;
    private AtomicInteger stepCounter;
    private List<ChatMessage> messages;

    private volatile String route;
    private String finalAnswer;
    private String lastResponse;
    private NodeTrace lastNode;

    public ReActTrace() {
        this.stepCounter = new AtomicInteger(0);
        this.messages = new ArrayList<>();
        this.route = ROUTE_REASON;
    }

    public ReActTrace(String prompt) {
        this();
        this.prompt = prompt;
    }


    // --- Getters & Setters ---

    public String getPrompt() {
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

    /**
     * 压缩历史消息
     *
     */
    private void compact() {
        if (messages.size() <= 10) return;

        // 1. 寻找起点（初始问题）
        ChatMessage startPoint = messages.stream()
                .filter(m -> m instanceof UserMessage)
                .findFirst()
                .orElse(null);

        // 2. 确定滑动窗口起点（保留最近 8 条）
        int windowSize = 8;
        int startIndex = messages.size() - windowSize;

        // 3. 完整性校验：如果当前位置是孤立的工具结果，则必须向前回溯到其调用者
        while (startIndex > 0 && isDanglingPath(startIndex)) {
            startIndex--;
        }

        List<ChatMessage> activeTrace = new ArrayList<>(messages.subList(startIndex, messages.size()));

        // 4. 重建轨迹消息链
        messages.clear();
        if (startPoint != null) {
            messages.add(startPoint);
        }

        messages.add(ChatMessage.ofSystem("[System: Earlier trace steps compacted to optimize context.]"));
        messages.addAll(activeTrace);
    }


    /**
     * 判断是否是一个“孤立”的工具消息（即切断了它与前置 Assistant Call 的联系）
     */
    private boolean isDanglingPath(int index) {
        ChatMessage current = messages.get(index);

        // 如果当前是 ToolMessage，它不能作为足迹的开头
        if (current instanceof ToolMessage) return true;

        // 如果助手消息包含工具调用，通常建议把它和后面的结果一起保留
        if (current instanceof AssistantMessage) {
            AssistantMessage am = (AssistantMessage) current;
            return Assert.isNotEmpty(am.getToolCalls());
        }

        return false;
    }
}