package demo.ai.memory;

import org.noear.solon.ai.talents.memory.MemorySolution;
import org.noear.solon.ai.talents.memory.MemorySolutionProvider;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 *
 * @author noear 2026/3/23 created
 *
 */
public class MemoryManger implements MemorySolutionProvider {
    private Map<String, MemorySolution> cached = new ConcurrentHashMap<>();

    @Override
    public MemorySolution get(String __cwd) {
        return cached.computeIfAbsent(__cwd, k -> new MemorySolutionImpl(k));
    }
}
