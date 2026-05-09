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
package org.noear.solon.ai.skills.memory.store;

import org.noear.solon.ai.skills.memory.MemoryStoreProvider;
import org.noear.solon.ai.skills.memory.md.MemoryMdData;

/**
 * 基于 MD 文件的记忆存储供应商（方案 A：纯 MD，零外部依赖）
 *
 * <p>委托给 {@link MemoryMdData} 共享数据层，享受：
 * <ul>
 *   <li>启动时自动加载已有 MD 文件到内存缓存</li>
 *   <li>读取优先走缓存，避免重复磁盘 I/O</li>
 *   <li>原子写入自动降级（兼容 Windows/FAT32/NFS/Docker overlay）</li>
 *   <li>Front Matter 保存完整 storeKey，消除文件名还原歧义</li>
 *   <li>写入同时自动更新搜索索引，保证存搜一致性</li>
 * </ul>
 *
 * <p>单文件格式（YAML Front Matter + 正文）：
 * <pre>
 * ---
 * store_key: "shared:user_pref_framework"
 * time: "2026-05-08 21:24:30"
 * importance: 7
 * ttl: 2592000
 * stored_time: "2026-05-08 21:24:30"
 * ---
 *
 * 用户偏好使用 Solon 框架进行后端开发
 * </pre>
 *
 * @author noear
 * @since 3.10.5
 */
public class MemoryStoreProviderMdImpl implements MemoryStoreProvider {
    private final MemoryMdData data;

    /**
     * 基于共享 MdMemoryData 创建（与 Search 共享同一实例）
     *
     * @param data 共享数据层实例
     */
    public MemoryStoreProviderMdImpl(MemoryMdData data) {
        this.data = data;
    }

    @Override
    public void put(String userId, String key, String val, int ttl) {
        data.put(userId, key, val, ttl);
    }

    @Override
    public String get(String userId, String key) {
        return data.get(userId, key);
    }

    @Override
    public void remove(String userId, String key) {
        data.remove(userId, key);
    }
}
