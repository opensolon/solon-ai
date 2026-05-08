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
package org.noear.solon.ai.skills.memory.md;

import org.noear.snack4.ONode;
import org.noear.solon.ai.skills.memory.MemorySearchResult;
import org.noear.solon.ai.util.Markdown;
import org.noear.solon.ai.util.MarkdownUtil;
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
 *   <li>搜索时复用已缓存的分词结果，避免重复 tokenize</li>
 *   <li>Front Matter 中保存完整 storeKey，消除文件名还原的不确定性</li>
 *   <li>原子写入自动降级（兼容 Windows/FAT32/NFS/Docker overlay）</li>
 *   <li>TTL 过期支持启动时清理和定期后台清理</li>
 * </ul>
 *
 * @author noear
 * @since 3.10.5
 */
public class MemoryMdData {
    private static final Logger LOG = LoggerFactory.getLogger(MemoryMdData.class);
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String FRONT_MATTER_DELIMITER = "---";

    private final Path baseDir;

    /**
     * 内存缓存：storeKey → MemoryEntry
     * storeKey 格式："ai:memskill:{bucketKey}:{userKey}"
     */
    private final Map<String, MemoryEntry> cache = new ConcurrentHashMap<>();

    /**
     * 搜索索引：bucketKey → { docId → IndexEntry }
     * 按桶分组，搜索时直接定位 bucket，避免全量遍历
     */
    private final Map<String, Map<String, IndexEntry>> indexByBucket = new ConcurrentHashMap<>();

    /**
     * 分词缓存：content → tokens
     * 直接以 content 文本为 key，避免 hashCode 碰撞导致的分词错误
     */
    private final Map<String, Set<String>> tokenizeCache = new ConcurrentHashMap<>();

    /**
     * 后台过期清理调度器（可选，通过 enableAutoCleanup 开启）
     */
    private ScheduledExecutorService cleanupScheduler;

    public MemoryMdData(Path baseDir) {
        this.baseDir = baseDir;
        init();
    }

    private void init() {
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create memory storage directory: " + baseDir, e);
        }
        loadFromDisk(baseDir);
    }

    // ==================== Store 操作 ====================

    /**
     * 存入记忆条目：写 MD 文件 + 更新内存缓存
     *
     * <p>注意：搜索索引的更新由 MemorySkill 统一调用 updateIndex() 完成，
     * 保持与其他方案（Lucene/Repository/Rogue）的调用约定一致，避免双写冗余。
     */
    public void put(String storeKey, String val, int ttl) {
        try {
            ONode node = ONode.ofJson(val);
            String content = node.get("content").getString();
            String time = node.get("time").getString();
            int importance = node.get("importance").getInt();
            String storedTime = getNow();

            // 1. 写 MD 文件（Front Matter 中保存完整 storeKey，消除还原歧义）
            Path file = resolveFile(storeKey);
            writeMdFile(file, storeKey, time, importance, ttl, storedTime, content);

            // 2. 更新内存缓存
            cache.put(storeKey, new MemoryEntry(content, time, importance, ttl, storedTime));
        } catch (Exception e) {
            LOG.error("MdMemoryData put error, key={}", storeKey, e);
        }
    }

    /**
     * 获取记忆条目：优先走内存缓存，缓存未命中再读磁盘
     */
    public String get(String storeKey) {
        MemoryEntry entry = cache.get(storeKey);

        if (entry == null) {
            // 缓存未命中，尝试从磁盘加载（可能是外部写入的 MD 文件）
            entry = loadFromMdFile(storeKey);
            if (entry == null) {
                return null;
            }
            cache.put(storeKey, entry);

            // 同步到搜索索引
            String[] parts = splitStoreKey(storeKey);
            if (parts != null) {
                String docId = parts[0] + ":" + parts[1];
                indexByBucket.computeIfAbsent(parts[0], k -> new ConcurrentHashMap<>())
                        .putIfAbsent(docId, new IndexEntry(parts[0], parts[1],
                                entry.content, entry.importance, entry.time));
            }
        }

        // TTL 过期检查
        if (isExpired(entry)) {
            remove(storeKey);
            return null;
        }

        return buildJson(entry.content, entry.time, entry.importance);
    }

    /**
     * 删除记忆条目：删 MD 文件 + 清缓存 + 清搜索索引
     */
    public void remove(String storeKey) {
        Path file = resolveFile(storeKey);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.error("MdMemoryData remove error, key={}", storeKey, e);
        }

        MemoryEntry removed = cache.remove(storeKey);
        if (removed != null) {
            invalidateTokenizeCache(removed.content);
        }

        String[] parts = splitStoreKey(storeKey);
        if (parts != null) {
            String docId = parts[0] + ":" + parts[1];
            Map<String, IndexEntry> bucket = indexByBucket.get(parts[0]);
            if (bucket != null) {
                IndexEntry removedIdx = bucket.remove(docId);
                if (removedIdx != null) {
                    invalidateTokenizeCache(removedIdx.content);
                }
            }
        }
    }

    // ==================== Search 操作 ====================

    /**
     * 搜索：基于缓存的关键词匹配 + 重要性权重评分
     * 按 bucketKey 直接定位索引桶，避免全量遍历
     */
    public List<MemorySearchResult> search(String bucketKey, String query, int limit) {
        if (query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, IndexEntry> bucket = indexByBucket.get(bucketKey);
        if (bucket == null || bucket.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> queryTokens = tokenize(query.toLowerCase());

        List<ScoredEntry> scored = new ArrayList<>();
        for (IndexEntry entry : bucket.values()) {
            double score = computeScore(entry, queryTokens);
            if (score > 0) {
                scored.add(new ScoredEntry(entry, score));
            }
        }

        return scored.stream()
                .sorted(Comparator.comparingDouble((ScoredEntry se) -> se.score).reversed())
                .limit(limit)
                .map(se -> new MemorySearchResult(se.entry.userKey, se.entry.content, se.entry.importance, se.entry.time))
                .collect(Collectors.toList());
    }

    /**
     * 获取高价值热记忆
     */
    public List<MemorySearchResult> getHotMemories(String bucketKey, int limit) {
        Map<String, IndexEntry> bucket = indexByBucket.get(bucketKey);
        if (bucket == null || bucket.isEmpty()) {
            return Collections.emptyList();
        }

        return bucket.values().stream()
                .filter(e -> e.importance >= 5)
                .sorted(Comparator.comparingInt((IndexEntry e) -> e.importance).reversed()
                        .thenComparing((IndexEntry e) -> e.time, Comparator.reverseOrder()))
                .limit(limit)
                .map(e -> new MemorySearchResult(e.userKey, e.content, e.importance, e.time))
                .collect(Collectors.toList());
    }

    /**
     * 手动更新搜索索引（由 MemorySkill 统一调用，兼容 MemorySearchProvider.updateIndex 接口）
     */
    public void updateIndex(String bucketKey, String userKey, String fact, int importance, String time) {
        String docId = bucketKey + ":" + userKey;
        Map<String, IndexEntry> bucket = indexByBucket.computeIfAbsent(bucketKey, k -> new ConcurrentHashMap<>());
        IndexEntry old = bucket.put(docId, new IndexEntry(bucketKey, userKey, fact, importance, time));
        if (old != null) {
            invalidateTokenizeCache(old.content);
        }
        invalidateTokenizeCache(fact);
    }

    /**
     * 手动移除搜索索引（由 MemorySkill 统一调用，兼容 MemorySearchProvider.removeIndex 接口）
     */
    public void removeIndex(String bucketKey, String userKey) {
        String docId = bucketKey + ":" + userKey;
        Map<String, IndexEntry> bucket = indexByBucket.get(bucketKey);
        if (bucket != null) {
            IndexEntry removed = bucket.remove(docId);
            if (removed != null) {
                invalidateTokenizeCache(removed.content);
            }
        }
    }

    // ==================== 启动加载 ====================

    /**
     * 从磁盘全量加载 MD 文件到缓存和搜索索引
     */
    private void loadFromDisk(Path baseDir) {
        int expiredCount = 0;
        try (Stream<Path> files = Files.list(baseDir)) {
            List<Path> mdFiles = files.filter(p -> p.toString().endsWith(".md"))
                                      .collect(Collectors.toList());

            for (Path file : mdFiles) {
                LoadResult lr = loadSingleFile(file);
                if (lr == LoadResult.EXPIRED) {
                    expiredCount++;
                }
            }
        } catch (IOException e) {
            LOG.error("MdMemoryData loadFromDisk error", e);
        }

        if (!cache.isEmpty()) {
            LOG.info("MdMemoryData loaded {} entries from {} ({} expired cleaned)",
                    cache.size(), baseDir, expiredCount);
        }
    }

    private enum LoadResult { LOADED, EXPIRED, SKIPPED }

    private LoadResult loadSingleFile(Path file) {
        try {
            String md = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            FrontMatter fm = parseFrontMatter(md);
            if (fm == null) {
                return LoadResult.SKIPPED;
            }

            // 优先从 Front Matter 读取 storeKey（可靠还原）
            String storeKey = fm.storeKey;
            if (storeKey == null || storeKey.isEmpty()) {
                // 兼容旧格式文件：从文件名启发式还原
                storeKey = fileNameToStoreKey(file.getFileName().toString());
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

            cache.put(storeKey, new MemoryEntry(fm.content, fm.time, fm.importance, fm.ttl, fm.storedTime));

            String[] parts = splitStoreKey(storeKey);
            if (parts != null) {
                String docId = parts[0] + ":" + parts[1];
                indexByBucket.computeIfAbsent(parts[0], k -> new ConcurrentHashMap<>())
                        .put(docId, new IndexEntry(parts[0], parts[1], fm.content, fm.importance, fm.time));
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
    private void writeMdFile(Path file, String storeKey, String time, int importance, int ttl,
                             String storedTime, String content) throws IOException {
        String md = buildMdContent(storeKey, time, importance, ttl, storedTime, content);

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
    private MemoryEntry loadFromMdFile(String storeKey) {
        Path file = resolveFile(storeKey);
        if (!Files.exists(file)) {
            return null;
        }

        try {
            String md = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            FrontMatter fm = parseFrontMatter(md);
            if (fm == null) {
                return null;
            }

            // 同步 Front Matter 中的 storeKey（首次兼容旧文件时补写）
            if (fm.storeKey == null || fm.storeKey.isEmpty()) {
                try {
                    writeMdFile(file, storeKey, fm.time, fm.importance, fm.ttl, fm.storedTime, fm.content);
                } catch (IOException ignored) {
                }
            }

            return new MemoryEntry(fm.content, fm.time, fm.importance, fm.ttl, fm.storedTime);
        } catch (IOException e) {
            LOG.error("MdMemoryData loadFromMdFile error, key={}", storeKey, e);
            return null;
        }
    }

    // ===================== 内部工具方法 =====================

    private Path resolveFile(String storeKey) {
        String safeKey = storeKey.replace(":", "_").replace("/", "_");
        return baseDir.resolve(safeKey + ".md");
    }

    /**
     * 从文件名启发式还原 storeKey（仅用于兼容不含 storeKey 字段的旧格式文件）
     * "ai_memskill_shared_user_pref.md" → "ai:memskill:shared:user_pref"
     */
    private String fileNameToStoreKey(String fileName) {
        String name = fileName.endsWith(".md") ? fileName.substring(0, fileName.length() - 3) : fileName;
        if (name.startsWith("ai_memskill_")) {
            String rest = name.substring("ai_memskill_".length());
            return "ai:memskill:" + rest.replace("_", ":");
        }
        return name.replace("_", ":");
    }

    /**
     * 拆分 storeKey 为 bucketKey 和 userKey
     * "ai:memskill:{bucketKey}:{userKey}" → ["{bucketKey}", "{userKey}"]
     */
    private String[] splitStoreKey(String storeKey) {
        if (storeKey == null) return null;
        String prefix = "ai:memskill:";
        if (!storeKey.startsWith(prefix)) return null;
        String rest = storeKey.substring(prefix.length());
        int firstColon = rest.indexOf(':');
        if (firstColon < 0) return null;
        String bucketKey = rest.substring(0, firstColon);
        String userKey = rest.substring(firstColon + 1);
        return new String[]{bucketKey, userKey};
    }

    private String buildMdContent(String storeKey, String time, int importance, int ttl,
                                  String storedTime, String content) {
        StringBuilder sb = new StringBuilder();
        sb.append(FRONT_MATTER_DELIMITER).append("\n");
        sb.append("store_key: \"").append(escapeYaml(storeKey)).append("\"\n");
        sb.append("time: \"").append(time).append("\"\n");
        sb.append("importance: ").append(importance).append("\n");
        sb.append("ttl: ").append(ttl).append("\n");
        sb.append("stored_time: \"").append(storedTime).append("\"\n");
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
    private FrontMatter parseFrontMatter(String md) {
        if (md == null || md.isEmpty()) return null;

        // 将文本按行分割，交给 MarkdownUtil 解析
        List<String> lines = Arrays.asList(md.split("\n"));
        Markdown markdown = MarkdownUtil.resolve(lines);

        ONode meta = markdown.getMetadata();
        if (meta.size() == 0) return null;

        FrontMatter fm = new FrontMatter();
        fm.content = markdown.getContent();

        if (meta.hasKey("store_key")) {
            fm.storeKey = meta.get("store_key").getString();
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
        if (meta.hasKey("stored_time")) {
            fm.storedTime = meta.get("stored_time").getString();
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

    private String buildJson(String content, String time, int importance) {
        return "{\"content\":" + ONode.serialize(content)
                + ",\"time\":" + ONode.serialize(time)
                + ",\"importance\":" + importance + "}";
    }

    private String getNow() {
        return LocalDateTime.now().format(FORMATTER);
    }

    // ==================== 后台过期清理（编号11） ====================

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
        cleanupScheduler.scheduleAtFixedRate(this::cleanupExpired,
                intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        LOG.info("MdMemoryData auto-cleanup enabled, interval={}s", intervalSeconds);
        return this;
    }

    /**
     * 主动清理所有过期条目（缓存 + 磁盘文件）
     */
    public void cleanupExpired() {
        Iterator<Map.Entry<String, MemoryEntry>> it = cache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, MemoryEntry> e = it.next();
            if (isExpired(e.getValue())) {
                remove(e.getKey());
            }
        }
    }

    // ==================== 搜索评分 ====================

    /**
     * 分词结果缓存：直接以 content 文本为 key，避免 hashCode 碰撞
     */
    private Set<String> tokenizeCached(String text) {
        return tokenizeCache.computeIfAbsent(text, k -> tokenize(k));
    }

    /**
     * 清除分词缓存
     */
    private void invalidateTokenizeCache(String content) {
        if (content != null) {
            tokenizeCache.remove(content.toLowerCase());
        }
    }

    /**
     * 分词：支持英文单词切分 + 中文 bi-gram
     *
     * <p>英文：按非字母数字字符分割，长度 >1 的 token 保留。
     * <p>中文：对连续中文字符做 bi-gram（每两个相邻字组成一个 token），
     * 提升“用户偏好使用Solon框架”这类混合文本的搜索命中率。
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
            // bi-gram 分词：每两个相邻字组成一个 token
            String str = buf.toString();
            for (int i = 0; i < str.length() - 1; i++) {
                tokens.add(str.substring(i, i + 2));
            }
        } else if (buf.length() == 1) {
            // 单字也保留，避免丢失短词匹配
            tokens.add(buf.toString());
        }
        buf.setLength(0);
    }

    private double computeScore(IndexEntry entry, Set<String> queryTokens) {
        String contentLower = entry.content.toLowerCase();
        Set<String> contentTokens = tokenizeCached(contentLower);

        long hits = queryTokens.stream()
                .filter(contentTokens::contains)
                .count();

        if (hits == 0) {
            for (String token : queryTokens) {
                if (contentLower.contains(token)) {
                    hits++;
                }
            }
        }

        if (hits == 0) return 0;

        double hitRate = (double) hits / queryTokens.size();
        double impWeight = entry.importance / 10.0;

        return hitRate * 0.7 + impWeight * 0.3;
    }

    // ==================== 内部数据结构 ====================

    static class MemoryEntry {
        String content;
        String time;
        int importance;
        int ttl;
        String storedTime;

        MemoryEntry(String content, String time, int importance, int ttl, String storedTime) {
            this.content = content;
            this.time = time;
            this.importance = importance;
            this.ttl = ttl;
            this.storedTime = storedTime;
        }
    }

    static class IndexEntry {
        String bucketKey;
        String userKey;
        String content;
        int importance;
        String time;

        IndexEntry(String bucketKey, String userKey, String content, int importance, String time) {
            this.bucketKey = bucketKey;
            this.userKey = userKey;
            this.content = content;
            this.importance = importance;
            this.time = time;
        }
    }

    static class FrontMatter {
        String storeKey = "";
        String time = "";
        int importance = 0;
        int ttl = -1;
        String storedTime = "";
        String content = "";
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
