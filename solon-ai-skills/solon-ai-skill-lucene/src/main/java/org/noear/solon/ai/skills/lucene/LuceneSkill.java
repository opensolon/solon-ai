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
package org.noear.solon.ai.skills.lucene;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.annotation.Param;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 基于 Lucene 的代码搜索技能 (Indexing & Semantic Search)
 *
 * @author noear
 * @since 3.9.1
 */
public class LuceneSkill extends AbsSkill {
    private static final Logger LOG = LoggerFactory.getLogger(LuceneSkill.class);

    protected final Path rootPath;
    protected final Directory indexDirectory;
    protected final Analyzer analyzer;

    // 可配置的忽略列表
    private Set<String> ignoreNames = new HashSet<>(Arrays.asList(
            ".git", ".svn", ".log", ".tmp", "node_modules", "target", "bin", "build", ".idea", ".vscode", ".DS_Store"
    ));

    // 可配置的可搜索后缀名
    private Set<String> searchableExtensions = new HashSet<>(Arrays.asList(
            "java", "xml", "js", "ts", "md", "properties", "sql", "txt", "html", "json", "yml", "yaml", "sh", "bat"
    ));

    public LuceneSkill(String workDir) {
        this.rootPath = Paths.get(workDir).toAbsolutePath().normalize();
        this.indexDirectory = new ByteBuffersDirectory();
        this.analyzer = new StandardAnalyzer();
    }

    /**
     * 设置忽略的文件名或目录名
     */
    public LuceneSkill ignoreNames(Collection<String> names) {
        if (names != null) this.ignoreNames = new HashSet<>(names);
        return this;
    }

    /**
     * 设置允许索引的文件后缀 (不带点)
     */
    public LuceneSkill searchableExtensions(Collection<String> exts) {
        if (exts != null) {
            this.searchableExtensions = exts.stream()
                    .map(String::toLowerCase)
                    .collect(Collectors.toSet());
        }
        return this;
    }

    @Override
    public String name() {
        return "local_full_text_search_manager";
    }

    @Override
    public String description() {
        return "高性能本地全文检索工具。支持后缀: " + searchableExtensions;
    }

    @Override
    public boolean isSupported(Prompt prompt) {
        return true;
    }

    @Override
    public String getInstruction(Prompt prompt) {
        return "## 本地全文搜索协议 (Local Search Protocol)\n" +
                "- **工具定位**：这是你感知当前工作区内容的“本地雷达”。当你无法通过目录结构定位具体逻辑，或需要查找跨文件的符号引用时使用。\n" +
                "- **数据边界**：搜索仅限于当前项目根目录及挂载的只读池。它是私有的、实时的、不依赖外部网络的。\n" +
                "- **搜索策略**：支持模糊关键词。结果按 Lucene 相关性排序。若由于文件大幅改动导致搜索结果不自然，应立即执行 `refresh_search_index`。\n" +
                "- **性能习惯**：对于已知路径的小文件，优先使用 `read_file`；对于“大海捞针”式的查询，必须使用此协议。";
    }

    @ToolMapping(name = "full_text_search", description = "在项目文件中进行本地全文检索（支持代码、配置、文档）。")
    public String full_text_search(@Param(value = "query", description = "搜索关键字或短语") String query) {
        try {
            if (!DirectoryReader.indexExists(indexDirectory)) {
                return "本地索引尚未建立。请先执行 refresh_search_index 工具以初始化搜索环境。";
            }
        } catch (IOException e) {
            return "检查索引状态失败: " + e.getMessage();
        }

        try (IndexReader reader = DirectoryReader.open(indexDirectory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            QueryParser parser = new QueryParser("content", analyzer);
            // 对输入进行转义，防止特殊符号导致 Lucene 解析报错
            Query q = parser.parse(QueryParser.escape(query));

            TopDocs docs = searcher.search(q, 20);
            if (docs.totalHits.value == 0) return "未找到匹配内容。";

            StringBuilder sb = new StringBuilder();
            sb.append("找到 ").append(docs.totalHits.value).append(" 个结果 (按相关性排序)：\n\n");

            for (ScoreDoc sd : docs.scoreDocs) {
                Document d = searcher.doc(sd.doc);
                String content = d.get("content");
                String path = d.get("path");

                // 1. 获取相关性评分 (归一化处理以便于阅读)
                float score = sd.score;

                // 2. 搜索关键词位置并计算行号
                int idx = content.toLowerCase().indexOf(query.toLowerCase());
                int lineNum = 1;
                if (idx != -1) {
                    // 计算行号：统计关键词之前的换行符数量
                    for (int i = 0; i < idx; i++) {
                        if (content.charAt(i) == '\n') lineNum++;
                    }
                }

                // 3. 格式化输出：[得分] 路径 : 行号
                sb.append(String.format("Path: %s (Score: %.2f, Line: ~%d)\n", path, score, lineNum));

                // 4. 预览逻辑
                if (idx != -1) {
                    int start = Math.max(0, idx - 60);
                    int end = Math.min(content.length(), idx + 120);
                    String preview = content.substring(start, end).replace("\n", " ").trim();
                    sb.append("   预览: ...").append(preview).append("...\n");
                } else {
                    // 保底预览：显示文件开头
                    String head = content.substring(0, Math.min(content.length(), 120)).replace("\n", " ").trim();
                    sb.append("   预览: ").append(head).append("...\n");
                }
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            LOG.error("Full text search error", e);
            return "搜索失败: " + e.getMessage();
        }
    }

    @ToolMapping(name = "refresh_search_index", description = "刷新本地全文索引。")
    public String refreshSearchIndex() {
        long start = System.currentTimeMillis();
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE); // 重新构建索引

        // 使用数组或 AtomicInteger 来绕过匿名内部类的变量捕获限制
        int[] stats = {0};

        try (IndexWriter writer = new IndexWriter(indexDirectory, config)) {
            Files.walkFileTree(rootPath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    // 排除忽略目录
                    if (ignoreNames.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String name = file.getFileName().toString().toLowerCase();
                    int lastDot = name.lastIndexOf('.');
                    String ext = (lastDot == -1) ? "" : name.substring(lastDot + 1);

                    if (searchableExtensions.contains(ext)) {
                        try {
                            byte[] bytes = Files.readAllBytes(file);
                            String content = new String(bytes, StandardCharsets.UTF_8);

                            Document doc = new Document();
                            // StringField 不分词，用于路径存储
                            doc.add(new StringField("path", rootPath.relativize(file).toString().replace("\\", "/"), Field.Store.YES));
                            // TextField 会分词，用于全文搜索
                            doc.add(new TextField("content", content, Field.Store.YES));

                            writer.addDocument(doc);
                            stats[0]++; // 计数增加
                        } catch (Exception e) {
                            LOG.warn("Failed to index file: " + file, e);
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            writer.commit();
            long duration = System.currentTimeMillis() - start;

            // 返回更具象的信息，喂给 Agent 的“确定性”
            return String.format("本地索引刷新成功！已扫描并收录 %d 个文件，耗时 %dms。", stats[0], duration);

        } catch (IOException e) {
            LOG.error("Refresh search index failed", e);
            return "刷新索引失败: " + e.getMessage();
        }
    }
}