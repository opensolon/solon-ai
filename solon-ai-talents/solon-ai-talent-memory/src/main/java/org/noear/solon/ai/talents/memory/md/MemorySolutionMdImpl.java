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

import java.nio.file.Path;

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
 * <p>用法：
 * <pre>
 * MemorySolution solution = new MemorySolutionMdImpl(__cwd);
 * </pre>
 *
 * @author noear
 * @since 3.10.5
 */
public class MemorySolutionMdImpl implements MemorySolution, AutoCloseable {
    private final MemoryMdData data;
    private final MemorySearcher searchProvider;
    private final MemoryStorer storeProvider;

    public MemorySolutionMdImpl(Path mdPath) {
        data = new MemoryMdData(mdPath).enableAutoCleanup(3600);

        storeProvider = new MemoryStorerMdImpl(data);
        searchProvider = new MemorySearcherMdImpl(data);
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
