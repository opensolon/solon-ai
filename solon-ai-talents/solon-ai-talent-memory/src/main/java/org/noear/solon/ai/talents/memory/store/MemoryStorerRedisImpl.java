/*
 * Copyright 2017-2026 noear.org and authors
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
package org.noear.solon.ai.talents.memory.store;

import org.noear.redisx.RedisClient;
import org.noear.solon.ai.talents.memory.MemorySolutionProvider;
import org.noear.solon.ai.talents.memory.MemoryStorer;
import org.noear.solon.core.util.Assert;

import java.util.Arrays;
import java.util.List;

/**
 *
 * @author noear 2026/3/4 created
 *
 */
public class MemoryStorerRedisImpl implements MemoryStorer {
    private final RedisClient redis;
    private final List<String> scopeOrder;
    private String basePrefix;

    public MemoryStorerRedisImpl(RedisClient redis) {
        this.redis = redis;
        this.scopeOrder = Arrays.asList(MemorySolutionProvider.SCOPE_USER);
    }

    public MemoryStorerRedisImpl(RedisClient redis, List<String> scopeOrder) {
        this.redis = redis;
        this.scopeOrder = scopeOrder;
    }

    public MemoryStorerRedisImpl basePrefix(String basePrefix) {
        this.basePrefix = basePrefix;
        return this;
    }

    private String getFinalKey(String userId, String key, String scope) {
        String base = Assert.isEmpty(basePrefix) ? "" : basePrefix;
        return base + (scope != null && !scope.isEmpty() ? scope + ":" : "") + userId + ":" + key;
    }

    @Override
    public void put(String userId, String key, String val, int ttl, String scope) {
        if (ttl < 0) {
            // ttl=-1 表示永久存储，不设置过期时间
            redis.getBucket().store(getFinalKey(userId, key, scope), val);
        } else {
            redis.getBucket().store(getFinalKey(userId, key, scope), val, ttl);
        }
    }

    @Override
    public String get(String userId, String key) {
        String result = null;
        for (String scope : scopeOrder) {
            String val = redis.getBucket().get(getFinalKey(userId, key, scope));
            if (val != null) {
                result = val; // 后遍历的 scope 覆盖先遍历的（workspace 优先级最高）
            }
        }
        return result;
    }

    @Override
    public void remove(String userId, String key) {
        for (String scope : scopeOrder) {
            redis.getBucket().remove(getFinalKey(userId, key, scope));
        }
    }
}
