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

import org.noear.snack4.ONode;
import org.noear.solon.ai.talents.memory.MemorySearchResult;
import org.noear.solon.ai.util.Markdown;
import org.noear.solon.ai.util.MarkdownUtil;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * MD 方案的共享数据层：统一管理 MD 文件读写、内存缓存与搜索索引。
 *
 * <p>Store 层和 Search 层共享同一个 MdMemoryData 实例，保证：
 * <ul>
 *   <li>启动时从 MD 文件目录全量加载，重启后搜索索引不丢失</li>
 *   <li>写入 MD 文件的同时更新内存缓存和搜索索引，保证存搜一致性</li>
 *   <li>读取优先走内存缓存，避免重复磁盘 I/O</li>
 *   <li>分词结果内联到索引条目，随条目生命周期自动释放，无缓存泄漏风险</li>
 *   <li>Front Matter 中保存完整 storeKey，消除文件名还原的不确定性</li>
 *   <li>原子写入自动降级（兼容 Windows/FAT32/NFS/Docker overlay）</li>
 *   <li>TTL 过期支持启动时清理和定期后台清理</li>
 * </ul>
 *
 * @author noear
 * @since 3.10.5
 */
public class MemoryMdData implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(MemoryMdData.class);
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String FRONT_MATTER_DELIMITER = "---";

    /**
     * 作用域目录映射（scope → 物理目录）。
     * 使用 LinkedHashMap 保证迭代顺序（低→高），高优先级域覆盖低优先级域的同 key 条目。
     * 建议插入顺序：user → workspace。
     */
    private final Map<String, Path> scopeDirMap;

    /**
     * 内存缓存：cacheKey → MemoryEntry
     * cacheKey 格式："{userId}__{key}@{scope}"（含 scope 维度，避免跨域同 key 覆盖）
     */
    private final Map<String, MemoryEntry> cache = new ConcurrentHashMap<>();

    /**
     * 搜索索引：userId → { docId → IndexEntry }
     * 按用户分组，搜索时直接定位用户，避免全量遍历
     */
    private final Map<String, Map<String, IndexEntry>> indexByUser = new ConcurrentHashMap<>();

    /**
     * 后台过期清理调度器（可选，通过 enableAutoCleanup 开启）
     */
    private ScheduledExecutorService cleanupScheduler;

    /**
     * @param scopeDirMap 作用域目录映射，需使用 LinkedHashMap 保证迭代顺序（低→高优先级）
     */
    public MemoryMdData(Map<String, Path> scopeDirMap) {
        // 用 LinkedHashMap 保序，消除 HashMap 迭代序不确定性
        if (scopeDirMap instanceof LinkedHashMap) {
            this.scopeDirMap = scopeDirMap;
        } else {
            this.scopeDirMap = new LinkedHashMap<>(scopeDirMap);
        }

        init();
    }

    private void init() {
        for (String scope : scopeDirMap.keySet()) {
            Path dir = scopeDirMap.get(scope);
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create memory storage directory: " + dir, e);
            }
            loadFromDisk(dir, scope);
            cleanupTmpFiles(dir);
        }
    }

    // ==================== Store 操作 ====================

    /**
     * 存入记忆条目：写 MD 文件 + 更新内存缓存
     *
     * <p>注意：搜索索引的更新由 MemoryTalent 统一调用 updateIndex() 完成，
     * 保持与其他方案（Lucene/Repository/Rogue）的调用约定一致，避免双写冗余。
     */
    public void put(String userId, String key, String val, int ttl, String scope) {
        String storeKey = buildStoreKey(userId, key);
        try {
            ONode node = ONode.ofJson(val);
            String content = node.get("content").getString();
            String time = node.get("time").getString();
            int importance = node.get("importance").getInt();
            String storedTime = getNow();

            // 1. 写 MD 文件（Front Matter 中保存完整 storeKey 和 scope，消除还原歧义）
            Path file = resolveFile(storeKey, scopeDirMap.get(scope));
            writeMdFile(file, storeKey, scope, time, importance, ttl, storedTime, content);

            // 2. 更新内存缓存（scope 维度隔离，避免跨域同 key 覆盖）
            cache.put(buildCacheKey(userId, key, scope), new MemoryEntry(content, time, importance, ttl, storedTime, scope));
        } catch (Exception e) {
            LOG.error("MdMemoryData put error, userId={}, key={}", userId, key, e);
        }
    }

    /**
     * 获取记忆条目：优先走内存缓存，缓存未命中再读磁盘
     */
    public String get(String userId, String key) {
        String storeKey = buildStoreKey(userId, key);

        // 按优先级顺序探测缓存（scopeDirMap 迭代序 = 低→高，后者覆盖前者）
        MemoryEntry entry = null;
        String foundScope = null;
        for (String scope : scopeDirMap.keySet()) {
            MemoryEntry cached = cache.get(buildCacheKey(userId, key, scope));
            if (cached != null) {
                entry = cached;
                foundScope = scope;
            }
        }

        if (entry == null) {
            // 缓存未命中，按优先级顺序从磁盘加载
            // Note: 并发 get() 可能在 put() 的 updateIndex() 之前短暂加载旧数据并写入索引，
            // 属于最终一致性可接受范围——put() 的 updateIndex() 随后会覆盖为最新值。
            for (String scope : scopeDirMap.keySet()) {
                MemoryEntry found = loadFromMdFile(storeKey, scope, scopeDirMap.get(scope));
                if (found != null) {
                    entry = found;
                    foundScope = scope;
                }
            }

            if (entry == null) {
                return null;
            }
            cache.put(buildCacheKey(userId, key, foundScope), entry);

            // 同步到搜索索引
            indexByUser.computeIfAbsent(userId, k -> new ConcurrentHashMap<>())
                    .put(buildDocId(userId, key),
                            new IndexEntry(userId, key, entry.content, entry.importance, entry.time, entry.scope));
        }

        // TTL 过期检查
        if (isExpired(entry)) {
            remove(userId, key);
            return null;
        }

        ONode oNode = new ONode();
        oNode.set("content", entry.content);
        oNode.set("time", entry.time);
        oNode.set("importance", entry.importance);
        oNode.set("scope", entry.scope);

        return oNode.toJson();
    }

    /**
     * 删除记忆条目：删 MD 文件 + 清缓存 + 清搜索索引
     */
    public void remove(String userId, String key) {
        String storeKey = buildStoreKey(userId, key);

        for(Map.Entry<String,Path> entry : scopeDirMap.entrySet()) {
            Path file = resolveFile(storeKey, entry.getValue());
            try {
                boolean deleted = Files.deleteIfExists(file);
                if (!deleted) {
                    LOG.debug("MdMemoryData remove: file not found (normal for multi-scope), userId={}, key={}, file={}", userId, key, file);
                } else {
                    LOG.debug("MdMemoryData remove: file deleted, userId={}, key={}", userId, key);
                }
            } catch (IOException e) {
                LOG.error("MdMemoryData remove error (file may be locked), userId={}, key={}, file={}", userId, key, file, e);
            }
        }

        // 清除所有作用域的缓存条目
        for (String scope : scopeDirMap.keySet()) {
            cache.remove(buildCacheKey(userId, key, scope));
        }

        Map<String, IndexEntry> userMap = indexByUser.get(userId);
        if (userMap != null) {
            userMap.remove(buildDocId(userId, key));
            if (userMap.isEmpty()) {
                indexByUser.remove(userId);
            }
        }
    }

    // ==================== Search 操作 ====================

    /**
     * 搜索：BM25 风格的关键词匹配 + 确定性排序
     * 按 userId 直接定位索引，避免全量遍历。
     * 已过期条目（TTL 已到但尚未被后台清理）不参与检索。
     * 排序：相关性得分降序 → 同分按重要性降序 → 再同分按时间倒序（新→旧）。
     */
    public List<MemorySearchResult> search(String userId, String query, int limit) {
        if (query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, IndexEntry> userIndex = indexByUser.get(userId);
        if (userIndex == null || userIndex.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> queryTokens = tokenize(query.toLowerCase());

        // 统计词频（df：token 出现在多少条记忆中），供 IDF 加权评分使用。
        // 记忆库规模小（通常几十~几百条），每次搜索遍历一次索引成本可接受，且天然语言无关。
        Map<String, Integer> df = new HashMap<>();
        int total = 0;
        for (IndexEntry entry : userIndex.values()) {
            if (isIndexExpired(entry)) {
                continue; // 已过期条目（TTL 已到但尚未被后台清理）不参与检索
            }
            total++;
            for (String token : getTokens(entry)) {
                df.merge(token, 1, Integer::sum);
            }
        }

        List<ScoredEntry> scored = new ArrayList<>();
        for (IndexEntry entry : userIndex.values()) {
            if (isIndexExpired(entry)) {
                continue; // 已过期条目（TTL 已到但尚未被后台清理）不参与检索
            }
            double score = computeScore(entry, queryTokens, df, total);
            if (score > 0) {
                scored.add(new ScoredEntry(entry, score));
            }
        }

        return scored.stream()
                .sorted(Comparator.comparingDouble((ScoredEntry se) -> se.score).reversed()
                        .thenComparing(Comparator.comparingInt((ScoredEntry se) -> se.entry.importance).reversed())
                        .thenComparing(Comparator.comparing((ScoredEntry se) -> se.entry.time).reversed()))
                .limit(limit)
                .map(se -> new MemorySearchResult(se.entry.userKey, se.entry.content, se.entry.importance, se.entry.time, se.entry.scope))
                .collect(Collectors.toList());
    }

    /**
     * 获取高价值热记忆（已过期条目不参与）
     */
    public List<MemorySearchResult> getHotMemories(String userId, int limit) {
        Map<String, IndexEntry> userIndex = indexByUser.get(userId);
        if (userIndex == null || userIndex.isEmpty()) {
            return Collections.emptyList();
        }

        return userIndex.values().stream()
                .filter(e -> !isIndexExpired(e))
                .filter(e -> e.importance >= 5)
                .sorted(Comparator.comparingInt((IndexEntry e) -> e.importance).reversed()
                        .thenComparing((IndexEntry e) -> e.time, Comparator.reverseOrder()))
                .limit(limit)
                .map(e -> new MemorySearchResult(e.userKey, e.content, e.importance, e.time, e.scope))
                .collect(Collectors.toList());
    }

    /**
     * 列举全部记忆条目（不做重要度过滤），按重要度倒序、时间倒序返回。
     * 已过期（TTL 已到但尚未被后台清理）的条目不展示。
     */
    public List<MemorySearchResult> listAll(String userId, int limit) {
        Map<String, IndexEntry> userIndex = indexByUser.get(userId);
        if (userIndex == null || userIndex.isEmpty()) {
            return Collections.emptyList();
        }

        return userIndex.values().stream()
                .filter(e -> !isIndexExpired(e))
                .sorted(Comparator.comparingInt((IndexEntry e) -> e.importance).reversed()
                        .thenComparing((IndexEntry e) -> e.time, Comparator.reverseOrder()))
                .limit(limit)
                .map(e -> new MemorySearchResult(e.userKey, e.content, e.importance, e.time, e.scope))
                .collect(Collectors.toList());
    }

    /**
     * 列举指定用户下所有记忆条目的 key
     */
    public Set<String> keys(String userId) {
        Map<String, IndexEntry> userIndex = indexByUser.get(userId);
        if (userIndex == null || userIndex.isEmpty()) {
            return Collections.emptySet();
        }
        return userIndex.values().stream()
                .map(e -> e.userKey)
                .collect(Collectors.toSet());
    }

    /**
     * 手动更新搜索索引（由 MemoryTalent 统一调用，兼容 MemorySearchProvider.updateIndex 接口）
     */
    public void updateIndex(String userId, String key, String fact, int importance, String time, String scope) {
        Map<String, IndexEntry> userIndex = indexByUser.computeIfAbsent(userId, k -> new ConcurrentHashMap<>());
        userIndex.put(buildDocId(userId, key), new IndexEntry(userId, key, fact, importance, time, scope));
    }

    /**
     * 手动移除搜索索引（由 MemoryTalent 统一调用，兼容 MemorySearchProvider.removeIndex 接口）
     */
    public void removeIndex(String userId, String key) {
        Map<String, IndexEntry> userIndex = indexByUser.get(userId);
        if (userIndex != null) {
            userIndex.remove(buildDocId(userId, key));
            if (userIndex.isEmpty()) {
                indexByUser.remove(userId);
            }
        }
    }

    // ==================== 启动加载 ====================

    /**
     * 从磁盘全量加载 MD 文件到缓存和搜索索引
     */
    private void loadFromDisk(Path baseDir, String scope) {
        int expiredCount = 0;
        int loadedCount = 0;
        try (Stream<Path> files = Files.list(baseDir)) {
            List<Path> mdFiles = files.filter(p -> p.getFileName().toString().endsWith(".md"))
                                      .collect(Collectors.toList());

            for (Path file : mdFiles) {
                LoadResult lr = loadSingleFile(file, scope);
                if (lr == LoadResult.EXPIRED) {
                    expiredCount++;
                } else if (lr == LoadResult.LOADED) {
                    loadedCount++;
                }
            }
        } catch (IOException e) {
            LOG.error("MdMemoryData loadFromDisk error", e);
        }

        if (loadedCount > 0 || expiredCount > 0) {
            LOG.info("MdMemoryData loaded {} entries from {} ({} expired cleaned)",
                    loadedCount, baseDir, expiredCount);
        }
    }

    private enum LoadResult { LOADED, EXPIRED, SKIPPED }

    private LoadResult loadSingleFile(Path file, String dirScope) {
        try {
            FrontMatter fm = parseFrontMatter(Files.readAllLines(file, StandardCharsets.UTF_8));
            if (fm == null) {
                return LoadResult.SKIPPED;
            }

            // 优先从 Front Matter 读取 storeKey（可靠还原）
            String storeKey = fm.storeKey;
            if (storeKey == null || storeKey.isEmpty()) {
                // 兼容旧格式文件：文件名即 storeKey（当前格式 storeKey + ".md"）
                storeKey = fileNameToStoreKey(file.getFileName().toString());
                LOG.warn("MdMemoryData: file has no name field, heuristic restore may be inaccurate: {}", file);
            }

            // TTL 过期检查，过期的不加载并删除文件
            if (fm.ttl > 0 && fm.storedTime != null && !fm.storedTime.isEmpty()) {
                try {
                    LocalDateTime stored = LocalDateTime.parse(fm.storedTime, FORMATTER);
                    if (Duration.between(stored, LocalDateTime.now()).getSeconds() > fm.ttl) {
                        Files.deleteIfExists(file);
                        return LoadResult.EXPIRED;
                    }
                } catch (Exception ignored) {
                }
            }

            if (fm.content == null || fm.content.trim().isEmpty()) {
                LOG.warn("MdMemoryData: file has empty content: {}", file);
                return LoadResult.SKIPPED;
            }

            // 有效 scope：优先 Front Matter，其次目录归属
            String effectiveScope = (fm.scope != null && !fm.scope.isEmpty()) ? fm.scope : dirScope;

            String[] parts = splitStoreKey(storeKey);
            if (parts != null) {
                cache.put(buildCacheKey(parts[0], parts[1], effectiveScope),
                        new MemoryEntry(fm.content, fm.time, fm.importance, fm.ttl, fm.storedTime, effectiveScope));

                indexByUser.computeIfAbsent(parts[0], k -> new ConcurrentHashMap<>())
                        .put(buildDocId(parts[0], parts[1]),
                                new IndexEntry(parts[0], parts[1], fm.content, fm.importance, fm.time, effectiveScope));
            }

            return LoadResult.LOADED;
        } catch (Exception e) {
            LOG.warn("MdMemoryData loadSingleFile error: {}", file, e);
            return LoadResult.SKIPPED;
        }
    }

    // ==================== MD 文件读写 ====================

    /**
     * 写入 MD 文件（原子写入 + 自动降级）
     */
    private void writeMdFile(Path file, String storeKey, String scope, String time, int importance, int ttl,
                             String storedTime, String content) throws IOException {
        String md = buildMdContent(storeKey, scope, time, importance, ttl, storedTime, content);

        Files.createDirectories(file.getParent());
        Path tmpFile = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmpFile, md.getBytes(StandardCharsets.UTF_8));

        try {
            Files.move(tmpFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // 降级为普通 rename（Windows/FAT32/NFS/Docker overlay 等环境）
            Files.move(tmpFile, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 从 MD 文件加载单条记忆（缓存未命中时调用）
     */
    private MemoryEntry loadFromMdFile(String storeKey, String scope, Path scopeBaseDir) {
        Path file = resolveFile(storeKey, scopeBaseDir);
        if (!Files.exists(file)) {
            return null;
        }

        try {
            FrontMatter fm = parseFrontMatter(Files.readAllLines(file, StandardCharsets.UTF_8));
            if (fm == null) {
                return null;
            }

            // 有效 scope：优先 Front Matter，其次参数传入的目录归属
            String effectiveScope = (fm.scope != null && !fm.scope.isEmpty()) ? fm.scope : scope;
            MemoryEntry tempEntry = new MemoryEntry(fm.content, fm.time, fm.importance, fm.ttl, fm.storedTime, effectiveScope);

            // 先检查 TTL，过期的直接删除文件返回 null，避免无意义的补写 I/O
            if (isExpired(tempEntry)) {
                try { Files.deleteIfExists(file); } catch (IOException ignored) {}
                return null;
            }

            // 未过期且 Front Matter 中缺少 storeKey 或 scope 时补写（兼容旧文件）
            if (fm.storeKey == null || fm.storeKey.isEmpty() || fm.scope == null || fm.scope.isEmpty()) {
                try {
                    writeMdFile(file, storeKey, scope, fm.time, fm.importance, fm.ttl, fm.storedTime, fm.content);
                } catch (IOException ignored) {
                }
            }

            return tempEntry;
        } catch (IOException e) {
            LOG.error("MdMemoryData loadFromMdFile error, key={}", storeKey, e);
            return null;
        }
    }

    // ===================== 内部工具方法 =====================

    /**
     * 构建完整 storeKey："{userId}__{key}"
     */
    private String buildStoreKey(String userId, String key) {
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException("userId must not be empty");
        }
        return userId + "__" + key;
    }

    /**
     * 构建 docId："{userId}:{key}"
     * <p>
     * 当前与 storeKey 格式一致，独立方法便于后续格式变化时统一修改。
     */
    private String buildDocId(String userId, String key) {
        return userId + ":" + key;
    }

    private Path resolveFile(String storeKey, Path scopeBaseDir) {
        return scopeBaseDir.resolve(storeKey + ".md");
    }

    /**
     * 清理残留的 .tmp 文件（writeMdFile 中 move 失败时可能残留）
     */
    private void cleanupTmpFiles(Path baseDir) {
        try (Stream<Path> files = Files.list(baseDir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".tmp"))
                 .forEach(p -> {
                     try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                 });
        } catch (IOException ignored) {
        }
    }

    /**
     * 从文件名还原 storeKey（仅用于兼容不含 name 字段的旧格式文件）
     * <p>
     * 当前文件名格式为 "{storeKey}.md"，去掉 .md 后缀即为 storeKey。
     * 对于极旧格式（hash 前缀）的文件，还原可能不准确，会通过 splitStoreKey 返回 null 跳过索引。
     */
    private String fileNameToStoreKey(String fileName) {
        return fileName.endsWith(".md") ? fileName.substring(0, fileName.length() - 3) : fileName;
    }

    /**
     * 构建含 scope 维度的缓存键："{userId}__{key}@{scope}"
     */
    private String buildCacheKey(String userId, String key, String scope) {
        return buildStoreKey(userId, key) + "@" + (scope != null ? scope : "");
    }

    /**
     * 拆分缓存键为 userId 和 key（丢弃 scope 维度）
     * "{userId}__{key}@{scope}" → ["{userId}", "{key}"]
     */
    private String[] splitCacheKey(String cacheKey) {
        if (cacheKey == null) return null;
        int atIdx = cacheKey.indexOf('@');
        String storeKey = atIdx > 0 ? cacheKey.substring(0, atIdx) : cacheKey;
        return splitStoreKey(storeKey);
    }

    /**
     * 拆分 storeKey 为 userId 和 key
     * "{userId}__{key}" → ["{userId}", "{key}"]
     */
    private String[] splitStoreKey(String storeKey) {
        if (storeKey == null) return null;
        // 使用 lastIndexOf 防止 userId 含 "__" 时产生歧义拆分
        int sepIdx = storeKey.lastIndexOf("__");
        if (sepIdx < 0) return null;
        String userId = storeKey.substring(0, sepIdx);
        String key = storeKey.substring(sepIdx + 2);
        return new String[]{userId, key};
    }

    private String buildMdContent(String storeKey, String scope, String time, int importance, int ttl,
                                  String storedTime, String content) {
        StringBuilder sb = new StringBuilder();
        sb.append(FRONT_MATTER_DELIMITER).append("\n");
        sb.append("name: \"").append(escapeYaml(storeKey)).append("\"\n");
        sb.append("time: \"").append(time).append("\"\n");
        sb.append("importance: ").append(importance).append("\n");
        sb.append("ttl: ").append(ttl).append("\n");
        sb.append("stored_at: \"").append(storedTime).append("\"\n");
        if (scope != null && !scope.isEmpty()) {
            sb.append("scope: \"").append(escapeYaml(scope)).append("\"\n");
        }
        sb.append(FRONT_MATTER_DELIMITER).append("\n\n");
        sb.append(content).append("\n");
        return sb.toString();
    }

    /**
     * 转义 YAML 值中的特殊字符（storeKey 含冒号，必须引号包裹）
     */
    private String escapeYaml(String val) {
        if (val == null) return "";
        return val.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 解析 MD 文件的 Front Matter
     *
     * <p>使用项目自带的 {@link MarkdownUtil} 解析 YAML Front Matter，替代手写解析。
     * MarkdownUtil 基于 SnakeYAML，可复原地处理转义字符（先 \\ 再 \"），
     * 且只在前几行内查找结束符 --- ，避免 body 中的 --- 被误识别。
     */
    private FrontMatter parseFrontMatter(List<String> lines) {
        if (Assert.isEmpty(lines)) return null;

        Markdown markdown = MarkdownUtil.resolve(lines);

        ONode meta = markdown.getMetadata();
        if (meta.size() == 0) return null;

        FrontMatter fm = new FrontMatter();
        fm.content = markdown.getContent();

        if (meta.hasKey("name")) {
            fm.storeKey = meta.get("name").getString();
        }
        if (meta.hasKey("time")) {
            fm.time = meta.get("time").getString();
        }
        if (meta.hasKey("importance")) {
            fm.importance = meta.get("importance").getInt();
        }
        if (meta.hasKey("ttl")) {
            fm.ttl = meta.get("ttl").getInt();
        }
        if (meta.hasKey("stored_at")) {
            fm.storedTime = meta.get("stored_at").getString();
        }
        if (meta.hasKey("scope")) {
            fm.scope = meta.get("scope").getString();
        }

        return fm;
    }

    private boolean isExpired(MemoryEntry entry) {
        if (entry.ttl < 0) return false;
        if (entry.storedTime == null || entry.storedTime.isEmpty()) return false;

        try {
            LocalDateTime stored = LocalDateTime.parse(entry.storedTime, FORMATTER);
            return Duration.between(stored, LocalDateTime.now()).getSeconds() > entry.ttl;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 判断索引条目是否已过期（反查缓存中的 TTL 信息）
     *
     * <p>索引条目不携带 TTL（接口签名未含），故反查 cache 中同 key 的 MemoryEntry。
     * 缓存无对应条目时按未过期处理（保守降级，保持原有行为）。
     */
    private boolean isIndexExpired(IndexEntry entry) {
        MemoryEntry cached = cache.get(buildCacheKey(entry.userId, entry.userKey, entry.scope));
        return cached != null && isExpired(cached);
    }

    private String getNow() {
        return LocalDateTime.now().format(FORMATTER);
    }

    // ==================== 后台过期清理 ====================

    /**
     * 启用后台定时清理过期条目
     *
     * @param intervalSeconds 清理间隔（秒）
     */
    public MemoryMdData enableAutoCleanup(long intervalSeconds) {
        if (cleanupScheduler != null) {
            return this; // 已启用
        }
        cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "md-memory-cleanup");
            t.setDaemon(true);
            return t;
        });
        cleanupScheduler.scheduleAtFixedRate(() -> {
                    try {
                        cleanupExpired();
                    } catch (Exception e) {
                        LOG.error("MdMemoryData cleanup error", e);
                    }
                },
                intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        LOG.info("MdMemoryData auto-cleanup enabled, interval={}s", intervalSeconds);
        return this;
    }

    /**
     * 主动清理所有过期条目（缓存 + 磁盘文件）
     *
     * <p>先收集过期 key 再统一删除，避免遍历中修改导致的弱一致性问题。
     */
    public void cleanupExpired() {
        List<String[]> expiredKeys = new ArrayList<>();
        for (Map.Entry<String, MemoryEntry> e : cache.entrySet()) {
            if (isExpired(e.getValue())) {
                String[] parts = splitCacheKey(e.getKey());
                if (parts != null) {
                    expiredKeys.add(parts);
                }
            }
        }
        for (String[] parts : expiredKeys) {
            remove(parts[0], parts[1]);
        }
        if (!expiredKeys.isEmpty()) {
            LOG.debug("MdMemoryData cleanup: {} expired entries removed", expiredKeys.size());
        }
    }

    // ==================== 搜索评分 ====================


    /**
     * 获取索引条目的分词结果（内联缓存，随条目生命周期自动释放）
     *
     * <p>双重检查锁定保证线程安全：volatile 读在 synchronized 外面，
     * 绝大多数情况下直接返回已缓存的分词结果，不进入同步块。
     */
    private Set<String> getTokens(IndexEntry entry) {
        Set<String> tokens = entry.tokens;
        if (tokens == null) {
            synchronized (entry) {
                tokens = entry.tokens;
                if (tokens == null) {
                    tokens = tokenize(entry.content.toLowerCase());
                    entry.tokens = tokens;
                }
            }
        }
        return tokens;
    }

    /**
     * 分词：支持英文单词切分 + 中文 bi-gram（语言无关，不依赖停用词表）
     *
     * <p>英文/日文假名/韩文等连续字母数字：整段作为 token（长度 >1 保留）。
     * <p>中文：对连续中文字符做 bi-gram（每两个相邻字组成一个 token）并保留完整短语，
     * 提升"用户偏好使用Solon框架"这类混合文本的搜索命中率。
     * <p>"的""户的""what"等低区分度 token 不在此过滤，统一交由搜索评分的
     * IDF 逆文档频率加权压制（数据驱动、语言无关，见 {@link #computeScore}）。
     */
    private Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();

        // 提取所有连续的英文片段和中文片段
        StringBuilder englishBuf = new StringBuilder();
        StringBuilder chineseBuf = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '\u4e00' && c <= '\u9fff') {
                // 先 flush 英文缓冲区
                flushEnglish(englishBuf, tokens);
                chineseBuf.append(c);
            } else if (Character.isLetterOrDigit(c)) {
                // 先 flush 中文缓冲区
                flushChinese(chineseBuf, tokens);
                englishBuf.append(c);
            } else {
                // 分隔符：flush 两个缓冲区
                flushEnglish(englishBuf, tokens);
                flushChinese(chineseBuf, tokens);
            }
        }

        // flush 尾部
        flushEnglish(englishBuf, tokens);
        flushChinese(chineseBuf, tokens);

        return tokens;
    }

    private void flushEnglish(StringBuilder buf, Set<String> tokens) {
        if (buf.length() > 1) {
            tokens.add(buf.toString().toLowerCase());
        }
        buf.setLength(0);
    }

    private void flushChinese(StringBuilder buf, Set<String> tokens) {
        if (buf.length() >= 2) {
            String str = buf.toString();
            // 保留完整短语（提升短查询的精确匹配）
            tokens.add(str);
            // bi-gram 分词：每两个相邻字组成一个 token
            for (int i = 0; i < str.length() - 1; i++) {
                tokens.add(str.substring(i, i + 2));
            }
        } else if (buf.length() == 1) {
            // 单字也保留，避免丢失短词匹配
            tokens.add(buf.toString());
        }
        buf.setLength(0);
    }

    /**
     * 搜索评分：BM25 风格的求和式 IDF + 子串兜底
     *
     * <p>评分策略（数据驱动、语言无关，替代硬编码停用词表）：
     * <ul>
     *   <li>精确 token 命中：对命中的 token 累加 IDF（求和式，未命中词完全不进入得分，
     *       不会像比率式那样被查询中的常见词整体缩放），再除以理论最大 IDF 和归一化到 [0,1]。
     *       IDF 使高区分度 token（如专有名词 "Solon"）主导排序，而 "的""户的""what" 等
     *       df 高的低区分度 token 自动被压制，对中文 bi-gram、英文及日韩等其他语言一视同仁</li>
     *   <li>子串兜底命中：仅当精确 token 命中为 0 时触发，按命中 token 数占比计分（降权）</li>
     *   <li>重要性（importance）不再线性混入分数，而是作为同分时的次级排序依据（见 {@link #search}），
     *       保证排序由相关性主导、元数据次级，语义与 Lucene 的 BM25 一致，且排序完全确定</li>
     * </ul>
     *
     * @param df 每个 token 在库内出现的记忆条数（由 search 统计）
     * @param n  参与评分的总记忆条数（未过期）
     */
    private double computeScore(IndexEntry entry, Set<String> queryTokens, Map<String, Integer> df, int n) {
        Set<String> contentTokens = getTokens(entry);

        // 阶段一：IDF 求和式精确命中（未命中词不进入得分）
        double hitScore = 0;
        for (String token : queryTokens) {
            if (contentTokens.contains(token)) {
                hitScore += idf(df.getOrDefault(token, 0), n);
            }
        }

        if (hitScore > 0) {
            // 归一化到 [0,1]：除以理论最大 IDF 和（全部查询词均为最高区分度）
            double maxScore = queryTokens.size() * (Math.log(n + 1.0) + 1.0);
            return maxScore > 0 ? hitScore / maxScore : 0;
        }

        // 阶段二：子串兜底（降权）
        String contentLower = entry.content.toLowerCase();
        long substrHits = 0;
        for (String token : queryTokens) {
            if (contentLower.contains(token)) {
                substrHits++;
            }
        }

        if (substrHits == 0) return 0;

        return (double) substrHits / queryTokens.size();
    }

    /**
     * IDF（逆文档频率）：token 出现的记忆条数越多，区分度越低。
     * 平滑处理避免除零；df=0 的查询词权重最高，但零命中不贡献分子，无副作用。
     */
    private static double idf(int df, int n) {
        return Math.log(((double) n + 1) / (df + 1)) + 1;
    }

    // ==================== 生命周期管理 ====================

    @Override
    public void close() {
        if (cleanupScheduler != null) {
            cleanupScheduler.shutdownNow();
            cleanupScheduler = null;
        }
    }

    // ==================== 内部数据结构 ====================

    static class MemoryEntry {
        String content;
        String time;
        int importance;
        int ttl;
        String storedTime;
        String scope;

        MemoryEntry(String content, String time, int importance, int ttl, String storedTime, String scope) {
            this.content = content;
            this.time = time;
            this.importance = importance;
            this.ttl = ttl;
            this.storedTime = storedTime;
            this.scope = scope;
        }
    }

    static class IndexEntry {
        String userId;
        String userKey;
        String content;
        int importance;
        String time;
        String scope;
        /**
         * 分词结果内联缓存（lazy init，随 IndexEntry 生命周期自动释放）
         */
        volatile Set<String> tokens;

        IndexEntry(String userId, String userKey, String content, int importance, String time, String scope) {
            this.userId = userId;
            this.userKey = userKey;
            this.content = content;
            this.importance = importance;
            this.time = time;
            this.scope = scope;
        }
    }

    static class FrontMatter {
        String storeKey = "";
        String time = "";
        int importance = 0;
        int ttl = -1;
        String storedTime = "";
        String content = "";
        String scope = "";
    }

    static class ScoredEntry {
        final IndexEntry entry;
        final double score;

        ScoredEntry(IndexEntry entry, double score) {
            this.entry = entry;
            this.score = score;
        }
    }
}
