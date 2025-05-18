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

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.*;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.GetReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.SearchReq.SearchReqBuilder;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;

import org.noear.solon.Utils;
import org.noear.solon.ai.embedding.EmbeddingModel;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.RepositoryLifecycle;
import org.noear.solon.ai.rag.RepositoryStorable;
import org.noear.solon.ai.rag.repository.milvus.FilterTransformer;
import org.noear.solon.ai.rag.util.SimilarityUtil;
import org.noear.solon.ai.rag.util.ListUtil;
import org.noear.solon.ai.rag.util.QueryCondition;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.noear.solon.lang.Preview;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Milvus 矢量存储知识库
 *
 * @author linziguan
 * @since 3.1
 */
@Preview("3.1")
public class MilvusRepository implements RepositoryStorable, RepositoryLifecycle {
    private final Gson gson = new Gson();
    private final Builder config;

    private MilvusRepository(Builder config) {
        this.config = config;

        initRepository();
    }

    /**
     * 初始化仓库
     */
    @Override
    public void initRepository() {
        // 查询是否存在
        boolean exists = config.client.hasCollection(HasCollectionReq.builder()
                .collectionName(config.collectionName)
                .build());

        if (exists == false) {
            // 构建一个collection，用于存储document
            try {
                CreateCollectionReq.CollectionSchema schema = config.client.createSchema();
                schema.addField(AddFieldReq.builder()
                        .fieldName("id")
                        .dataType(DataType.VarChar)
                        .maxLength(64)
                        .isPrimaryKey(true)
                        .autoID(false)
                        .build());

                int dim = config.embeddingModel.dimensions();
                schema.addField(AddFieldReq.builder()
                        .fieldName("embedding")
                        .dataType(DataType.FloatVector)
                        .dimension(dim)
                        .build());
                schema.addField(AddFieldReq.builder()
                        .fieldName("content")
                        .dataType(DataType.VarChar)
                        .maxLength(65535)
                        .build());
                schema.addField(AddFieldReq.builder()
                        .fieldName("metadata")
                        .dataType(DataType.JSON)
                        .build());

                IndexParam indexParamForIdField = IndexParam.builder()
                        .fieldName("id")
                        .build();

                IndexParam indexParamForVectorField = IndexParam.builder()
                        .fieldName("embedding")
                        .indexName("embedding_index")
                        .indexType(IndexParam.IndexType.IVF_FLAT)
                        .metricType(IndexParam.MetricType.COSINE)
                        .extraParams(Collections.singletonMap("nlist", 128))
                        .build();

                List<IndexParam> indexParams = new ArrayList<>();
                indexParams.add(indexParamForIdField);
                indexParams.add(indexParamForVectorField);

                CreateCollectionReq customizedSetupReq1 = CreateCollectionReq.builder()
                        .collectionName(config.collectionName)
                        .collectionSchema(schema)
                        .indexParams(indexParams)
                        .build();

                config.client.createCollection(customizedSetupReq1);

                GetLoadStateReq customSetupLoadStateReq1 = GetLoadStateReq.builder()
                        .collectionName(config.collectionName)
                        .build();

                config.client.getLoadState(customSetupLoadStateReq1);
            } catch (Exception err) {
                throw new RuntimeException(err);
            }
        }
    }

    /**
     * 注销仓库
     */
    @Override
    public void dropRepository() {
        config.client.dropCollection(DropCollectionReq.builder()
                .collectionName(config.collectionName)
                .build());
    }

    @Override
    public void insert(List<Document> documents) throws IOException {
        if (Utils.isEmpty(documents)) {
            return;
        }

        // 分块处理
        for (List<Document> batch : ListUtil.partition(documents, config.embeddingModel.batchSize())) {
            // 批量embedding
            config.embeddingModel.embed(batch);

            // 转换成json存储
            List<JsonObject> docObjs = batch.stream().map(this::toJsonObject)
                    .collect(Collectors.toList());

            InsertReq insertReq = InsertReq.builder()
                    .collectionName(config.collectionName)
                    .data(docObjs)
                    .build();

            // 如果需要更新，请先移除再插入（即不支持更新）
            config.client.insert(insertReq);
        }
    }

    @Override
    public void delete(String... ids) throws IOException {
        config.client.delete(DeleteReq.builder()
                .collectionName(config.collectionName)
                .ids(Arrays.asList(ids))
                .build());
    }

    @Override
    public boolean exists(String id) throws IOException {
        return config.client.get(GetReq.builder()
                .collectionName(config.collectionName)
                .ids(Arrays.asList(id))
                .build()).getGetResults().size() > 0;

    }

    @Override
    public List<Document> search(QueryCondition condition) throws IOException {
        FloatVec queryVector = new FloatVec(config.embeddingModel.embed(condition.getQuery()));

        SearchReqBuilder builder = SearchReq.builder()
                .collectionName(config.collectionName)
                .data(Collections.singletonList(queryVector))
                .topK(condition.getLimit())
                .outputFields(Arrays.asList("content", "metadata"));

        if (condition.getFilterExpression() != null) {
            String filterEl = FilterTransformer.getInstance().transform(condition.getFilterExpression());
            if (Utils.isNotEmpty(filterEl)) {
                builder.filter(filterEl);
            }
        }

        SearchReq searchReq = builder.build();
        SearchResp searchResp = config.client.search(searchReq);

        Stream<Document> docs = searchResp.getSearchResults().stream()
                .flatMap(r -> r.stream())
                .map(this::toDocument);

        // 再次过滤下
        return SimilarityUtil.refilter(docs, condition);
    }

    // 文档转为 JsonObject
    private JsonObject toJsonObject(Document doc) {
        if (doc.getId() == null) {
            doc.id(Utils.uuid());
        }

        return gson.toJsonTree(doc).getAsJsonObject();
    }

    /**
     * 搜索结果转为文档
     */
    private Document toDocument(SearchResp.SearchResult result) {
        Map<String, Object> entity = result.getEntity();
        String content = (String) entity.get("content");
        JsonObject metadataJson = (JsonObject) entity.get("metadata");
        Map<String, Object> metadata = null;
        if (metadataJson != null) {
            metadata = gson.fromJson(metadataJson, Map.class);
        }
        float[] embedding = (float[]) entity.get("embedding");

        Document doc = new Document((String) result.getId(), content, metadata, result.getScore());
        return doc.embedding(embedding);
    }

    public static Builder builder(EmbeddingModel embeddingModel, MilvusClientV2 client) {
        return new Builder(embeddingModel, client);
    }

    public static class Builder {
        private final EmbeddingModel embeddingModel;
        private final MilvusClientV2 client;
        private String collectionName = "solon_ai";//不能用 -

        public Builder(EmbeddingModel embeddingModel, MilvusClientV2 client) {
            this.embeddingModel = embeddingModel;
            this.client = client;
        }

        public Builder collectionName(String collectionName) {
            this.collectionName = collectionName;
            return this;
        }

        public MilvusRepository build() {
            return new MilvusRepository(this);
        }
    }
}