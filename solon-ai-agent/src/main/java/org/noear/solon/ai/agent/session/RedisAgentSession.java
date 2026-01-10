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
package org.noear.solon.ai.agent.session;

import org.noear.redisx.RedisClient;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.lang.Preview;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Redis 智能体会话适配 (Redis Agent Session)
 *
 * <p>基于 Redis 实现的分布式会话存储，确保多实例环境下的状态一致性：</p>
 * <ul>
 * <li><b>消息持久化</b>：利用 Redis List 存储各智能体的交互历史。</li>
 * <li><b>状态恢复</b>：利用 Redis Bucket 持久化 {@link FlowContext} 快照。</li>
 * </ul>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class RedisAgentSession implements AgentSession {

    private final String instanceId;
    private final RedisClient redisClient;
    private volatile FlowContext snapshot;

    public RedisAgentSession(String instanceId, RedisClient redisClient) {
        Objects.requireNonNull(instanceId, "instanceId is required");
        Objects.requireNonNull(redisClient, "redisClient is required");

        this.instanceId = instanceId;
        this.redisClient = redisClient;

        // 从 Redis 加载会话快照
        String json = redisClient.getBucket().get(instanceId);
        if (json != null) {
            this.snapshot = FlowContext.fromJson(json);
        } else {
            this.snapshot = FlowContext.of(instanceId);
        }

        // 将当前 Session 实例注入快照上下文，便于在 Flow 节点中回溯
        this.snapshot.put(Agent.KEY_SESSION, this);
    }

    @Override
    public String getSessionId() {
        return instanceId;
    }

    @Override
    public void addHistoryMessage(String agentName, ChatMessage message) {
        String key = getHistoryKey(agentName);
        // 向 Redis List 尾部追加消息
        redisClient.getList(key).add(ChatMessage.toJson(message));
    }

    /**
     * 获取指定智能体的历史记忆片段（倒序输出）
     * * @param last 获取最近的条数
     */
    @Override
    public Collection<ChatMessage> getHistoryMessages(String agentName, int last) {
        String key = getHistoryKey(agentName);

        // 使用 Redis 负索引：获取从倒数第 last 个到最后一个的所有元素
        List<String> rawList = redisClient.openSession().key(key).listGetRange(0, last-1);

        if (rawList == null || rawList.isEmpty()) {
            return Collections.emptyList();
        }

        // 将 JSON 转换为对象列表
        List<ChatMessage> messages = rawList.stream()
                .map(ChatMessage::fromJson)
                .collect(Collectors.toList());

        // 执行倒序反转：使最新的消息排在前面（符合某些 UI 或审计逻辑的需求）
        Collections.reverse(messages);

        return messages;
    }

    @Override
    public void updateSnapshot(FlowContext snapshot) {
        this.snapshot = snapshot;
        // 持久化快照，确保分布式节点可见
        redisClient.getBucket().store(instanceId, snapshot.toJson());
    }

    @Override
    public FlowContext getSnapshot() {
        return snapshot;
    }

    /**
     * 构建智能体专属的历史消息 Key
     */
    private String getHistoryKey(String agentName) {
        return "ai:session:" + instanceId + ":" + agentName;
    }

    /**
     * 清理指定智能体的内存记忆
     */
    public void clear(String agentName) {
        String key = getHistoryKey(agentName);
        redisClient.getList(key).clear();
    }
}