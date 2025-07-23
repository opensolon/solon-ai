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

import io.github.javpower.vectorexclient.VectorRexClient;
import io.github.javpower.vectorexclient.builder.QueryBuilder;
import io.github.javpower.vectorexclient.entity.MetricType;
import io.github.javpower.vectorexclient.entity.ScalarField;
import io.github.javpower.vectorexclient.entity.VectorFiled;
import io.github.javpower.vectorexclient.req.CollectionDataAddReq;
import io.github.javpower.vectorexclient.req.CollectionDataDelReq;
import io.github.javpower.vectorexclient.req.VectoRexCollectionReq;
import io.github.javpower.vectorexclient.res.ServerResponse;
import io.github.javpower.vectorexclient.res.VectorSearchResult;
import org.noear.solon.Utils;
import org.noear.solon.ai.embedding.EmbeddingModel;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.RepositoryLifecycle;
import org.noear.solon.ai.rag.RepositoryStorable;
import org.noear.solon.ai.rag.repository.vectorex.FilterTransformer;
import org.noear.solon.ai.rag.repository.vectorex.MetadataField;
import org.noear.solon.ai.rag.util.ListUtil;
import org.noear.solon.ai.rag.util.QueryCondition;
import org.noear.solon.ai.rag.util.SimilarityUtil;

import java.io.IOException;
import java.util.*;
import java.util.function.BiConsumer;

/**
 * VectoRex 矢量存储知识库
 *
 * @author noear
 * @since 3.1
 */
public class VectoRexRepository implements RepositoryStorable, RepositoryLifecycle {
    private final Builder config;

    private VectoRexRepository(Builder config) {
        this.config = config;
    }

    @Override
    public void initRepository() throws Exception {
        List<ScalarField> scalarFields = new ArrayList<>();

        ScalarField id = ScalarField.builder().name(config.idFieldName).isPrimaryKey(true).build();
        scalarFields.add(id);
        if (Utils.isNotEmpty(config.metadataFields)) {
            for (MetadataField metadataField : config.metadataFields) {
                scalarFields.add(ScalarField.builder().name(metadataField.getName()).isPrimaryKey(false).build());
            }
        }

        List<VectorFiled> vectorFields = new ArrayList<>();
        VectorFiled vector = VectorFiled.builder().name(config.embeddingFieldName)
                .metricType(MetricType.FLOAT_COSINE_DISTANCE)
                .dimensions(config.embeddingModel.dimensions())
                .build();

        vectorFields.add(vector);
        ServerResponse<Void> response = config.client.createCollection(VectoRexCollectionReq.builder().collectionName(config.collectionName).scalarFields(scalarFields).vectorFileds(vectorFields).build());
        if (!response.isSuccess()){
            throw new IOException(response.getMsg());
        }
    }

    @Override
    public void dropRepository() throws Exception {
        ServerResponse<Void> response = config.client.delCollection(config.collectionName);

        if (response.isSuccess() == false) {
            throw new IOException(response.getMsg());
        }
    }

    @Override
    public void insert(List<Document> documents, BiConsumer<Integer, Integer> progressCallback) throws IOException {
        if (Utils.isEmpty(documents)) {
            //回调进度
            if (progressCallback != null) {
                progressCallback.accept(0, 0);
            }
            return;
        }

        // 分块处理
        List<List<Document>> batchList = ListUtil.partition(documents, config.embeddingModel.batchSize());
        int batchIndex = 0;
        for (List<Document> batch : batchList) {
            config.embeddingModel.embed(batch);
            batchInsertDo(batch);

            //回调进度
            if (progressCallback != null) {
                progressCallback.accept(++batchIndex, batchList.size());
            }
        }


    }

    private void batchInsertDo(List<Document> batch) throws IOException{
        for (Document doc : batch) {
            doc.id(Utils.uuid());

            Map<String, Object> map = new HashMap<>();
            map.put(config.idFieldName, doc.getId());
            map.put(config.embeddingFieldName, doc.getEmbedding());
            map.put(config.contentFieldName, doc.getContent());
            map.put(config.metadataFieldName, doc.getMetadata());

            if (Utils.isNotEmpty(config.metadataFields)) {
                for (MetadataField metadataField : config.metadataFields) {
                    map.put(metadataField.getName(), doc.getMetadata(metadataField.getName()));
                }
            }

            CollectionDataAddReq req = CollectionDataAddReq.builder()
                    .collectionName(config.collectionName)
                    .metadata(map)
                    .build();

            ServerResponse<Void> response = config.client.addCollectionData(req);

            if (response.isSuccess() == false) {
                throw new IOException(response.getMsg());
            }
        }
    }

    @Override
    public void delete(String... ids) throws IOException {
        for (String id : ids) {
            CollectionDataDelReq req = new CollectionDataDelReq(config.collectionName, id);
            ServerResponse<Void> response = config.client.deleteCollectionData(req);

            if (response.isSuccess() == false) {
                throw new IOException(response.getMsg());
            }
        }
    }

    @Override
    public boolean exists(String id) throws IOException {
        QueryBuilder queryBuilder = QueryBuilder.lambda(config.collectionName);
        queryBuilder.eq(config.idFieldName, id);
        ServerResponse<List<VectorSearchResult>> response = config.client.queryCollectionData(queryBuilder);
        return response.isSuccess() && Utils.isNotEmpty(response.getData());
    }

    @Override
    public List<Document> search(QueryCondition condition) throws IOException {
        float[] embed = config.embeddingModel.embed(condition.getQuery());
        List<Float> embedList = new ArrayList<>();
        for (float f : embed) {
            embedList.add(f);
        }
        QueryBuilder queryBuilder = new FilterTransformer(config.collectionName)
                .transform(condition.getFilterExpression());
        queryBuilder.vector(config.embeddingFieldName, embedList)
                .topK(condition.getLimit());

        ServerResponse<List<VectorSearchResult>> response = config.client.queryCollectionData(queryBuilder);

        if (response.isSuccess() == false) {
            throw new IOException(response.getMsg());
        }

        return SimilarityUtil.refilter(response.getData().stream().map(this::toDocument), condition);
    }

    private Document toDocument(VectorSearchResult rst) {
        double score = 1.0 - Math.min(1.0, Math.max(0.0, rst.getScore()));
        return new Document(
                (String) rst.getData().getMetadata().get(config.idFieldName),
                (String) rst.getData().getMetadata().get(config.contentFieldName),
                (Map<String, Object>) rst.getData().getMetadata().get(config.metadataFieldName),
                score);
    }

    public static Builder builder(EmbeddingModel embeddingModel, VectorRexClient client) {
        return new Builder(embeddingModel, client);
    }

    public static class Builder {
        private final EmbeddingModel embeddingModel;
        private final VectorRexClient client;
        private String collectionName = "solon_ai";

        private final String idFieldName = "id";
        private final String embeddingFieldName = "embedding";
        private final String contentFieldName = "content";
        private final String metadataFieldName = "metadata";
        /**
         * metadata索引类型
         */
        private List<MetadataField> metadataFields = new ArrayList<>();

        public Builder(EmbeddingModel embeddingModel, VectorRexClient client) {
            this.embeddingModel = embeddingModel;
            this.client = client;
        }

        public Builder collectionName(String collectionName) {
            this.collectionName = collectionName;
            return this;
        }

        public Builder metadataFields(List<MetadataField> metadataFields) {
            this.metadataFields = metadataFields;
            return this;
        }

        public VectoRexRepository build() {
            return new VectoRexRepository(this);
        }
    }
}
