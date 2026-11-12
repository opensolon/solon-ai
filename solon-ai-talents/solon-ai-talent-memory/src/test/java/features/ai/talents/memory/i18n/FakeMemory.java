package features.ai.talents.memory.i18n;

import org.noear.snack4.ONode;
import org.noear.solon.ai.talents.memory.MemorySearchResult;
import org.noear.solon.ai.talents.memory.MemorySearcher;
import org.noear.solon.ai.talents.memory.MemorySolution;
import org.noear.solon.ai.talents.memory.MemorySolutionProvider;
import org.noear.solon.ai.talents.memory.MemoryStorer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 确定性的内存记忆方案（渲染快照用；可按 Key 注入读 / 写 / 删失败）
 */
public class FakeMemory implements MemorySolution, MemoryStorer, MemorySearcher {
    final Map<String, String> store = new TreeMap<>();
    final Map<String, MemorySearchResult> index = new TreeMap<>();

    final Set<String> getThrows = new HashSet<>();
    final Set<String> putThrows = new HashSet<>();
    final Set<String> putDrops = new HashSet<>();
    final Set<String> removeThrows = new HashSet<>();
    boolean searchThrows;

    /**
     * 提供者（solution 为 null 时模拟“未找到方案”）
     */
    static MemorySolutionProvider provider(FakeMemory solution) {
        return new MemorySolutionProvider() {
            @Override
            public MemorySolution get(String __cwd) {
                return solution;
            }
        };
    }

    /**
     * 直接写入一条（time / importance 可为 null，用于“未知”分支）
     */
    void seed(String key, String content, Integer importance, String time, String scope) {
        ONode node = new ONode();
        node.set("content", content);
        if (time != null) {
            node.set("time", time);
        }
        if (importance != null) {
            node.set("importance", importance);
        }
        if (scope != null) {
            node.set("scope", scope);
        }
        store.put(key, node.toJson());
        index.put(key, new MemorySearchResult(key, content, importance == null ? 0 : importance, time, scope));
    }

    @Override
    public MemorySearcher getSearcher() {
        return this;
    }

    @Override
    public MemoryStorer getStorer() {
        return this;
    }

    // ---- storer ----

    @Override
    public void put(String userId, String key, String val, int ttl, String scope) {
        if (putThrows.contains(key)) {
            throw new IllegalStateException("disk full");
        }
        if (putDrops.contains(key)) {
            return;
        }
        ONode node = ONode.ofJson(val);
        if (scope != null) {
            node.set("scope", scope);
        }
        store.put(key, node.toJson());
    }

    @Override
    public String get(String userId, String key) {
        if (getThrows.contains(key)) {
            throw new IllegalStateException("io error");
        }
        return store.get(key);
    }

    @Override
    public void remove(String userId, String key) {
        if (removeThrows.contains(key)) {
            throw new IllegalStateException("locked");
        }
        store.remove(key);
    }

    // ---- searcher ----

    @Override
    public List<MemorySearchResult> search(String userId, String query, int limit) {
        if (searchThrows) {
            throw new IllegalStateException("search error");
        }
        List<MemorySearchResult> list = new ArrayList<>();
        for (MemorySearchResult r : index.values()) {
            if (list.size() < limit && (r.getContent().contains(query) || query.contains(r.getContent()))) {
                list.add(r);
            }
        }
        return list;
    }

    @Override
    public List<MemorySearchResult> getHotMemories(String userId, int limit) {
        List<MemorySearchResult> list = new ArrayList<>();
        for (MemorySearchResult r : index.values()) {
            if (list.size() < limit && r.getImportance() >= 5) {
                list.add(r);
            }
        }
        return list;
    }

    @Override
    public List<MemorySearchResult> listAll(String userId, int limit) {
        List<MemorySearchResult> list = new ArrayList<>();
        for (MemorySearchResult r : index.values()) {
            if (list.size() < limit) {
                list.add(r);
            }
        }
        return list;
    }

    @Override
    public void updateIndex(String userId, String key, String fact, int importance, String time, String scope) {
        index.put(key, new MemorySearchResult(key, fact, importance, time, scope));
    }

    @Override
    public void removeIndex(String userId, String key) {
        index.remove(key);
    }
}
