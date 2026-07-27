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
import org.noear.solon.ai.talents.memory.MemoryStorer;
import org.noear.solon.core.util.Assert;

/**
 * Redis 存储实现。
 *
 * <p>Redis 是扁平 K/V 存储，所有数据都在同一个键空间内，
 * 不像 MD 方案那样按作用域分目录存放。因此本实现不支持作用域（scope），
 * {@code put} 的 scope 参数将被忽略。
 *
 * @author noear 2026/3/4 created
 *
 */
public class MemoryStorerRedisImpl implements MemoryStorer {
    private final RedisClient redis;
    private String basePrefix;

    public MemoryStorerRedisImpl(RedisClient redis) {
        this.redis = redis;
    }

    public MemoryStorerRedisImpl basePrefix(String basePrefix) {
        this.basePrefix = basePrefix;
        return this;
    }

    private String getFinalKey(String userId, String key) {
        String base = Assert.isEmpty(basePrefix) ? "" : basePrefix;
        return base + userId + ":" + key;
    }

    @Override
    public void put(String userId, String key, String val, int ttl, String scope) {
        // scope 在扁平 K/V 存储中无物理意义，忽略
        if (ttl < 0) {
            // ttl=-1 表示永久存储，不设置过期时间
            redis.getBucket().store(getFinalKey(userId, key), val);
        } else {
            redis.getBucket().store(getFinalKey(userId, key), val, ttl);
        }
    }

    @Override
    public String get(String userId, String key) {
        return redis.getBucket().get(getFinalKey(userId, key));
    }

    @Override
    public void remove(String userId, String key) {
        redis.getBucket().remove(getFinalKey(userId, key));
    }
}
