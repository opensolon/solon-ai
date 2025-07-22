/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.rag.repository;

import org.noear.solon.Utils;
import org.noear.solon.ai.embedding.EmbeddingModel;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.RepositoryLifecycle;
import org.noear.solon.ai.rag.RepositoryStorable;
import org.noear.solon.ai.rag.util.ListUtil;
import org.noear.solon.ai.rag.util.QueryCondition;
import org.noear.solon.ai.rag.util.SimilarityUtil;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * 内存存储知识库
 *
 * @author noear
 * @since 3.1
 */
public class InMemoryRepository implements RepositoryStorable, RepositoryLifecycle {
    private final EmbeddingModel embeddingModel;
    private final Map<String, Document> store = new ConcurrentHashMap<>();

    public InMemoryRepository(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @Override
    public void insert(List<Document> documents, BiConsumer<Integer, Integer> progressCallback) throws IOException {
        if (Utils.isEmpty(documents)) {
            //回调进度
            progressCallback.accept(0, 0);
            return;
        }

        // 分块处理
        List<List<Document>> batchList = ListUtil.partition(documents, embeddingModel.batchSize());
        int batchIndex = 0;
        for (List<Document> batch : batchList) {
            embeddingModel.embed(batch);
            batchInsertDo(batch);

            //回调进度
            progressCallback.accept(batchIndex++, batchList.size());
        }
    }

    private void batchInsertDo(List<Document> batch) {
        for (Document doc : batch) {
            if (Utils.isEmpty(doc.getId())) {
                doc.id(Utils.uuid());
            }

            store.put(doc.getId(), doc);
        }
    }

    @Override
    public void initRepository() {

    }

    @Override
    public void dropRepository() {
        store.clear();
    }

    @Override
    public void delete(String... ids) {
        for (String id : ids) {
            store.remove(id);
        }
    }

    @Override
    public boolean exists(String id) {
        return store.containsKey(id);
    }

    @Override
    public List<Document> search(QueryCondition condition) throws IOException {
        float[] queryEmbed = embeddingModel.embed(condition.getQuery());


        return SimilarityUtil.refilter(store.values().stream()
                        .map(doc -> SimilarityUtil.copyAndScore(doc, queryEmbed)),
                condition);
    }
}