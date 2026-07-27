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
package demo.ai.memory;

import org.noear.solon.ai.talents.memory.MemorySearchResult;
import org.noear.solon.ai.talents.memory.MemorySearcher;
import org.noear.solon.ai.talents.memory.MemorySolution;
import org.noear.solon.ai.talents.memory.MemoryStorer;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多作用域复合记忆方案：对外表现为「一个」普通 {@link MemorySolution}，
 * 把「写按域分、读自动合并（高优先域覆盖低优先域同 key）」的细节全部封装在内部。
 *
 * <p>调用方（如 MemoryTalent）只面对这一个方案，无需感知作用域循环与结果打标：
 * <ul>
 *   <li>写：{@code put(...,scope)} 按 scope 路由到对应子域；scope 为空落到默认域</li>
 *   <li>读单条：{@code get} 按优先级从高到低探测，命中即返回（高优先域覆盖）</li>
 *   <li>删：{@code remove} / {@code removeIndex} 在所有子域执行（全删）</li>
 *   <li>搜/热/列：合并各子域结果，按 key 去重（高优先域覆盖），并给每条 {@code setScope}</li>
 *   <li>索引：{@code updateIndex} 探测 key 落在哪个子域，路由到该域（put 先于 updateIndex，此时 key 已落域）</li>
 * </ul>
 *
 * <p>构造约定：{@code children} 的迭代顺序 = 作用域优先级递增，
 * 即<b>末项优先级最高</b>（最具体，如 workspace 排在 user 之后）。
 *
 * @author noear
 * @since 4.0.0
 */
public class ScopedMemorySolution implements MemorySolution {
    private static final Logger LOG = LoggerFactory.getLogger(ScopedMemorySolution.class);

    /** scope -> 子方案，迭代顺序为优先级递增（末项最高） */
    private final LinkedHashMap<String, MemorySolution> children;
    /** 默认写入域（scope 为空时使用） */
    private final String defaultScope;
    /** 优先级从高到低的 scope 顺序（children 逆序），用于探测/合并覆盖 */
    private final List<String> scopesHighToLow;

    private final MemoryStorer storer = new ScopedStorer();
    private final MemorySearcher searcher = new ScopedSearcher();

    /**
     * @param children     scope -> 子方案；迭代顺序为优先级递增（末项最高，如 {user, workspace}）
     * @param defaultScope 默认写入域；为空时取 children 末项（最高优先级）
     */
    public ScopedMemorySolution(LinkedHashMap<String, MemorySolution> children, String defaultScope) {
        if (Assert.isEmpty(children)) {
            throw new IllegalArgumentException("children must not be empty");
        }
        this.children = children;

        List<String> keys = new ArrayList<>(children.keySet());
        Collections.reverse(keys);
        this.scopesHighToLow = keys;

        this.defaultScope = Assert.isNotEmpty(defaultScope) ? defaultScope : keys.get(0);
    }

    @Override
    public MemorySearcher getSearcher() {
        return searcher;
    }

    @Override
    public MemoryStorer getStorer() {
        return storer;
    }

    private MemorySolution childOf(String scope) {
        MemorySolution sol = children.get(scope);
        if (sol == null) {
            sol = children.get(defaultScope);
        }
        return sol;
    }

    // ==================== 复合 Storer ====================

    private class ScopedStorer implements MemoryStorer {
        @Override
        public void put(String userId, String key, String val, int ttl) {
            put(userId, key, val, ttl, defaultScope);
        }

        @Override
        public void put(String userId, String key, String val, int ttl, String scope) {
            String s = Assert.isNotEmpty(scope) ? scope : defaultScope;
            MemorySolution sol = childOf(s);
            if (sol != null && sol.getStorer() != null) {
                sol.getStorer().put(userId, key, val, ttl);
            }
        }

        @Override
        public String get(String userId, String key) {
            // 优先级从高到低探测，命中即返回（高优先域覆盖低优先域）
            for (String scope : scopesHighToLow) {
                MemorySolution sol = children.get(scope);
                if (sol == null || sol.getStorer() == null) continue;
                try {
                    String val = sol.getStorer().get(userId, key);
                    if (Assert.isNotEmpty(val)) {
                        return val;
                    }
                } catch (Exception e) {
                    LOG.warn("ScopedMemorySolution get error, scope={}, key={}", scope, key, e);
                }
            }
            return null;
        }

        @Override
        public void remove(String userId, String key) {
            // 全删：所有子域都尝试移除
            for (MemorySolution sol : children.values()) {
                if (sol == null || sol.getStorer() == null) continue;
                try {
                    sol.getStorer().remove(userId, key);
                } catch (Exception e) {
                    LOG.warn("ScopedMemorySolution remove error, key={}", key, e);
                }
            }
        }
    }

    // ==================== 复合 Searcher ====================

    private class ScopedSearcher implements MemorySearcher {
        @Override
        public List<MemorySearchResult> search(String userId, String query, int limit) {
            List<MemorySearchResult> merged = mergeByKey(sol -> {
                try {
                    return sol.getSearcher().search(userId, query, limit);
                } catch (Exception e) {
                    LOG.warn("ScopedMemorySolution search error", e);
                    return Collections.emptyList();
                }
            });
            // 相关性场景按重要度倒序
            merged.sort(Comparator.comparingDouble(MemorySearchResult::getImportance).reversed());
            return truncate(merged, limit);
        }

        @Override
        public List<MemorySearchResult> getHotMemories(String userId, int limit) {
            List<MemorySearchResult> merged = mergeByKey(sol -> {
                try {
                    return sol.getSearcher().getHotMemories(userId, limit);
                } catch (Exception e) {
                    LOG.warn("ScopedMemorySolution getHotMemories error", e);
                    return Collections.emptyList();
                }
            });
            sortByImportanceThenTime(merged);
            return truncate(merged, limit);
        }

        @Override
        public List<MemorySearchResult> listAll(String userId, int limit) {
            List<MemorySearchResult> merged = mergeByKey(sol -> {
                try {
                    return sol.getSearcher().listAll(userId, limit);
                } catch (Exception e) {
                    LOG.warn("ScopedMemorySolution listAll error", e);
                    return Collections.emptyList();
                }
            });
            sortByImportanceThenTime(merged);
            return truncate(merged, limit);
        }

        @Override
        public void updateIndex(String userId, String key, String fact, int importance, String time) {
            // 探测 key 落在哪个子域（put 已先于本次调用完成），路由到该域更新索引
            for (String scope : scopesHighToLow) {
                MemorySolution sol = children.get(scope);
                if (sol == null || sol.getStorer() == null || sol.getSearcher() == null) continue;
                try {
                    if (Assert.isNotEmpty(sol.getStorer().get(userId, key))) {
                        sol.getSearcher().updateIndex(userId, key, fact, importance, time);
                        return;
                    }
                } catch (Exception e) {
                    LOG.warn("ScopedMemorySolution updateIndex probe error, scope={}, key={}", scope, key, e);
                }
            }
            // 兜底：落到默认域
            MemorySolution def = children.get(defaultScope);
            if (def != null && def.getSearcher() != null) {
                def.getSearcher().updateIndex(userId, key, fact, importance, time);
            }
        }

        @Override
        public void removeIndex(String userId, String key) {
            // 全删：所有子域都尝试移除索引
            for (MemorySolution sol : children.values()) {
                if (sol == null || sol.getSearcher() == null) continue;
                try {
                    sol.getSearcher().removeIndex(userId, key);
                } catch (Exception e) {
                    LOG.warn("ScopedMemorySolution removeIndex error, key={}", key, e);
                }
            }
        }
    }

    // ==================== 合并工具 ====================

    private interface ScopedQuery {
        List<MemorySearchResult> apply(MemorySolution sol);
    }

    /**
     * 按 key 合并各子域结果并打 scope 标记：按优先级从低到高遍历，
     * 使用 LinkedHashMap 让高优先域覆盖低优先域同 key（后 put 覆盖）。
     */
    private List<MemorySearchResult> mergeByKey(ScopedQuery query) {
        Map<String, MemorySearchResult> map = new LinkedHashMap<>();
        // children 迭代顺序为优先级递增，正序遍历使高优先域（靠后）覆盖低优先域
        for (Map.Entry<String, MemorySolution> e : children.entrySet()) {
            MemorySolution sol = e.getValue();
            if (sol == null || sol.getSearcher() == null) continue;
            for (MemorySearchResult r : query.apply(sol)) {
                r.setScope(e.getKey());
                map.put(r.getKey(), r);
            }
        }
        return new ArrayList<>(map.values());
    }

    private void sortByImportanceThenTime(List<MemorySearchResult> list) {
        list.sort((a, b) -> {
            int d = Double.compare(b.getImportance(), a.getImportance());
            if (d != 0) return d;
            return String.valueOf(b.getTime()).compareTo(String.valueOf(a.getTime()));
        });
    }

    private List<MemorySearchResult> truncate(List<MemorySearchResult> list, int limit) {
        if (limit > 0 && list.size() > limit) {
            return new ArrayList<>(list.subList(0, limit));
        }
        return list;
    }
}
