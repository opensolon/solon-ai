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
package org.noear.solon.ai.talents.memory.md;

import org.noear.solon.ai.talents.memory.MemorySearcher;
import org.noear.solon.ai.talents.memory.MemorySolution;
import org.noear.solon.ai.talents.memory.MemoryStorer;
import org.noear.solon.ai.talents.memory.search.MemorySearcherMdImpl;
import org.noear.solon.ai.talents.memory.store.MemoryStorerMdImpl;
import org.noear.solon.ai.talents.mount.source.MountSource;

import java.nio.file.Path;
import java.util.Map;

/**
 * 基于 MD 文件的记忆方案实现（方案 A：纯 MD，零外部依赖，开箱即用）
 *
 * <p>Store 和 Search 共享同一个 {@link MemoryMdData} 实例，保证：
 * <ul>
 *   <li>启动时全量加载已有 MD 文件</li>
 *   <li>写入同时更新搜索索引，存搜一致</li>
 *   <li>读取和搜索都走内存缓存</li>
 *   <li>后台定期清理过期条目</li>
 * </ul>
 *
 * <p><b>存储位置</b>：每个作用域是一个 {@link MountSource}，MD 文件可以放在本地，也可以放在 db / http 等远程来源（算存分离）。
 * 注意缓存与索引在各进程内存中：同一来源被多个实例同时使用时，其他实例的写入要在重建本实例后才能检索到
 * （按 key 读取在缓存未命中时会回源）。需要多实例实时一致时，请用 Redis（{@link MemoryStorer}）+ 向量库（{@link MemorySearcher}）方案。</p>
 *
 * @author noear
 * @since 3.10.5
 */
public class MemorySolutionMdImpl implements MemorySolution, AutoCloseable {
    private final MemoryMdData data;
    private final MemorySearcher searchProvider;
    private final MemoryStorer storeProvider;

    /**
     * 高级构造器：自定义作用域目录映射。
     *
     * <p>需使用 LinkedHashMap 保证迭代顺序（低→高优先级），
     * 例如 {@code user → workspace}，后者覆盖前者。
     *
     * @param scopeDirMap 作用域目录映射
     * @deprecated 4.2.0，改用 {@link #of(Map)}（本地目录传 {@code FileMountSource.of(dir)}）
     */
    @Deprecated
    public MemorySolutionMdImpl(Map<String, Path> scopeDirMap) {
        this(new MemoryMdData(scopeDirMap));
    }

    private MemorySolutionMdImpl(MemoryMdData data) {
        this.data = data.enableAutoCleanup(3600);

        storeProvider = new MemoryStorerMdImpl(data);
        searchProvider = new MemorySearcherMdImpl(data);
    }

    /**
     * 基于挂载来源创建（本地、db、http 均可）。
     *
     * <p>须使用 LinkedHashMap 等有序 Map（低→高优先级），例如 {@code user → workspace}，后者覆盖前者；
     * 条目放在各来源根下。</p>
     *
     * @param scopeSourceMap 作用域来源映射
     * @return 记忆方案
     * @since 4.2.0
     */
    public static MemorySolutionMdImpl of(Map<String, MountSource> scopeSourceMap) {
        return new MemorySolutionMdImpl(MemoryMdData.of(scopeSourceMap));
    }

    @Override
    public void close() {
        if (data != null) {
            data.close();
        }
    }

    @Override
    public MemorySearcher getSearcher() {
        return searchProvider;
    }

    @Override
    public MemoryStorer getStorer() {
        return storeProvider;
    }
}
