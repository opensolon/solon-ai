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
package org.noear.solon.ai.talents.memory.search;

import org.noear.solon.ai.talents.memory.MemorySearcher;
import org.noear.solon.ai.talents.memory.MemorySearchResult;
import org.noear.solon.ai.talents.memory.md.MemoryMdData;

import java.util.List;

/**
 * 基于 MD 文件的记忆搜索供应商（方案 A：纯 MD，零外部依赖）
 *
 * <p>委托给 {@link MemoryMdData} 共享数据层，享受：
 * <ul>
 *   <li>启动时自动加载已有 MD 文件到搜索索引</li>
 *   <li>分词结果缓存，避免重复 tokenize</li>
 *   <li>关键词匹配 + 子串匹配（对中文友好）</li>
 *   <li>与 Store 层共享同一数据实例，保证索引一致性</li>
 * </ul>
 *
 * @author noear
 * @since 3.10.5
 */
public class MemorySearcherMdImpl implements MemorySearcher {

    private final MemoryMdData data;

    /**
     * 基于共享 MdMemoryData 创建（推荐：与 Store 共享同一实例）
     *
     * @param data 共享数据层实例
     */
    public MemorySearcherMdImpl(MemoryMdData data) {
        this.data = data;
    }

    @Override
    public List<MemorySearchResult> search(String userId, String query, int limit) {
        return data.search(userId, query, limit);
    }

    @Override
    public List<MemorySearchResult> getHotMemories(String userId, int limit) {
        return data.getHotMemories(userId, limit);
    }

    @Override
    public List<MemorySearchResult> listAll(String userId, int limit) {
        return data.listAll(userId, limit);
    }

    @Override
    public void updateIndex(String userId, String key, String fact, int importance, String time) {
        data.updateIndex(userId, key, fact, importance, time);
    }

    @Override
    public void removeIndex(String userId, String key) {
        data.removeIndex(userId, key);
    }
}
