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
package org.noear.solon.ai.talents.memory;


/**
 * 记忆存储供应商接口
 * * 负责底层数据的物理持久化（如 Redis, Database, LocalCache 等）
 *
 * @author noear
 * @since 3.9.4
 */
public interface MemoryStorer {
    /**
     * 存入记忆条目（带作用域）
     *
     * <p>实现约定（调用方据此判定写入结果）：
     * <ul>
     *   <li><b>失败必须抛异常</b>：不得吞掉底层异常后静默返回，否则调用方会把失败当成功上报。</li>
     *   <li><b>read-after-write 一致</b>：本方法正常返回后，{@link #get(String, String)} 必须能读到刚写入的值。
     *       调用方会读回校验（内容 + 重要度）通过后才更新检索索引，以免出现「主体未落盘、索引已可搜」的幽灵条目。</li>
     * </ul>
     *
     * @param key   完整存储键（包含前缀与会话标识）
     * @param val   序列化后的记忆 JSON 内容
     * @param ttl   存活时间，单位：秒（-1 表示永久存储）
     * @param scope 存储作用域（如 workspace / user）；为空时由实现自行决定默认域
     * @since 4.0.0
     */
    void put(String userId, String key, String val, int ttl, String scope);

    /**
     * 获取记忆条目
     *
     * <p>多作用域实现应做聚合读并在返回的 JSON 中带上 {@code scope} 字段：
     * 调用方在未显式指定作用域时会沿用该域写回，避免同 Key 跨域产生重影。
     *
     * @param key   存储键
     * @return      序列化内容，若不存在则返回 null
     */
    String get(String userId, String key);

    /**
     * 移除特定记忆
     *
     * <p>实现约定：删除失败必须抛异常（不得只打日志后静默返回）；
     * 多作用域实现会清理所有作用域下的同名 Key，条目在某个域不存在属正常情况、不算失败。
     *
     * @param key   存储键
     */
    void remove(String userId, String key);
}