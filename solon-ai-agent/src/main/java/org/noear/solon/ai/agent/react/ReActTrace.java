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
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.team.TeamProtocol;
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
 * ReAct 运行轨迹记录器
 * <p>负责承载智能体推理过程中的短期记忆、执行状态机、逻辑路由以及消息序列。</p>
 * <p>核心机制包含动态上下文压缩（Compact），确保在多轮 ReAct 循环中不触发模型上下文上限。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public class ReActTrace {
    private transient ReActConfig config;
    private transient AgentSession session;
    private transient TeamProtocol protocol;

    private String agentName;
    private Prompt prompt;

    /**
     * 消息历史序列（包含 Thought, Action, Observation）
     */
    private volatile List<ChatMessage> messages;
    /**
     * 迭代步数计数器
     */
    private AtomicInteger stepCounter;
    /**
     * 逻辑路由标识（决定下一节点是 REASON, ACTION 还是 END）
     */
    private volatile String route;

    /**
     * 最终生成的回答内容
     */
    private String finalAnswer;
    /**
     * 模型最近一次原始回答内容
     */
    private String lastAnswer;
    /**
     * 性能度量指标
     */
    private final ReActMetrics metrics = new ReActMetrics();

    public ReActTrace() {
        // 无参构造：主要用于流程持久化中的反序列化恢复
        this.stepCounter = new AtomicInteger(0);
        this.messages = new ArrayList<>();
        this.route = Agent.ID_REASON;
    }

    public ReActTrace(Prompt prompt) {
        this();
        this.prompt = prompt;
    }


    // --- 状态访问与生命周期管理 ---


    protected void prepare(ReActConfig config, AgentSession session, String agentName, TeamProtocol protocol) {
        this.config = config;
        this.session = session;
        this.agentName = agentName;
        this.protocol = protocol;
    }

    public ReActConfig getConfig() {
        return config;
    }


    public AgentSession getSession() {
        return session;
    }

    public TeamProtocol getProtocol() {
        return protocol;
    }

    public String getAgentName() {
        return agentName;
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

    /**
     * 获取当前已执行的迭代步数
     */
    public int getStepCount() {
        return stepCounter.get();
    }

    /**
     * 递增步数并返回新值（用于循环安全控制）
     */
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

    public String getLastAnswer() {
        return lastAnswer;
    }

    public void setLastAnswer(String lastAnswer) {
        this.lastAnswer = lastAnswer;
    }

    /**
     * 获取消息序列的快照副本，确保外部读取线程安全
     */
    public synchronized List<ChatMessage> getMessages() {
        return new ArrayList<>(messages);
    }

    /**
     * 获取最后一条交互消息（通常用于 Action 阶段解析指令）
     */
    public synchronized ChatMessage getLastMessage() {
        if (messages.isEmpty()) {
            return null;
        }

        return messages.get(messages.size() - 1);
    }

    /**
     * 追加单条消息并尝试触发动态上下文压缩
     */
    public synchronized void appendMessage(ChatMessage message) {
        if (message == null) {
            return;
        }

        messages.add(message);
    }

    /**
     * 替换所有消息（一般用于压缩时用）
     */
    public synchronized void replaceMessages(List<ChatMessage> messages) {
        this.messages = messages;
    }

    /**
     * 获取格式化的交互历史
     * 用于多智能体协作或调试日志输出，按角色标记推理轨迹
     */
    public String getFormattedHistory() {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage msg : messages) {
            if (msg instanceof UserMessage) {
                sb.append("[User] ").append(((UserMessage) msg).getContent()).append("\n");
            } else if (msg instanceof AssistantMessage) {
                AssistantMessage am = (AssistantMessage) msg;
                String content = am.getContent();
                if (Assert.isNotEmpty(content)) {
                    sb.append("[Assistant] ").append(content).append("\n");
                }
                if (Assert.isNotEmpty(am.getToolCalls())) {
                    for (ToolCall call : am.getToolCalls()) {
                        sb.append("[Action] ").append(call.name()).append(": ").append(call.arguments()).append("\n");
                    }
                }
            } else if (msg instanceof ToolMessage) {
                sb.append("[Observation] ").append(msg.getContent()).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 统计总工具调用次数
     */
    public int getToolCallCount() {
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
}