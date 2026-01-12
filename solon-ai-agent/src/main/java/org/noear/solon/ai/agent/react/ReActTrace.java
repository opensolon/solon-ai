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
import org.noear.solon.ai.agent.AgentTrace;
import org.noear.solon.ai.agent.team.TeamProtocol;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.core.util.Assert;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ReAct 运行轨迹记录器 (状态机上下文)
 * <p>负责维护智能体推理过程中的短期记忆、执行路由、消息序列及上下文压缩。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class ReActTrace implements AgentTrace {
    private static final Logger log = LoggerFactory.getLogger(ReActTrace.class);

    /** 运行配置 */
    private transient ReActAgentConfig config;
    /** 运行选项 */
    private transient ReActOptions options;
    /** Agent 会话上下文 */
    private transient AgentSession session;
    /** 协作协议 (如 Team 模式) */
    private transient TeamProtocol protocol;
    /** 协议注入的专用工具映射表 */
    private transient final Map<String, FunctionTool> protocolToolMap = new LinkedHashMap<>();

    /** 智能体名称 */
    private String agentName;
    /** 任务提示词 */
    private Prompt prompt;
    /** 迭代步数计数器 */
    private AtomicInteger stepCounter;
    /** 消息历史序列 (Thought, Action, Observation) */
    private final List<ChatMessage> messages = new CopyOnWriteArrayList<>();
    /** 逻辑路由标识 (REASON, ACTION, END) */
    private volatile String route;
    /** 最终回答内容 (Final Answer) */
    private volatile String finalAnswer;
    /** 模型最近一次原始回答 */
    private volatile String lastAnswer;
    /** 度量指标 */
    private final ReActMetrics metrics = new ReActMetrics();

    public ReActTrace() {
        this.stepCounter = new AtomicInteger(0);
        this.route = Agent.ID_REASON;
    }

    public ReActTrace(Prompt prompt) {
        this();
        this.prompt = prompt;
    }

    // --- 生命周期与状态管理 ---

    /**
     * 准备执行环境
     */
    protected void prepare(ReActAgentConfig config, ReActOptions options, AgentSession session, String agentName, TeamProtocol protocol) {
        this.config = config;
        this.options = options;
        this.session = session;
        this.agentName = agentName;
        this.protocol = protocol;
    }

    public ReActAgentConfig getConfig() {
        return config;
    }

    public ReActOptions getOptions() {
        return options;
    }

    public AgentSession getSession() {
        return session;
    }

    /**
     * 获取流程快照快照
     */
    public FlowContext getContext() {
        return (session != null) ? session.getSnapshot() : null;
    }

    public TeamProtocol getProtocol() {
        return protocol;
    }

    /**
     * 注册协议内置工具
     */
    public void addProtocolTool(FunctionTool tool) {
        protocolToolMap.put(tool.name(), tool);
    }

    public FunctionTool getProtocolTool(String name) {
        return protocolToolMap.get(name);
    }

    public Collection<FunctionTool> getProtocolTools() {
        return protocolToolMap.values();
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

    public int getStepCount() {
        return stepCounter.get();
    }

    /**
     * 递增步数
     */
    public int nextStep() {
        int step = stepCounter.incrementAndGet();
        if (log.isDebugEnabled()) {
            log.debug("Agent [{}] proceed to step: {}", agentName, step);
        }
        return step;
    }

    public String getRoute() {
        return route;
    }

    /**
     * 更新路由状态
     */
    public void setRoute(String route) {
        if (log.isTraceEnabled()) {
            log.trace("Agent [{}] route changed: {} -> {}", agentName, this.route, route);
        }
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

    public int getMessagesSize() {
        return messages.size();
    }

    /**
     * 获取只读历史消息快照
     */
    public List<ChatMessage> getMessages() {
        return Collections.unmodifiableList(messages);
    }

    /**
     * 获取末尾消息
     */
    public ChatMessage getLastMessage() {
        return messages.isEmpty() ? null : messages.get(messages.size() - 1);
    }

    /**
     * 回溯查找最近的 Assistant 消息
     */
    public AssistantMessage getLastAssistantMessage() {
        List<ChatMessage> currentMessages = this.messages;
        for (int i = currentMessages.size() - 1; i >= 0; i--) {
            try {
                ChatMessage msg = currentMessages.get(i);
                if (msg instanceof AssistantMessage) {
                    return (AssistantMessage) msg;
                }
            } catch (IndexOutOfBoundsException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 记录新消息到轨迹
     */
    public void appendMessage(ChatMessage message) {
        if (message != null) {
            messages.add(message);
        }
    }

    public void appendMessages(Collection<ChatMessage> newMessages) {
        if (Assert.isNotEmpty(newMessages)) {
            messages.addAll(newMessages);
        }
    }

    /**
     * 替换历史消息 (用于上下文压缩或清洗)
     */
    public void replaceMessages(List<ChatMessage> newMessages) {
        if (newMessages == null) {
            throw new IllegalArgumentException("messages cannot be null");
        }
        if (log.isDebugEnabled()) {
            log.debug("Agent [{}] messages replaced, size: {} -> {}", agentName, messages.size(), newMessages.size());
        }
        this.messages.clear();
        this.messages.addAll(newMessages);
    }

    /**
     * 获取人性化历史记录格式
     */
    public String getFormattedHistory() {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage msg : messages) {
            if (msg instanceof UserMessage) {
                sb.append("[User] ").append(msg.getContent()).append("\n");
            } else if (msg instanceof AssistantMessage) {
                AssistantMessage am = (AssistantMessage) msg;
                if (Assert.isNotEmpty(am.getContent())) {
                    sb.append("[Assistant] ").append(am.getContent()).append("\n");
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
     * 获取已触发的工具调用总数
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