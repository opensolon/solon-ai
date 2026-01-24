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
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Redis 智能体会话适配器 (分布式持久化方案)
 * * <p>提供跨实例的消息同步与状态恢复能力，适用于集群部署。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class RedisAgentSession implements AgentSession {
    private static final Logger LOG = LoggerFactory.getLogger(RedisAgentSession.class);

    private final String instanceId;
    private final RedisClient redisClient;
    private volatile FlowContext snapshot;

    public RedisAgentSession(String instanceId, RedisClient redisClient) {
        Objects.requireNonNull(instanceId, "instanceId is required");
        Objects.requireNonNull(redisClient, "redisClient is required");

        this.instanceId = instanceId;
        this.redisClient = redisClient;

        // 加载或初始化持久化快照
        String json = redisClient.getBucket().get(instanceId);
        if (json != null) {
            this.snapshot = FlowContext.fromJson(json);
            if (LOG.isDebugEnabled()) {
                LOG.debug("Session [{}] loaded from Redis.", instanceId);
            }
        } else {
            this.snapshot = FlowContext.of(instanceId);
        }

        // 注入 Session 实例到上下文，便于 Flow 节点回溯
        this.snapshot.put(Agent.KEY_SESSION, this);
    }

    @Override
    public String getSessionId() {
        return instanceId;
    }

    @Override
    public List<ChatMessage> getMessages() {
        return getLatestMessages(50);
    }

    @Override
    public void addMessage(Collection<? extends ChatMessage> messages) {
        String messagesKey = getMessagesKey();

        for (ChatMessage msg : messages) {
            if (msg.getRole() != ChatRole.SYSTEM) {
                redisClient.getList(messagesKey).add(ChatMessage.toJson(msg));
            }
        }
    }

    @Override
    public boolean isEmpty() {
        String messagesKey = getMessagesKey();

        return redisClient.getList(messagesKey).size() == 0;
    }

    /**
     * 物理清理指定智能体的历史记忆
     */
    public void clear() {
        String messagesKey = getMessagesKey();

        redisClient.getList(messagesKey).clear();
    }

    /**
     * 获取指定智能体的历史记忆
     */
    @Override
    public List<ChatMessage> getLatestMessages(int windowSize) {
        String messagesKey = getMessagesKey();

        List<String> rawList = redisClient.openSession().key(messagesKey).listGetRange(0, windowSize - 1);

        if (rawList == null || rawList.isEmpty()) {
            return Collections.emptyList();
        }

        List<ChatMessage> messages = rawList.stream()
                .map(ChatMessage::fromJson)
                .collect(Collectors.toList());

        // 反转列表，使最新消息符合逻辑顺序输出
        Collections.reverse(messages);

        return messages;
    }

    @Override
    public void updateSnapshot(FlowContext snapshot) {
        this.snapshot = snapshot;
        // 实时同步快照至持久层，确保分布式节点可见性
        String snapshotKey = getSnapshotKey();
        redisClient.getBucket().store(snapshotKey, snapshot.toJson());

        if (LOG.isDebugEnabled()) {
            LOG.debug("Session [{}] snapshot persisted to Redis.", snapshotKey);
        }
    }

    @Override
    public FlowContext getSnapshot() {
        return snapshot;
    }

    private String getSnapshotKey() {
        return instanceId + ":snapshot";
    }

    private String getMessagesKey() {
        return instanceId + ":messages";
    }
}