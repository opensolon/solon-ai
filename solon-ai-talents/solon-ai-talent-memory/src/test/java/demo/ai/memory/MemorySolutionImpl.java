package demo.ai.memory;

import org.noear.solon.ai.talents.memory.MemorySearcher;
import org.noear.solon.ai.talents.memory.MemorySolution;
import org.noear.solon.ai.talents.memory.MemoryStorer;
import org.noear.solon.ai.talents.memory.search.MemorySearcherLuceneImpl;
import org.noear.solon.ai.talents.memory.store.MemoryStorerRogueImpl;

import java.io.IOException;
import java.nio.file.Paths;

/**
 *
 * @author noear 2026/3/23 created
 *
 */
public class MemorySolutionImpl implements MemorySolution {
    private MemorySearcher searchProvider;
    private MemoryStorer storeProvider;

    public MemorySolutionImpl(String __cwd) {
        String lucenePath = Paths.get(__cwd, "lucene").toAbsolutePath().toString();
        String roguePath = Paths.get(__cwd,  "rogue.db").toAbsolutePath().toString();

        try {
            searchProvider = new MemorySearcherLuceneImpl(lucenePath);
            storeProvider = new MemoryStorerRogueImpl(roguePath);
        } catch (IOException e) {
            throw new RuntimeException(e);
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