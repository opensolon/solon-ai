package org.noear.solon.ai.skills.memory;


/**
 * 记忆存储供应商接口
 * * 负责底层数据的物理持久化（如 Redis, Database, LocalCache 等）
 *
 * @author noear
 * @since 3.9.4
 */
public interface MemoryStoreProvider {
    /**
     * 存入记忆条目
     *
     * @param key   完整存储键（包含前缀与会话标识）
     * @param val   序列化后的记忆 JSON 内容
     * @param ttl   存活时间，单位：秒（-1 表示永久存储）
     */
    void put(String key, String val, int ttl);

    /**
     * 获取记忆条目
     *
     * @param key   存储键
     * @return      序列化内容，若不存在则返回 null
     */
    String get(String key);

    /**
     * 移除特定记忆
     *
     * @param key   存储键
     */
    void remove(String key);
}