package demo.ai.memory;

import org.noear.solon.ai.talents.memory.MemorySearchProvider;
import org.noear.solon.ai.talents.memory.MemorySolution;
import org.noear.solon.ai.talents.memory.MemoryStoreProvider;
import org.noear.solon.ai.talents.memory.md.MemoryMdData;
import org.noear.solon.ai.talents.memory.search.MemorySearchProviderMdImpl;
import org.noear.solon.ai.talents.memory.store.MemoryStoreProviderMdImpl;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 基于 MD 文件的记忆方案实现（方案 A：纯 MD，零外部依赖）
 *
 * <p>Store 和 Search 共享同一个 {@link MemoryMdData} 实例，保证：
 * <ul>
 *   <li>启动时全量加载已有 MD 文件</li>
 *   <li>写入同时更新搜索索引，存搜一致</li>
 *   <li>读取和搜索都走内存缓存</li>
 * </ul>
 *
 * @author noear 2026/5/8 created
 */
public class MemorySolutionMdImpl implements MemorySolution {
    private final MemoryMdData data;
    private final MemorySearchProvider searchProvider;
    private final MemoryStoreProvider storeProvider;

    public MemorySolutionMdImpl(String __cwd) {
        Path mdPath = Paths.get(__cwd, "memory_md").toAbsolutePath();

        // 创建共享数据层（启动时自动加载已有 MD 文件，启用后台过期清理）
        data = new MemoryMdData(mdPath).enableAutoCleanup(3600);

        // Store 和 Search 共享同一个 data 实例
        storeProvider = new MemoryStoreProviderMdImpl(data);
        searchProvider = new MemorySearchProviderMdImpl(data);
    }

    /**
     * 关闭资源（释放后台清理线程）
     */
    public void close() {
        if (data != null) {
            data.close();
        }
    }

    @Override
    public MemorySearchProvider getSearchProvider() {
        return searchProvider;
    }

    @Override
    public MemoryStoreProvider getStoreProvider() {
        return storeProvider;
    }
}
