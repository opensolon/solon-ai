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
public class ReActRecord {
    public static final String ROUTE_REASON = "reason";
    public static final String ROUTE_ACTION = "action";
    public static final String ROUTE_END = "end";

    private String prompt;
    private AtomicInteger iteration;
    private List<ChatMessage> history;
    private volatile String route;
    private String finalAnswer;
    private String lastResponse;
    private NodeTrace lastNode;

    public ReActRecord() {
        this.iteration = new AtomicInteger(0);
        this.history = new ArrayList<>();
        this.route = "";
    }

    public ReActRecord(String prompt) {
        this();
        this.prompt = prompt;
    }


    // --- Getters & Setters ---

    public String getPrompt() {
        return prompt;
    }

    public AtomicInteger getIteration() {
        return iteration;
    }

    public int nextIteration() {
        return iteration.incrementAndGet();
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

    public synchronized List<ChatMessage> getHistory() {
        return new ArrayList<>(history); // 返回副本保证安全
    }

    public synchronized ChatMessage getLastMessage() {
        if (history.isEmpty()) {
            return null;
        }

        return history.get(history.size() - 1);
    }


    public synchronized void addMessage(ChatMessage message) {
        if (message == null) return;
        history.add(message);

        if (history.size() > 20) {
            compressHistory();
        }
    }

    /**
     * 压缩历史消息
     * */
    private void compressHistory() {
        if (history.size() <= 10) return;

        // 1. 尝试提取原始问题
        ChatMessage rootUserMessage = history.stream()
                .filter(m -> m instanceof UserMessage)
                .findFirst()
                .orElse(null);

        // 2. 确定截断点（保留最近的 8 条记录）
        int keepCount = 8;
        int cutIndex = history.size() - keepCount;

        // 3. 安全回溯：如果截断点正好在 ToolMessage 上，必须向前挪动
        // 理由：ToolMessage 必须紧跟在拥有 ToolCalls 的 AssistantMessage 之后
        while (cutIndex > 0 && isDanglingToolMessage(cutIndex)) {
            cutIndex--;
        }

        // 4. 获取活跃上下文
        List<ChatMessage> activeContext = new ArrayList<>(history.subList(cutIndex, history.size()));

        // 5. 重建历史
        history.clear();

        // 5a. 重新放入初始问题
        if (rootUserMessage != null) {
            history.add(rootUserMessage);
        }

        // 5b. 放入压缩提示（让模型知道中间有断层）
        history.add(ChatMessage.ofSystem("[System Note: Earlier reasoning steps summarized to save context window.]"));

        // 5c. 还原活跃推理链
        history.addAll(activeContext);
    }


    /**
     * 判断是否是一个“孤立”的工具消息（即切断了它与前置 Assistant Call 的联系）
     */
    private boolean isDanglingToolMessage(int index) {
        ChatMessage current = history.get(index);
        if (current instanceof ToolMessage) {
            return true;
        }

        // 如果当前是 Assistant 且包含 ToolCalls，通常建议把它和后面的结果一起保留
        if (current instanceof AssistantMessage) {
            AssistantMessage am = (AssistantMessage) current;
            return Assert.isNotEmpty(am.getToolCalls());
        }

        return false;
    }
}