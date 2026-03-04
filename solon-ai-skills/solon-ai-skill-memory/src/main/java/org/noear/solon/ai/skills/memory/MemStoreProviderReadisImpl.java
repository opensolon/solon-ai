package org.noear.solon.ai.skills.memory;

import org.noear.redisx.RedisClient;

/**
 *
 * @author noear 2026/3/4 created
 *
 */
public class MemStoreProviderReadisImpl implements MemStoreProvider {
    private final RedisClient redis;
    public MemStoreProviderReadisImpl(RedisClient redis){
        this.redis = redis;
    }

    @Override
    public void put(String key, String val, int ttl) {
       redis.getBucket().store(key, val, ttl);
    }

    @Override
    public String get(String key) {
        return redis.getBucket().get(key);
    }

    @Override
    public void remove(String key) {
        redis.getBucket().remove(key);
    }
}
