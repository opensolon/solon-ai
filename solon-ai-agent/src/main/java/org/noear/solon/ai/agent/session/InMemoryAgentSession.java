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
package org.noear.solon.ai.agent.session;

import org.noear.solon.Utils;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.SystemMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存型智能体会话 (In-Memory Session)
 * * <p>管理单次协作的生命周期数据：
 * 1. 隔离的短期记忆：按 Agent 维度存储历史消息。
 * 2. 状态快照：承载业务流变量上下文。</p>
 */
@Preview("3.8.1")
public class InMemoryAgentSession implements AgentSession {
    private static final Logger log = LoggerFactory.getLogger(InMemoryAgentSession.class);

    public static AgentSession of() { return new InMemoryAgentSession("tmp"); }
    public static AgentSession of(String instanceId) { return new InMemoryAgentSession(instanceId); }
    public static AgentSession of(String instanceId, int maxAgentMessages) {
        return new InMemoryAgentSession(instanceId, maxAgentMessages);
    }
    public static AgentSession of(FlowContext context) { return new InMemoryAgentSession(context, 0); }

    private final String sessionId;
    /** 智能体隔离的历史消息存储 (Key: AgentName) */
    private final Map<String, List<ChatMessage>> historyMessages = new ConcurrentHashMap<>();
    /** 运行快照 */
    private volatile FlowContext snapshot;
    /** 最大消息留存数（0 表示无限制） */
    private final int maxAgentMessages;

    public InMemoryAgentSession(String sessionId) { this(sessionId, 0); }

    public InMemoryAgentSession(String sessionId, int maxAgentMessages) {
        this.sessionId = sessionId;
        this.maxAgentMessages = maxAgentMessages;
        this.snapshot = FlowContext.of(sessionId);
        this.snapshot.put(Agent.KEY_SESSION, this);
    }

    public InMemoryAgentSession(FlowContext context, int maxAgentMessages) {
        this.sessionId = context.getInstanceId();
        this.maxAgentMessages = maxAgentMessages;
        this.snapshot = context;
        this.snapshot.put(Agent.KEY_SESSION, this);
    }

    @Override
    public String getSessionId() { return sessionId; }

    @Override
    public void addHistoryMessage(String agentName, ChatMessage message) {
        // 系统消息由模板生成，不进入历史归档
        if (message instanceof SystemMessage) return;

        List<ChatMessage> agentMessages = historyMessages.computeIfAbsent(agentName, k -> new ArrayList<>());
        agentMessages.add(message);

        // 窗口滚动逻辑：移除超出容量的消息
        if (maxAgentMessages > 0 && agentMessages.size() > maxAgentMessages) {
            int overflowCount = agentMessages.size() - maxAgentMessages;
            if (log.isDebugEnabled()) {
                log.debug("Session [{}]: Agent [{}] history overflow, removing {} steps.", sessionId, agentName, overflowCount);
            }
            removeMessages(agentMessages, overflowCount);
        }
    }

    /**
     * 滑动窗口删除：支持工具调用链的一致性清理
     */
    private void removeMessages(List<ChatMessage> messages, int size) {
        Iterator<ChatMessage> iterator = messages.iterator();
        int removed = 0;

        while (iterator.hasNext() && removed < size) {
            ChatMessage message = iterator.next();
            iterator.remove();
            removed++;

            // 联动清理：若删除的是 AssistantToolCall，必须同时清理其后续的 ToolResponse
            if (message instanceof AssistantMessage) {
                List<ToolCall> toolCalls = ((AssistantMessage) message).getToolCalls();
                if (Utils.isNotEmpty(toolCalls)) {
                    while (iterator.hasNext() && iterator.next() instanceof ToolMessage) {
                        iterator.remove();
                        // 联动清理不计入 removed，确保窗口内保留完整的逻辑轮次
                    }
                }
            }
        }
    }

    @Override
    public Collection<ChatMessage> getHistoryMessages(String agentName, int last) {
        List<ChatMessage> list = historyMessages.get(agentName);
        if (list != null) {
            int size = list.size();
            return (size > last) ? list.subList(size - last, size) : list;
        }
        return Collections.emptyList();
    }

    @Override
    public void updateSnapshot(FlowContext snapshot) { this.snapshot = snapshot; }

    @Override
    public FlowContext getSnapshot() { return snapshot; }
}