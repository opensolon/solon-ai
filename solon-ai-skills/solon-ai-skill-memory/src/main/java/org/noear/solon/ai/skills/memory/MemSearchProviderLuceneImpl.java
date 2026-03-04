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
package org.noear.solon.ai.skills.memory;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.noear.solon.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 Lucene 的本地化记忆搜索供应商 (线程安全优化版)
 *
 * @author noear
 * @since 3.9.4
 */
public class MemSearchProviderLuceneImpl implements MemSearchProvider, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(MemSearchProviderLuceneImpl.class);

    private final Directory directory;
    private final Analyzer analyzer;
    private final IndexWriter writer; // 保持单例 Writer，避免 LockObtainFailedException

    public MemSearchProviderLuceneImpl() throws IOException {
        this(new ByteBuffersDirectory());
    }

    public MemSearchProviderLuceneImpl(String path) throws IOException {
        this(FSDirectory.open(Paths.get(path)));
    }

    public MemSearchProviderLuceneImpl(Directory directory) throws IOException {
        this.directory = directory;
        this.analyzer = new StandardAnalyzer();
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        // 允许在异常关闭后自动恢复锁
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        this.writer = new IndexWriter(directory, config);
    }

    @Override
    public List<MemSearchResult> search(String userId, String query, int limit) {
        return queryInternal(userId, query, limit, false);
    }

    @Override
    public List<MemSearchResult> getHotMemories(String userId, int limit) {
        return queryInternal(userId, null, limit, true);
    }

    private List<MemSearchResult> queryInternal(String userId, String queryStr, int limit, boolean isHotMode) {
        List<MemSearchResult> results = new ArrayList<>();
        // 使用 DirectoryReader.open(writer) 可以实现 Near Real Time (NRT) 搜索
        try (IndexReader reader = DirectoryReader.open(writer)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            BooleanQuery.Builder mainQuery = new BooleanQuery.Builder();

            mainQuery.add(new TermQuery(new Term("user_id", userId)), BooleanClause.Occur.MUST);

            if (Utils.isNotEmpty(queryStr)) {
                QueryParser parser = new QueryParser("content", analyzer);
                mainQuery.add(parser.parse(queryStr), BooleanClause.Occur.MUST);
            }

            if (isHotMode) {
                mainQuery.add(IntPoint.newRangeQuery("importance", 5, Integer.MAX_VALUE), BooleanClause.Occur.MUST);
            }

            Sort sort = isHotMode ? new Sort(new SortField("importance", SortField.Type.INT, true)) : Sort.RELEVANCE;
            TopDocs topDocs = searcher.search(mainQuery.build(), limit, sort);

            for (ScoreDoc sd : topDocs.scoreDocs) {
                Document d = searcher.doc(sd.doc);
                IndexableField impField = d.getField("importance");
                int importance = (impField != null) ? impField.numericValue().intValue() : 0;

                results.add(new MemSearchResult(
                        d.get("mem_key"),
                        d.get("content"),
                        importance,
                        d.get("time")
                ));
            }
        } catch (Exception e) {
            log.error("Lucene search error", e);
        }
        return results;
    }

    @Override
    public void updateIndex(String userId, String key, String fact, int importance, String time) {
        try {
            String docId = userId + ":" + key;
            Document doc = new Document();
            doc.add(new StringField("id", docId, Field.Store.YES));
            doc.add(new StringField("user_id", userId, Field.Store.YES));
            doc.add(new StringField("mem_key", key, Field.Store.YES));
            doc.add(new TextField("content", fact, Field.Store.YES));
            doc.add(new IntPoint("importance", importance));
            doc.add(new StoredField("importance", importance));
            doc.add(new NumericDocValuesField("importance", importance));
            doc.add(new StringField("time", time, Field.Store.YES));

            writer.updateDocument(new Term("id", docId), doc);
            writer.commit(); // 显式提交
        } catch (IOException e) {
            log.error("Lucene update error", e);
        }
    }

    @Override
    public void removeIndex(String userId, String key) {
        try {
            writer.deleteDocuments(new Term("id", userId + ":" + key));
            writer.commit();
        } catch (IOException e) {
            log.error("Lucene delete error", e);
        }
    }

    @Override
    public void close() throws Exception {
        if (writer != null) writer.close();
        if (directory != null) directory.close();
    }
}