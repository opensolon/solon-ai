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
        return "code_search_manager";
    }

    @Override
    public String description() {
        return "通过 Lucene 索引提供全文检索。在大项目中快速定位逻辑。支持后缀: " + searchableExtensions;
    }

    @Override
    public boolean isSupported(Prompt prompt) {
        return true;
    }

    @Override
    public String getInstruction(Prompt prompt) {
        return "#### 搜索策略 (Search Patterns)\n" +
                "- `code_search`: 全文检索。结果包含相关性评分。相比 grep，它能理解模糊意图。\n" +
                "- `refresh_index`: 当你新增了大量文件，导致搜索不到时，使用此工具。\n";
    }

    @ToolMapping(name = "code_search", description = "在项目中搜索代码逻辑。")
    public String codeSearch(@Param(value = "query", description = "搜索关键字") String query) {
        try (IndexReader reader = DirectoryReader.open(indexDirectory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            // 对输入进行转义，防止特殊符号导致 Lucene 解析报错
            QueryParser parser = new QueryParser("content", analyzer);
            Query q = parser.parse(QueryParser.escape(query));

            TopDocs docs = searcher.search(q, 20);
            if (docs.totalHits.value == 0) return "未找到匹配内容。";

            StringBuilder sb = new StringBuilder();
            sb.append("找到 ").append(docs.totalHits.value).append(" 个结果：\n\n");

            for (ScoreDoc sd : docs.scoreDocs) {
                Document d = searcher.doc(sd.doc);
                sb.append("📍 ").append(d.get("path")).append("\n");
                String content = d.get("content");
                // 简单的预览逻辑
                int idx = content.toLowerCase().indexOf(query.toLowerCase());
                if (idx != -1) {
                    int start = Math.max(0, idx - 60);
                    int end = Math.min(content.length(), idx + 120);
                    sb.append("预览: ...").append(content.substring(start, end).replace("\n", " ")).append("...\n");
                }
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "搜索失败: " + e.getMessage();
        }
    }

    @ToolMapping(name = "refresh_index", description = "刷新全文索引。")
    public String refreshIndex() {
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