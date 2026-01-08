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

import org.noear.redisx.RedisClient;
import org.noear.redisx.plus.RedisList;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.flow.FlowContext;

import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Redis 智能体会话适配
 *
 * @author noear
 * @since 3.8.1
 */
public class RedisAgentSession implements AgentSession {
    public static AgentSession of(String instanceId) {
        return new InMemoryAgentSession(instanceId);
    }

    private final String instanceId;
    private final RedisClient redisClient;
    private final FlowContext snapshot;

    public RedisAgentSession(String instanceId, RedisClient redisClient) {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(redisClient, "redisClient");

        this.instanceId = instanceId;
        this.redisClient = redisClient;

        String json = redisClient.getBucket().get(instanceId);
        this.snapshot = FlowContext.fromJson(json);
        this.snapshot.put(Agent.KEY_SESSION, this);
    }


    @Override
    public String getSessionId() {
        return instanceId;
    }

    @Override
    public void addHistoryMessage(String agentName, ChatMessage message) {
        redisClient.getList(instanceId + ":" + agentName).add(ChatMessage.toJson(message));
    }

    @Override
    public Collection<ChatMessage> getHistoryMessages(String agentName, int last) {
        RedisList list = redisClient.getList(instanceId + ":" + agentName);

        if (list != null) {
            if (list.size() > last) {
                return list.getRange(list.size() - last, list.size())
                        .stream()
                        .map(json -> ChatMessage.fromJson(json))
                        .collect(Collectors.toList());
            } else {
                return list.getAll()
                        .stream()
                        .map(json -> ChatMessage.fromJson(json))
                        .collect(Collectors.toList());
            }
        }

        return Collections.emptyList();
    }

    @Override
    public void updateSnapshot(FlowContext snapshot) {
        redisClient.getBucket().store(instanceId, snapshot.toJson());
    }

    @Override
    public FlowContext getSnapshot() {
        return snapshot;
    }
}
