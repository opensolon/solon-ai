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
import org.noear.solon.ai.talents.memory.MemoryStoreProvider;
import org.noear.solon.core.util.Assert;

/**
 *
 * @author noear 2026/3/4 created
 *
 */
public class MemoryStoreProviderReadisImpl implements MemoryStoreProvider {
    private final RedisClient redis;
    private String basePrefix;

    public MemoryStoreProviderReadisImpl(RedisClient redis) {
        this.redis = redis;
    }


    public MemoryStoreProviderReadisImpl basePrefix(String basePrefix) {
        this.basePrefix = basePrefix;
        return this;
    }

    private String getFinalKey(String userId, String key) {
        if (Assert.isEmpty(basePrefix)) {
            return userId + ":" + key;
        } else {
            return basePrefix + userId + ":" + key;
        }
    }

    @Override
    public void put(String userId, String key, String val, int ttl) {
        redis.getBucket().store(getFinalKey(userId, key), val, ttl);
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