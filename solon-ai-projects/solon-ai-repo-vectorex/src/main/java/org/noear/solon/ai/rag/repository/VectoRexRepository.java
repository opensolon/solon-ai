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
import org.noear.solon.ai.rag.util.QueryCondition;
import org.noear.solon.ai.rag.util.SimilarityUtil;

import java.io.IOException;
import java.util.*;

/**
 * VectoRex 矢量存储知识库
 *
 * @author noear
 * @since 3.1
 */
public class VectoRexRepository implements RepositoryStorable, RepositoryLifecycle {
    private Builder config;
    private static final String idName = "id";
    private static final String vectorName = "embedding";

    private VectoRexRepository(Builder config) {
        this.config = config;
    }

    @Override
    public void initRepository() throws Exception {
        List<ScalarField> scalarFields = new ArrayList();

        ScalarField id = ScalarField.builder().name(idName).isPrimaryKey(true).build();
        scalarFields.add(id);

        List<VectorFiled> vectorFileds = new ArrayList();
        VectorFiled vector = VectorFiled.builder().name(vectorName)
                .metricType(MetricType.FLOAT_CANBERRA_DISTANCE)
                .dimensions(3)
                .build();

        vectorFileds.add(vector);
        ServerResponse<Void> face = config.client.createCollection(VectoRexCollectionReq.builder().collectionName("face").scalarFields(scalarFields).vectorFileds(vectorFileds).build());

    }

    @Override
    public void dropRepository() throws Exception {
        ServerResponse<Void> response = config.client.delCollection(config.collectionName);

        if (response.isSuccess() == false) {
            throw new IOException(response.getMsg());
        }
    }

    @Override
    public void insert(List<Document> documents) throws IOException {
        for (Document doc : documents) {
            doc.id(Utils.uuid());
            doc.embedding(config.embeddingModel.embed(doc.getContent()));

            CollectionDataAddReq req = CollectionDataAddReq.builder()
                    .collectionName(config.collectionName)
                    .metadata(new HashMap<String, Object>() {{
                        put(idName, doc.getId());
                        put(vectorName, doc.getEmbedding());
                        put("content", doc.getContent());
                        put("metadata", doc.getMetadata());
                    }})
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
        queryBuilder.eq(idName, id);
        ServerResponse<List<VectorSearchResult>> response = config.client.queryCollectionData(queryBuilder);

        return response.isSuccess() && Utils.isNotEmpty(response.getData());
    }

    @Override
    public List<Document> search(QueryCondition condition) throws IOException {
        float[] embed = config.embeddingModel.embed(condition.getQuery());

        QueryBuilder queryBuilder = new FilterTransformer(config.collectionName)
                .transform(condition.getFilterExpression());

        queryBuilder.vector(vectorName, Arrays.asList(embed))
                .topK(condition.getLimit());

        ServerResponse<List<VectorSearchResult>> response = config.client.queryCollectionData(queryBuilder);

        if (response.isSuccess() == false) {
            throw new IOException(response.getMsg());
        }

        return SimilarityUtil.refilter(response.getData().stream().map(this::toDocument), condition);
    }

    private Document toDocument(VectorSearchResult rst) {
        return new Document(
                (String) rst.getData().getMetadata().get("id"),
                (String) rst.getData().getMetadata().get("content"),
                (Map<String, Object>) rst.getData().getMetadata().get("metadata"),
                rst.getScore());
    }

    public static Builder builder(EmbeddingModel embeddingModel, VectorRexClient client) {
        return new Builder(embeddingModel, client);
    }

    public static class Builder {
        private final EmbeddingModel embeddingModel;
        private final VectorRexClient client;
        private String collectionName = "solon_ai";

        public Builder(EmbeddingModel embeddingModel, VectorRexClient client) {
            this.embeddingModel = embeddingModel;
            this.client = client;
        }

        public Builder collectionName(String collectionName) {
            this.collectionName = collectionName;
            return this;
        }

        public VectoRexRepository build() {
            return new VectoRexRepository(this);
        }
    }
}