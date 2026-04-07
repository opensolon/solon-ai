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

import com.aliyun.dashvector.DashVectorClient;
import com.aliyun.dashvector.DashVectorClientConfig;
import com.aliyun.dashvector.DashVectorCollection;
import com.aliyun.dashvector.models.Doc;
import com.aliyun.dashvector.models.DocOpResult;
import com.aliyun.dashvector.models.PartitionStats;
import com.aliyun.dashvector.models.Vector;
import com.aliyun.dashvector.models.requests.CreateCollectionRequest;
import com.aliyun.dashvector.models.requests.DeleteDocRequest;
import com.aliyun.dashvector.models.requests.FetchDocRequest;
import com.aliyun.dashvector.models.requests.QueryDocRequest;
import com.aliyun.dashvector.models.requests.UpsertDocRequest;
import com.aliyun.dashvector.models.responses.Response;
import com.aliyun.dashvector.proto.CollectionInfo;
import com.aliyun.dashvector.proto.FieldType;
import com.aliyun.dashvector.proto.Status;

import org.noear.solon.Utils;
import org.noear.solon.ai.embedding.EmbeddingModel;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.RepositoryLifecycle;
import org.noear.solon.ai.rag.RepositoryStorable;
import org.noear.solon.ai.rag.repository.aliyun.dashvector.FilterTransformer;
import org.noear.solon.ai.rag.repository.aliyun.dashvector.MetadataField;
import org.noear.solon.ai.rag.repository.aliyun.dashvector.util.DashVectorQueryCondition;
import org.noear.solon.ai.rag.repository.aliyun.dashvector.util.DocumentConverter;
import org.noear.solon.ai.rag.util.ListUtil;
import org.noear.solon.ai.rag.util.QueryCondition;
import org.noear.solon.ai.rag.util.SimilarityUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 阿里云向量数据库 DashVector（基于官方 dashvector-java-sdk 实现）
 *
 * <p>除了实现 {@link RepositoryStorable} 标准接口（高层 {@link Document} API），
 * 也通过 {@link #getClient()} / {@link #getCollection()} 暴露 SDK 入口，
 * 便于使用 partition、稀疏向量、多向量、ranker 等 DashVector 高级能力；
 * 配合 {@link DocumentConverter} 可在 {@link Document} 与 {@link Doc} 间互转。
 *
 * @author 烧饵块
 */
public class DashVectorSDKRepository implements RepositoryStorable, RepositoryLifecycle {
    private final Builder config;
    private DashVectorCollection collection;

    private DashVectorSDKRepository(Builder config) {
        this.config = config;

        try {
            initRepository();
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize DashVector repository: " + e.getMessage(), e);
        }
    }

    /**
     * 获取底层 DashVector 官方 SDK 客户端，便于直接使用 SDK 全部能力
     */
    public DashVectorClient getClient() {
        return config.client;
    }

    /**
     * 获取当前 DashVector 集合句柄，可直接调用 insert / upsert / query / fetch / delete 等 SDK 方法
     */
    public DashVectorCollection getCollection() {
        return collection;
    }

    /**
     * 获取当前集合名
     */
    public String getCollectionName() {
        return config.collectionName;
    }

    /**
     * 初始化仓库
     */
    @Override
    public void initRepository() throws IOException {
        if (collection != null) {
            return;
        }

        // 尝试获取已存在的集合
        Response<List<String>> listResp = config.client.list();
        boolean exists = listResp.isSuccess()
                && listResp.getOutput() != null
                && listResp.getOutput().contains(config.collectionName);

        if (!exists) {
            createNewCollection();
        }

        this.collection = config.client.get(config.collectionName);

        if (!this.collection.isSuccess()) {
            // 集合可能正处于 DROPPING 状态（list 能看到但 get 失败），
            // 等待服务端完成删除后重新创建
            for (int retry = 0; retry < 3; retry++) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for collection to be ready", e);
                }

                try {
                    createNewCollection();
                } catch (IOException ignore) {
                    // 仍在 DROPPING 中，继续等待
                    continue;
                }

                this.collection = config.client.get(config.collectionName);
                if (this.collection.isSuccess()) {
                    return;
                }
            }

            throw new IOException("Failed to get DashVector collection after retries: " + this.collection.getMessage());
        }
    }

    /**
     * 创建新集合
     */
    private void createNewCollection() throws IOException {
        Map<String, FieldType> fieldsSchema = new HashMap<>();
        fieldsSchema.put(DocumentConverter.CONTENT_FIELD_KEY, FieldType.STRING);
        fieldsSchema.put(DocumentConverter.URL_FIELD_KEY, FieldType.STRING);

        if (config.metadataFields != null) {
            for (MetadataField field : config.metadataFields) {
                fieldsSchema.put(field.getName(), field.getFieldType());
            }
        }

        CreateCollectionRequest.CreateCollectionRequestBuilder requestBuilder = CreateCollectionRequest.builder()
                .name(config.collectionName)
                .dimension(config.embeddingModel.dimensions())
                .dataType(config.dataType)
                .metric(config.metric);

        for (Map.Entry<String, FieldType> entry : fieldsSchema.entrySet()) {
            requestBuilder.filedSchema(entry.getKey(), entry.getValue());
        }

        // 量化策略
        if (config.quantizeType != null && !config.quantizeType.isEmpty()) {
            Map<String, String> extraParams = new HashMap<>();
            extraParams.put("quantize_type", config.quantizeType);
            requestBuilder.extraParams(extraParams);
        }

        Response<Void> resp = config.client.create(requestBuilder.build());
        if (!resp.isSuccess()) {
            throw new IOException("Failed to create DashVector collection: " + resp.getMessage());
        }
    }

    /**
     * 注销仓库（删除集合）
     */
    @Override
    public void dropRepository() throws IOException {
        if (this.collection != null) {
            Response<Void> resp = config.client.delete(config.collectionName);
            if (!resp.isSuccess()) {
                throw new IOException("Failed to drop DashVector collection: " + resp.getMessage());
            }
            this.collection = null;
        }
    }

    /**
     * 批量存储文档（支持更新），使用默认分区
     */
    @Override
    public void save(List<Document> documents, BiConsumer<Integer, Integer> progressCallback) throws IOException {
        save(documents, null, progressCallback);
    }

    /**
     * 批量存储文档（支持更新），指定分区
     *
     * @param documents        文档集
     * @param partition        分区名称（null 或空使用默认分区）
     * @param progressCallback 进度回调
     */
    public void save(List<Document> documents, String partition, BiConsumer<Integer, Integer> progressCallback) throws IOException {
        if (Utils.isEmpty(documents)) {
            if (progressCallback != null) {
                progressCallback.accept(0, 0);
            }
            return;
        }

        // 确保所有文档都有 ID
        for (Document doc : documents) {
            if (Utils.isEmpty(doc.getId())) {
                doc.id(Utils.uuid());
            }
        }

        // 分块处理
        List<List<Document>> batchList = ListUtil.partition(documents, config.embeddingModel.batchSize());
        int batchIndex = 0;
        for (List<Document> batch : batchList) {
            config.embeddingModel.embed(batch);
            upsertDocs(DocumentConverter.toDocs(batch), partition);

            if (progressCallback != null) {
                progressCallback.accept(++batchIndex, batchList.size());
            }
        }
    }

    /**
     * 直接以 SDK 的 {@link Doc} 形式批量 upsert，使用默认分区
     */
    public void upsertDocs(List<Doc> docs) throws IOException {
        upsertDocs(docs, null);
    }

    /**
     * 直接以 SDK 的 {@link Doc} 形式批量 upsert，指定分区
     *
     * @param docs      文档列表
     * @param partition 分区名称（null 或空使用默认分区）
     */
    public void upsertDocs(List<Doc> docs, String partition) throws IOException {
        if (docs == null || docs.isEmpty()) {
            return;
        }

        UpsertDocRequest request;
        if (!Utils.isEmpty(partition)) {
            request = UpsertDocRequest.builder().docs(docs).partition(partition).build();
        } else {
            request = UpsertDocRequest.builder().docs(docs).build();
        }

        Response<List<DocOpResult>> resp = collection.upsert(request);
        if (!resp.isSuccess()) {
            throw new IOException("Failed to upsert documents: " + resp.getMessage());
        }
    }

    /**
     * 删除指定 ID 的文档，使用默认分区
     */
    @Override
    public void deleteById(String... ids) throws IOException {
        deleteByIdInPartition(null, ids);
    }

    /**
     * 删除指定分区下、指定 ID 的文档
     *
     * @param partition 分区名称（null 或空使用默认分区）
     * @param ids       文档 ID 列表
     */
    public void deleteByIdInPartition(String partition, String... ids) throws IOException {
        if (Utils.isEmpty(ids)) {
            return;
        }

        DeleteDocRequest request;
        if (!Utils.isEmpty(partition)) {
            request = DeleteDocRequest.builder().ids(Arrays.asList(ids)).partition(partition).build();
        } else {
            request = DeleteDocRequest.builder().ids(Arrays.asList(ids)).build();
        }

        Response<List<DocOpResult>> resp = collection.delete(request);
        if (!resp.isSuccess()) {
            throw new IOException("Failed to delete documents: " + resp.getMessage());
        }
    }

    /**
     * 删除指定分区下的所有文档（会保留分区本身）
     *
     * @param partition 分区名称（null 或空使用默认分区）
     */
    public void deleteAll(String partition) throws IOException {
        DeleteDocRequest request;
        if (!Utils.isEmpty(partition)) {
            request = DeleteDocRequest.builder().deleteAll(true).partition(partition).build();
        } else {
            request = DeleteDocRequest.builder().deleteAll(true).build();
        }

        Response<List<DocOpResult>> resp = collection.delete(request);
        if (!resp.isSuccess()) {
            throw new IOException("Failed to delete all documents: " + resp.getMessage());
        }
    }

    /**
     * 检查文档是否存在，使用默认分区
     */
    @Override
    public boolean existsById(String id) throws IOException {
        return existsByIdInPartition(null, id);
    }

    /**
     * 检查指定分区下文档是否存在
     *
     * @param partition 分区名称（null 或空使用默认分区）
     * @param id        文档 ID
     */
    public boolean existsByIdInPartition(String partition, String id) throws IOException {
        if (Utils.isEmpty(id)) {
            return false;
        }

        FetchDocRequest request;
        if (!Utils.isEmpty(partition)) {
            request = FetchDocRequest.builder().id(id).partition(partition).build();
        } else {
            request = FetchDocRequest.builder().id(id).build();
        }

        Response<Map<String, Doc>> resp = collection.fetch(request);
        if (!resp.isSuccess()) {
            return false;
        }

        Map<String, Doc> output = resp.getOutput();
        return output != null && output.containsKey(id) && output.get(id) != null;
    }

    /**
     * 按 ID 获取文档，使用默认分区
     */
    public Document getById(String id) throws IOException {
        return getByIdInPartition(null, id);
    }

    /**
     * 按 ID 获取指定分区下的文档
     *
     * @param partition 分区名称（null 或空使用默认分区）
     * @param id        文档 ID
     */
    public Document getByIdInPartition(String partition, String id) throws IOException {
        if (Utils.isEmpty(id)) {
            return null;
        }

        FetchDocRequest request;
        if (!Utils.isEmpty(partition)) {
            request = FetchDocRequest.builder().id(id).partition(partition).build();
        } else {
            request = FetchDocRequest.builder().id(id).build();
        }

        Response<Map<String, Doc>> resp = collection.fetch(request);
        if (!resp.isSuccess() || resp.getOutput() == null) {
            return null;
        }

        Doc doc = resp.getOutput().get(id);
        return DocumentConverter.toDocument(doc);
    }

    /**
     * 搜索文档（支持传入 {@link DashVectorQueryCondition} 以使用 partition / outputFields / sparseVector / id 等扩展能力）
     */
    @Override
    public List<Document> search(QueryCondition condition) throws IOException {
        if (condition == null || condition.getQuery() == null) {
            return new ArrayList<>();
        }

        try {
            QueryDocRequest request = buildQueryRequest(condition);
            Response<List<Doc>> resp = collection.query(request);
            if (!resp.isSuccess()) {
                throw new IOException("Failed to query documents: " + resp.getMessage());
            }

            List<Document> result = DocumentConverter.toDocuments(resp.getOutput());
            return SimilarityUtil.refilter(result.stream(), condition);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to search documents: " + e.getMessage(), e);
        }
    }

    /**
     * 直接以 SDK 的 {@link Doc} 形式返回查询结果（不做归一化、二次过滤）
     */
    public List<Doc> searchAsDoc(QueryCondition condition) throws IOException {
        if (condition == null) {
            return new ArrayList<>();
        }

        try {
            QueryDocRequest request = buildQueryRequest(condition);
            Response<List<Doc>> resp = collection.query(request);
            if (!resp.isSuccess()) {
                throw new IOException("Failed to query documents: " + resp.getMessage());
            }
            return resp.getOutput() == null ? new ArrayList<>() : resp.getOutput();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to search documents: " + e.getMessage(), e);
        }
    }

    /**
     * 根据 {@link QueryCondition} / {@link DashVectorQueryCondition} 构造 SDK 的 {@link QueryDocRequest}
     */
    public QueryDocRequest buildQueryRequest(QueryCondition condition) throws IOException {
        QueryDocRequest.QueryDocRequestBuilder requestBuilder = QueryDocRequest.builder()
                .topk(condition.getLimit())
                .includeVector(false);

        // 处理 DashVector 专用扩展条件
        DashVectorQueryCondition dvCondition = (condition instanceof DashVectorQueryCondition)
                ? (DashVectorQueryCondition) condition
                : null;

        boolean useIdQuery = dvCondition != null && !Utils.isEmpty(dvCondition.getId());

        if (useIdQuery) {
            requestBuilder.id(dvCondition.getId());
        } else if (!Utils.isEmpty(condition.getQuery())) {
            float[] embedding = config.embeddingModel.embed(condition.getQuery());
            Vector queryVector = Vector.builder()
                    .value(DocumentConverter.floatArrayToList(embedding))
                    .build();
            requestBuilder.vector(queryVector);
        }

        // filter 表达式翻译
        String filter = FilterTransformer.getInstance().transform(condition.getFilterExpression());
        if (filter != null && !filter.isEmpty()) {
            requestBuilder.filter(filter);
        }

        if (dvCondition != null) {
            if (!Utils.isEmpty(dvCondition.getPartition())) {
                requestBuilder.partition(dvCondition.getPartition());
            }
            if (dvCondition.getOutputFields() != null && !dvCondition.getOutputFields().isEmpty()) {
                requestBuilder.outputFields(dvCondition.getOutputFields());
            }
            requestBuilder.includeVector(dvCondition.isIncludeVector());
            if (dvCondition.getSparseVector() != null && !dvCondition.getSparseVector().isEmpty()) {
                requestBuilder.sparseVector(dvCondition.getSparseVector());
            }
        }

        return requestBuilder.build();
    }

    // ====================================================================
    // Partition 管理（DashVector 特有能力，非 RepositoryStorable 接口）
    // ====================================================================

    /**
     * 创建分区
     */
    public void createPartition(String name) throws IOException {
        Response<Void> resp = collection.createPartition(name);
        if (!resp.isSuccess()) {
            throw new IOException("Failed to create partition: " + resp.getMessage());
        }
    }

    /**
     * 删除分区
     */
    public void deletePartition(String name) throws IOException {
        Response<Void> resp = collection.deletePartition(name);
        if (!resp.isSuccess()) {
            throw new IOException("Failed to delete partition: " + resp.getMessage());
        }
    }

    /**
     * 获取分区列表
     */
    public List<String> listPartitions() throws IOException {
        Response<List<String>> resp = collection.listPartitions();
        if (!resp.isSuccess()) {
            throw new IOException("Failed to list partitions: " + resp.getMessage());
        }
        return resp.getOutput() == null ? new ArrayList<>() : resp.getOutput();
    }

    /**
     * 描述分区状态
     */
    public Status describePartition(String name) throws IOException {
        Response<Status> resp = collection.describePartition(name);
        if (!resp.isSuccess()) {
            throw new IOException("Failed to describe partition: " + resp.getMessage());
        }
        return resp.getOutput();
    }

    /**
     * 获取分区统计
     */
    public PartitionStats statsPartition(String name) throws IOException {
        Response<PartitionStats> resp = collection.statsPartition(name);
        if (!resp.isSuccess()) {
            throw new IOException("Failed to stats partition: " + resp.getMessage());
        }
        return resp.getOutput();
    }

    // ====================================================================
    // Builder
    // ====================================================================

    /**
     * 创建 Builder（基于已有 {@link DashVectorClient}）
     */
    public static Builder builder(EmbeddingModel embeddingModel, DashVectorClient client) {
        return new Builder(embeddingModel, client);
    }

    /**
     * 创建 Builder（通过 apiKey 与 endpoint 自动构造 {@link DashVectorClient}）
     */
    public static Builder builder(EmbeddingModel embeddingModel, String apiKey, String endpoint) {
        DashVectorClientConfig clientConfig = DashVectorClientConfig.builder()
                .apiKey(apiKey)
                .endpoint(endpoint)
                .build();
        return new Builder(embeddingModel, new DashVectorClient(clientConfig));
    }

    public static class Builder {
        /**
         * 向量模型
         */
        private final EmbeddingModel embeddingModel;

        /**
         * DashVector 官方 SDK 客户端
         */
        private final DashVectorClient client;

        /**
         * 元数据字段
         */
        private List<MetadataField> metadataFields = new ArrayList<>();

        /**
         * 集合名称
         */
        private String collectionName = "solon_ai";

        /**
         * 距离度量方式
         */
        private CollectionInfo.Metric metric = CollectionInfo.Metric.cosine;

        /**
         * 向量数据类型
         */
        private CollectionInfo.DataType dataType = CollectionInfo.DataType.FLOAT;

        /**
         * 量化策略（如 "DT_VECTOR_INT8"）
         */
        private String quantizeType;

        private Builder(EmbeddingModel embeddingModel, DashVectorClient client) {
            this.embeddingModel = embeddingModel;
            this.client = client;
        }

        /**
         * 设置集合名称
         */
        public Builder collectionName(String collectionName) {
            if (collectionName.length() > 32) {
                throw new IllegalArgumentException("collection name too long, max 32 characters.");
            }
            this.collectionName = collectionName;
            return this;
        }

        /**
         * 设置元数据字段
         */
        public Builder metadataFields(List<MetadataField> metadataFields) {
            this.metadataFields = metadataFields;
            return this;
        }

        /**
         * 设置距离度量方式（默认 cosine）
         *
         * @param metric 支持 {@code cosine}、{@code euclidean}、{@code dotproduct}；
         *               当 metric 为 cosine 时，dataType 必须为 FLOAT
         */
        public Builder metric(CollectionInfo.Metric metric) {
            this.metric = metric;
            return this;
        }

        /**
         * 设置向量数据类型（默认 FLOAT）
         *
         * @param dataType 支持 {@code FLOAT}、{@code INT}
         */
        public Builder dataType(CollectionInfo.DataType dataType) {
            this.dataType = dataType;
            return this;
        }

        /**
         * 设置量化策略，将 float32 向量量化为更紧凑的格式以节省存储和加速检索</br>
         * 如果 Collection 设置为量化 int8 则这里必须设置量化方式，会自动完成量化步骤。
         *
         * @param quantizeType 目前支持 {@code "DT_VECTOR_INT8"}（将 float32 量化为 int8）
         * @see <a href="https://help.aliyun.com/zh/document_detail/2663745.html">向量动态量化</a>
         */
        public Builder quantizeType(String quantizeType) {
            this.quantizeType = quantizeType;
            return this;
        }

        public DashVectorSDKRepository build() {
            return new DashVectorSDKRepository(this);
        }
    }
}