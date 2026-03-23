package demo.ai.memory;

import org.noear.solon.ai.skills.memory.MemorySearchProvider;
import org.noear.solon.ai.skills.memory.MemorySolution;
import org.noear.solon.ai.skills.memory.MemoryStoreProvider;
import org.noear.solon.ai.skills.memory.search.MemorySearchProviderLuceneImpl;
import org.noear.solon.ai.skills.memory.store.MemoryStoreProviderRogueImpl;

import java.io.IOException;
import java.nio.file.Paths;

/**
 *
 * @author noear 2026/3/23 created
 *
 */
public class MemorySolutionImpl implements MemorySolution {
    private MemorySearchProvider searchProvider;
    private MemoryStoreProvider storeProvider;

    public MemorySolutionImpl(String __cwd) {
        String lucenePath = Paths.get(__cwd, "lucene").toAbsolutePath().toString();
        String roguePath = Paths.get(__cwd,  "rogue.db").toAbsolutePath().toString();

        try {
            searchProvider = new MemorySearchProviderLuceneImpl(lucenePath);
            storeProvider = new MemoryStoreProviderRogueImpl(roguePath);
        } catch (IOException e) {
            throw new RuntimeException(e);
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