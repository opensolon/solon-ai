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
package org.noear.solon.ai.chat.session;

import org.noear.redisx.RedisClient;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.ChatSession;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Redis 聊天会话 (带内存缓存层)
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class RedisChatSession implements ChatSession {
    private final String instanceId;
    private final String messagesKey;
    private final RedisClient redisClient;

    // 内存缓存层
    private final InMemoryChatSession cache;

    public RedisChatSession(String instanceId, RedisClient redisClient) {
        Objects.requireNonNull(instanceId, "instanceId is required");
        Objects.requireNonNull(redisClient, "redisClient is required");

        this.instanceId = instanceId;
        this.messagesKey = instanceId + ":messages";
        this.redisClient = redisClient;


        // --- 2. 初始化缓存层 ---
        this.cache = new InMemoryChatSession(instanceId);

        // --- 3. 初始化加载历史消息到缓存 ---
        loadMessagesToCache();
    }

    private void loadMessagesToCache() {
        // 从 Redis 捞取最近的 50 条消息
        List<String> rawList = redisClient.openSession().key(messagesKey).listGetRange(0, 49);
        if (rawList != null && !rawList.isEmpty()) {
            List<ChatMessage> history = new ArrayList<>();
            for (String json : rawList) {
                history.add(ChatMessage.fromJson(json));
            }
            // 保持原有顺序逻辑：Redis LPUSH 出来的需要反转后存入内存 cache
            Collections.reverse(history);
            this.cache.addMessage(history);
        }
    }

    @Override
    public String getSessionId() {
        return instanceId;
    }

    @Override
    public List<ChatMessage> getMessages() {
        // 走缓存
        return cache.getMessages();
    }

    @Override
    public List<ChatMessage> getLatestMessages(int windowSize) {
        // 走缓存
        return cache.getLatestMessages(windowSize);
    }

    @Override
    public void addMessage(Collection<? extends ChatMessage> messages) {
        if (Utils.isEmpty(messages)) return;

        // 1. 同步到内存
        cache.addMessage(messages);

        // 2. 同步到 Redis (保持原逻辑)
        for (ChatMessage msg : messages) {
            if (msg.getRole() != ChatRole.SYSTEM) {
                redisClient.getList(messagesKey).add(ChatMessage.toJson(msg));
            }
        }
    }

    @Override
    public boolean isEmpty() {
        return cache.isEmpty();
    }

    @Override
    public void clear() {
        cache.clear();
        redisClient.getList(messagesKey).clear();
        // 物理清理按照原逻辑涉及的 key
        redisClient.getBucket().remove(instanceId);
    }

    @Override
    public Map<String, Object> attrs() {
        return cache.attrs();
    }
}