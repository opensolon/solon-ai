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

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.flow.FlowContext;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存智能体会话实现 (In-Memory Agent Session)
 *
 * <p>该类为智能体提供了一个易失性的（Volatile）存储容器，用于管理单次协作的生命周期数据：</p>
 * <ul>
 * <li><b>短期记忆 (Short-term Memory)</b>：以智能体为维度隔离存储的历史消息。</li>
 * <li><b>运行快照 (Flow Snapshot)</b>：承载 Solon Flow 状态机的变量与上下文。</li>
 * </ul>
 * *
 *
 * @author noear
 * @since 3.8.1
 */
public class InMemoryAgentSession implements AgentSession {

    /**
     * 创建默认会话（标识为 tmp）
     */
    public static AgentSession of() {
        return new InMemoryAgentSession("tmp");
    }

    /**
     * 创建指定实例 ID 的会话
     */
    public static AgentSession of(String instanceId) {
        return new InMemoryAgentSession(instanceId);
    }

    /**
     * 基于现有的 FlowContext 适配会话
     */
    public static AgentSession of(FlowContext context) {
        return new InMemoryAgentSession(context);
    }

    /** 会话唯一标识 */
    private final String sessionId;

    /** * 智能体消息历史存储
     * <p>Key: AgentName, Value: 消息列表。使用线程安全容器以支持并行协作。</p>
     */
    private final Map<String, List<ChatMessage>> historyMessages = new ConcurrentHashMap<>();

    /** * 运行快照（当前会话的状态机上下文）
     */
    private volatile FlowContext snapshot;

    public InMemoryAgentSession(String sessionId) {
        this.sessionId = sessionId;
        this.snapshot = FlowContext.of(sessionId);
        this.snapshot.put(Agent.KEY_SESSION, this);
    }

    public InMemoryAgentSession(FlowContext context) {
        this.sessionId = context.getInstanceId();
        this.snapshot = context;
        this.snapshot.put(Agent.KEY_SESSION, this);
    }

    @Override
    public String getSessionId() {
        return sessionId;
    }

    /**
     * 归档历史消息
     * @param agentName 产生或关联该消息的智能体名称
     * @param message 聊天消息
     */
    @Override
    public void addHistoryMessage(String agentName, ChatMessage message) {
        historyMessages.computeIfAbsent(agentName, k -> new ArrayList<>())
                .add(message);
    }

    /**
     * 获取指定智能体的历史记忆片段
     * @param agentName 智能体名称
     * @param last 获取最近的条数
     */
    @Override
    public Collection<ChatMessage> getHistoryMessages(String agentName, int last) {
        List<ChatMessage> list = historyMessages.get(agentName);

        if (list != null) {
            int size = list.size();
            if (size > last) {
                // 返回最近的 N 条记录
                return list.subList(size - last, size);
            } else {
                return list;
            }
        }

        return Collections.emptyList();
    }

    /**
     * 更新运行时快照（用于在 Flow 引擎执行后同步最新状态）
     */
    @Override
    public void updateSnapshot(FlowContext snapshot) {
        this.snapshot = snapshot;
    }

    /**
     * 获取当前会话的快照上下文
     */
    @Override
    public FlowContext getSnapshot() {
        return snapshot;
    }
}