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

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ReAct 运行轨迹记录器
 * <p>负责承载智能体推理过程中的短期记忆、执行状态机、逻辑路由以及消息序列。</p>
 * <p>核心机制包含动态上下文压缩（Compact），确保在多轮 ReAct 循环中不触发模型上下文上限。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class ReActTrace implements AgentTrace {
    /**
     * 当前运行配置
     */
    private transient ReActConfig config;
    /**
     * 当前运行选项
     */
    private transient ReActOptions options;
    /**
     * 当前 Agent 会话上下文
     */
    private transient AgentSession session;
    /**
     * 当前执行的协作协议
     */
    private transient TeamProtocol protocol;
    /**
     * 协议注入的专用工具映射表（如 __transfer_to__）
     */
    private transient final Map<String, FunctionTool> protocolToolMap = new LinkedHashMap<>();

    /**
     * 当前执行的智能体名称
     */
    private String agentName;
    /**
     * 当前迭代的提示词模版与选项
     */
    private Prompt prompt;
    /**
     * 迭代步数计数器（用于防止死循环和控制 Token 消耗）
     */
    private AtomicInteger stepCounter;
    /**
     * 消息历史序列（包含 Thought, Action, Observation）
     */
    private final List<ChatMessage> messages = new CopyOnWriteArrayList<>();
    /**
     * 逻辑路由标识（决定下一节点是 REASON, ACTION 还是 END）
     */
    private volatile String route;
    /**
     * 最终生成的回答内容（即最终输出给用户的 Final Answer）
     */
    private volatile String finalAnswer;
    /**
     * 模型最近一次原始回答内容（用于解析 Action 信号）
     */
    private volatile String lastAnswer;
    /**
     * 性能与资源度量指标
     */
    private final ReActMetrics metrics = new ReActMetrics();

    public ReActTrace() {
        // 无参构造：主要用于流程持久化中的反序列化恢复
        this.stepCounter = new AtomicInteger(0);
        this.route = Agent.ID_REASON;
    }

    public ReActTrace(Prompt prompt) {
        this();
        this.prompt = prompt;
    }


    // --- 状态访问与生命周期管理 ---


    /**
     * 准备执行环境（由运行时注入上下文）
     */
    protected void prepare(ReActConfig config, ReActOptions options, AgentSession session, String agentName, TeamProtocol protocol) {
        this.config = config;
        this.options = options;
        this.session = session;
        this.agentName = agentName;
        this.protocol = protocol;
    }

    /**
     * 获取运行配置
     */
    public ReActConfig getConfig() {
        return config;
    }

    /**
     * 获取运行选项
     */
    public ReActOptions getOptions() {
        return options;
    }

    /**
     * 获取会话容器
     */
    public AgentSession getSession() {
        return session;
    }

    /**
     * 获取流程上下文
     */
    public FlowContext getContext() {
        if (session != null) {
            return session.getSnapshot();
        } else {
            return null;
        }
    }

    /**
     * 获取当前协作协议
     */
    public TeamProtocol getProtocol() {
        return protocol;
    }

    /**
     * 添加协议级内置工具（由 Protocol 注入）
     */
    public void addProtocolTool(FunctionTool tool) {
        protocolToolMap.put(tool.name(), tool);
    }

    /**
     * 根据名称获取协议工具
     */
    public FunctionTool getProtocolTool(String name) {
        return protocolToolMap.get(name);
    }

    /**
     * 获取所有已注入的协议工具
     */
    public Collection<FunctionTool> getProtocolTools() {
        return protocolToolMap.values();
    }

    /**
     * 获取智能体名称
     */
    public String getAgentName() {
        return agentName;
    }

    /**
     * 获取任务提示词
     */
    public Prompt getPrompt() {
        return prompt;
    }

    /**
     * 设置任务提示词
     */
    protected void setPrompt(Prompt prompt) {
        this.prompt = prompt;
    }

    /**
     * 获取度量指标
     */
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

    /**
     * 获取当前流转路由
     */
    public String getRoute() {
        return route;
    }

    /**
     * 设置当前流转路由
     */
    public void setRoute(String route) {
        this.route = route;
    }

    /**
     * 获取最终回答内容
     */
    public String getFinalAnswer() {
        return finalAnswer;
    }

    /**
     * 设置最终回答内容
     */
    public void setFinalAnswer(String finalAnswer) {
        this.finalAnswer = finalAnswer;
    }

    /**
     * 获取最后一次模型响应内容
     */
    public String getLastAnswer() {
        return lastAnswer;
    }

    /**
     * 设置最后一次模型响应内容
     */
    public void setLastAnswer(String lastAnswer) {
        this.lastAnswer = lastAnswer;
    }

    /**
     * 获取消息序列的大小
     */
    public int getMessagesSize() {
        return messages.size();
    }

    /**
     * 获取消息序列的快照副本，确保外部读取线程安全
     */
    public List<ChatMessage> getMessages() {
        return Collections.unmodifiableList(messages);
    }

    /**
     * 获取最后一条交互消息（通常用于 Action 阶段解析工具调用）
     */
    public ChatMessage getLastMessage() {
        if (messages.isEmpty()) {
            return null;
        } else {
            return messages.get(messages.size() - 1);
        }
    }

    public AssistantMessage getLastAssistantMessage() {
        List<ChatMessage> currentMessages = this.messages;

        for (int i = currentMessages.size() - 1; i >= 0; i--) {
            try {
                ChatMessage msg = currentMessages.get(i);
                if (msg instanceof AssistantMessage) {
                    return (AssistantMessage) msg;
                }
            } catch (IndexOutOfBoundsException e) {
                // 防御 CopyOnWriteArrayList 在极短时间内被 replaceMessages 清空的情况
                return null;
            }
        }
        return null;
    }

    /**
     * 追加单条消息并进入历史轨迹
     */
    public void appendMessage(ChatMessage message) {
        if (message == null) {
            return;
        }

        messages.add(message);
    }

    /**
     * 批量添加消息
     */
    public void appendMessages(Collection<ChatMessage> newMessages) {
        if (Assert.isEmpty(newMessages)) {
            return;
        }

        messages.addAll(newMessages);
    }

    /**
     * 替换所有消息（通常用于触发压缩算法或上下文清洗时）
     */
    public void replaceMessages(List<ChatMessage> newMessages) {
        if (newMessages == null) {
            throw new IllegalArgumentException("messages cannot be null");
        }

        this.messages.clear();
        this.messages.addAll(newMessages);  // 防御性复制
    }

    /**
     * 获取格式化的交互历史
     * 用于多智能体协作或调试日志输出，按角色标记推理轨迹
     */
    public String getFormattedHistory() {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage msg : messages) {
            if (msg instanceof UserMessage) {
                sb.append("[User] ").append(msg.getContent()).append("\n");
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
     * 统计总工具调用次数（从推理轨迹中计算）
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