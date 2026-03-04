package org.noear.solon.ai.skills.memory;

/**
 *
 * @author noear 2026/3/4 created
 *
 */
public interface MemStoreProvider {
    void put(String key, String val, int ttl);

    String get(String key);

    void remove(String key);
}
