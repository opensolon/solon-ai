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

    private final Path rootPath;
    private final Directory indexDirectory;
    private final Analyzer analyzer;

    // 可配置的忽略列表
    private Set<String> ignoreNames = new HashSet<>(Arrays.asList(
            ".git", ".svn", "node_modules", "target", "bin", "build", ".idea", ".vscode", ".DS_Store"
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
        return "full_text_search_manager";
    }

    @Override
    public String description() {
        return "高性能全文检索工具。支持后缀: " + searchableExtensions;
    }

    @Override
    public boolean isSupported(Prompt prompt) {
        return true;
    }

    @Override
    public String getInstruction(Prompt prompt) {
        return "#### 全文搜索协议 (Search Protocol)\n" +
                "- **工具定位**：这是你在复杂环境中定位信息的“雷达”。当你不知道目标内容在哪个文件，或需要查找跨文件关联时使用。\n" +
                "- **搜索策略**：支持模糊关键词。搜索结果会按相关性排序，并提供上下文预览以供参考。\n" +
                "- **索引依赖**：搜索结果依赖于当前索引。若近期有大量文件变更，请务必先执行 `refresh_search_index`。\n" +
                "- **避坑指南**：如果工作区文件极少（例如只有 1-2 个），直接 `read_file` 可能比搜索更快捷。";
    }

    @ToolMapping(name = "full_text_search", description = "在项目文件中进行全文检索（支持代码、配置、文档）。")
    public String full_text_search(@Param(value = "query", description = "搜索关键字或短语") String query) {
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
                sb.append(String.format("📍 %s (Score: %.2f, Line: ~%d)\n", path, score, lineNum));

                // 4. 预览逻辑
                if (idx != -1) {
                    int start = Math.max(0, idx - 60);
                    int end = Math.min(content.length(), idx + 120);
                    String preview = content.substring(start, end).replace("\n", " ");
                    sb.append("   预览: ...").append(preview).append("...\n");
                } else {
                    // 保底预览：显示文件开头
                    String head = content.substring(0, Math.min(content.length(), 120)).replace("\n", " ");
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

    @ToolMapping(name = "refresh_search_index", description = "刷新全文索引。")
    public String refreshSearchIndex() {
        long start = System.currentTimeMillis();
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);

        try (IndexWriter writer = new IndexWriter(indexDirectory, config)) {
            Files.walkFileTree(rootPath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (ignoreNames.contains(dir.getFileName().toString())) return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String name = file.getFileName().toString().toLowerCase();
                    int lastDot = name.lastIndexOf('.');
                    String ext = (lastDot == -1) ? "" : name.substring(lastDot + 1);

                    if (searchableExtensions.contains(ext)) {
                        Document doc = new Document();
                        doc.add(new StringField("path", rootPath.relativize(file).toString().replace("\\", "/"), Field.Store.YES));
                        doc.add(new TextField("content", new String(Files.readAllBytes(file), StandardCharsets.UTF_8), Field.Store.YES));
                        writer.addDocument(doc);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            writer.commit();
            return "索引刷新完成 (" + (System.currentTimeMillis() - start) + "ms)";
        } catch (IOException e) {
            return "刷新失败: " + e.getMessage();
        }
    }
}