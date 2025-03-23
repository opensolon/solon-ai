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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.noear.solon.Utils;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.RepositoryLifecycle;
import org.noear.solon.ai.rag.RepositoryStorable;
import org.noear.solon.ai.rag.repository.tcvectordb.FilterTransformer;
import org.noear.solon.ai.rag.repository.tcvectordb.MetadataField;
import org.noear.solon.ai.rag.util.QueryCondition;
import org.noear.solon.ai.rag.util.SimilarityUtil;
import org.noear.solon.lang.Preview;

import com.tencent.tcvectordb.client.VectorDBClient;
import com.tencent.tcvectordb.model.Collection;
import com.tencent.tcvectordb.model.Database;
import com.tencent.tcvectordb.model.DocField;
import com.tencent.tcvectordb.model.param.collection.CreateCollectionParam;
import com.tencent.tcvectordb.model.param.collection.Embedding;
import com.tencent.tcvectordb.model.param.collection.FieldType;
import com.tencent.tcvectordb.model.param.collection.FilterIndex;
import com.tencent.tcvectordb.model.param.collection.HNSWParams;
import com.tencent.tcvectordb.model.param.collection.IndexType;
import com.tencent.tcvectordb.model.param.collection.MetricType;
import com.tencent.tcvectordb.model.param.collection.ParamsSerializer;
import com.tencent.tcvectordb.model.param.collection.VectorIndex;
import com.tencent.tcvectordb.model.param.dml.DeleteParam;
import com.tencent.tcvectordb.model.param.dml.HNSWSearchParams;
import com.tencent.tcvectordb.model.param.dml.InsertParam;
import com.tencent.tcvectordb.model.param.dml.QueryParam;
import com.tencent.tcvectordb.model.param.dml.SearchByEmbeddingItemsParam;
import com.tencent.tcvectordb.model.param.entity.AffectRes;
import com.tencent.tcvectordb.model.param.entity.SearchRes;
import com.tencent.tcvectordb.model.param.enums.EmbeddingModelEnum;

/**
 * 腾讯云 VectorDB 矢量存储知识库
 *
 * @author 小奶奶花生米
 * @since 3.1
 */
@Preview("3.1")
public class TcVectorDbRepository implements RepositoryStorable, RepositoryLifecycle {
    /**
     * 文本字段名
     */
    public static final String TEXT_FIELD_NAME = "__text";

    /**
     * 向量字段名
     */
    public static final String VECTOR_FIELD_NAME = "vector";

    //构建配置
    private final Builder config;
    //集合对象
    private Collection collection;
    //是否已初始化
    private boolean initialized = false;

    /**
     * 私有构造函数，通过 Builder 创建实例
     *
     * @param config 配置
     */
    private TcVectorDbRepository(Builder config) {
        // 设置属性
        this.config = config;

        // 初始化仓库
        initRepository();
    }

    /**
     * 初始化仓库
     */
    @Override
    public void initRepository() {
        if (initialized) {
            return;
        }

        try {
            // 检查数据库是否存在
            List<String> databases = config.client.listDatabase();
            boolean databaseExists = databases.contains(config.databaseName);

            Database database;
            if (!databaseExists) {
                // 创建数据库
                database = config.client.createDatabase(config.databaseName);
            } else {
                // 获取现有数据库
                database = config.client.database(config.databaseName);
            }

            // 检查集合是否存在
            List<Collection> collections = database.listCollections();
            boolean collectionExists = collections.stream()
                    .anyMatch(c -> config.collectionName.equals(c.getCollection()));

            if (!collectionExists) {
                // 创建集合
                CreateCollectionParam.Builder collectionParamBuilder = CreateCollectionParam.newBuilder()
                        .withName(config.collectionName)
                        .withShardNum(config.shardNum)
                        .withReplicaNum(config.replicaNum)
                        .withDescription("Collection created by Solon AI")
                        .addField(new FilterIndex("id", FieldType.String, IndexType.PRIMARY_KEY));

                // 创建向量索引
                VectorIndex vectorIndex = getVectorIndex();

                collectionParamBuilder.addField(vectorIndex);

                // 添加元数据索引字段
                for (MetadataField field : config.metadataFields) {
                    FilterIndex filterIndex = new FilterIndex(
                            field.getName(),
                            field.getFieldType(),
                            IndexType.FILTER
                    );
                    collectionParamBuilder.addField(filterIndex);
                }

                // 添加嵌入配置
                collectionParamBuilder.withEmbedding(Embedding.newBuilder()
                        .withVectorField(VECTOR_FIELD_NAME)
                        .withField(TEXT_FIELD_NAME)
                        .withModelName(config.embeddingModel.getModelName())
                        .build());

                // 创建集合
                database.createCollection(collectionParamBuilder.build());
            }

            // 获取集合
            this.collection = database.describeCollection(config.collectionName);
            this.initialized = true;
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize VectorDB repository: " + e.getMessage(), e);
        }
    }

    @Override
    public void dropRepository() {
        // 检查数据库是否存在
        List<String> databases = config.client.listDatabase();
        boolean databaseExists = databases.contains(config.databaseName);

        Database database;
        if (!databaseExists) {
            // 创建数据库
            database = config.client.createDatabase(config.databaseName);
        } else {
            // 获取现有数据库
            database = config.client.database(config.databaseName);
        }

        database.dropCollection(config.collectionName);
        this.collection = null;
        this.initialized = false;
    }

    /**
     * 创建索引
     *
     * @return com.tencent.tcvectordb.model.param.collection.VectorIndex
     */
    private VectorIndex getVectorIndex() {
        VectorIndex vectorIndex;
        if (config.indexParams != null) {
            // 使用自定义参数
            vectorIndex = new VectorIndex(VECTOR_FIELD_NAME, config.embeddingModel.getDimension(),
                    config.indexType, config.metricType, config.indexParams);
        } else {
            // 对于其他索引类型
            vectorIndex = new VectorIndex(VECTOR_FIELD_NAME, config.embeddingModel.getDimension(),
                    config.indexType, config.metricType,
                    new HNSWParams(config.hnswM, config.hnswConstructionEf));
        }
        return vectorIndex;
    }

    /**
     * 批量存储文档
     *
     * @param documents 要存储的文档列表
     * @throws IOException 如果存储过程发生IO错误
     */
    @Override
    public void insert(List<Document> documents) throws IOException {
        if (documents == null || documents.isEmpty()) {
            return;
        }

        try {
            // 确保所有文档都有ID
            for (Document doc : documents) {
                if (Utils.isEmpty(doc.getId())) {
                    doc.id(Utils.uuid());
                }
            }

            // 准备上传到VectorDB的文档
            List<com.tencent.tcvectordb.model.Document> vectorDbDocs = new ArrayList<>();
            for (Document document : documents) {
                com.tencent.tcvectordb.model.Document.Builder builder = com.tencent.tcvectordb.model.Document.newBuilder()
                        .withId(document.getId())
                        .withDoc(document.getContent())
                        // 确保文档内容被设置到TEXT_FIELD_NAME字段
                        .addDocField(new DocField(TEXT_FIELD_NAME, document.getContent()));

                if (document.getMetadata() != null && !document.getMetadata().isEmpty()) {
                    for (Map.Entry<String, Object> entry : document.getMetadata().entrySet()) {
                        builder.addDocField(new DocField(entry.getKey(), entry.getValue()));
                    }
                }

                vectorDbDocs.add(builder.build());
            }

            // 插入文档
            InsertParam insertParam = InsertParam.newBuilder()
                    .addAllDocument(vectorDbDocs)
                    .withBuildIndex(true)
                    .build();
            AffectRes upsert = collection.upsert(insertParam);
            if (upsert.getCode() != 0) {
                throw new IOException("Failed to insert documents: " + upsert.getMsg());
            }

        } catch (Exception e) {
            throw new IOException("Failed to insert documents: " + e.getMessage(), e);
        }
    }

    /**
     * 删除文档
     *
     * @param ids 要删除的文档ID
     * @throws IOException 如果删除过程发生IO错误
     */
    @Override
    public void delete(String... ids) throws IOException {
        if (ids == null || ids.length == 0) {
            return;
        }

        try {
            // 准备删除参数
            DeleteParam deleteParam = DeleteParam.newBuilder()
                    .addAllDocumentId(Arrays.asList(ids))
                    .build();

            // 执行删除
            collection.delete(deleteParam);
        } catch (Exception e) {
            throw new IOException("Failed to delete documents: " + e.getMessage(), e);
        }
    }

    /**
     * 检查文档是否存在
     *
     * @param id 文档ID
     * @return 是否存在
     * @throws IOException 如果检查过程发生IO错误
     */
    @Override
    public boolean exists(String id) throws IOException {
        try {
            // 查询指定ID的文档
            QueryParam queryParam = QueryParam.newBuilder()
                    .withDocumentIds(Collections.singletonList(id))
                    .withLimit(1)
                    .build();

            List<com.tencent.tcvectordb.model.Document> documents = collection.query(queryParam);
            return documents != null && !documents.isEmpty();
        } catch (Exception e) {
            throw new IOException("Failed to check document existence: " + e.getMessage(), e);
        }
    }

    /**
     * 搜索文档
     *
     * @param condition 查询条件
     * @return 搜索结果
     * @throws IOException 如果搜索过程发生IO错误
     */
    @Override
    public List<Document> search(QueryCondition condition) throws IOException {
        if (condition == null) {
            throw new IllegalArgumentException("QueryCondition must not be null");
        }

        try {
            // 准备搜索参数
            SearchByEmbeddingItemsParam.Builder searchParamBuilder = SearchByEmbeddingItemsParam.newBuilder()
                    .withEmbeddingItems(Collections.singletonList(condition.getQuery()))
                    .withParams(new HNSWSearchParams(config.hnswSearchEf))
                    .withLimit(condition.getLimit() > 0 ? condition.getLimit() : 10);

            if (condition.getFilterExpression() != null) {
                searchParamBuilder.withFilter(FilterTransformer.getInstance().transform(condition.getFilterExpression()));
            }

            // 执行搜索
            SearchRes searchRes = collection.searchByEmbeddingItems(searchParamBuilder.build());

            // 解析搜索结果
            List<Document> result = getDocuments(searchRes);

            // 再次过滤和排序
            return SimilarityUtil.refilter(result.stream(), condition);
        } catch (Exception e) {
            throw new IOException("Failed to search documents: " + e.getMessage(), e);
        }
    }


    /**
     * 结果转换
     *
     * @param searchRes 搜索结果
     * @return java.util.List<org.noear.solon.ai.rag.Document>
     * @author 小奶奶花生米
     */
    private static List<Document> getDocuments(SearchRes searchRes) {
        List<Document> results = new ArrayList<>();
        if (searchRes.getDocuments() == null || searchRes.getDocuments().isEmpty()) {
            return results;
        }
        for (List<com.tencent.tcvectordb.model.Document> documents : searchRes.getDocuments()) {
            for (com.tencent.tcvectordb.model.Document doc : documents) {
                // 提取文档内容
                String content = doc.getDoc();
                // 使用toMetadata转换字段列表为元数据Map
                Map<String, Object> metadata = toMetadata(doc.getDocFields());
                // 创建文档
                Document document = new Document(
                        doc.getId(),
                        content,
                        metadata,
                        doc.getScore()
                );
                results.add(document);
            }
        }
        return results;
    }

    /**
     * 将DocField列表转换为元数据Map
     *
     * @param docFields DocField列表
     * @return 元数据Map
     */
    private static Map<String, Object> toMetadata(List<DocField> docFields) {
        Map<String, Object> metadata = new HashMap<>();
        if (docFields == null || docFields.isEmpty()) {
            return metadata;
        }

        for (DocField field : docFields) {
            // 跳过文本字段，因为它已经单独处理为文档内容
            if (!TEXT_FIELD_NAME.equals(field.getName())) {
                metadata.put(field.getName(), field.getValue());
            }
        }

        return metadata;
    }


    /// /////////////////////////////////////////////

    /**
     * 创建构建器
     *
     * @return 构建器
     */
    public static Builder builder(VectorDBClient client) {
        return new Builder(client);
    }

    /**
     * VectorDBRepository 构建器
     */
    public static class Builder {
        // 必要参数
        private final VectorDBClient client;
        private EmbeddingModelEnum embeddingModel = EmbeddingModelEnum.BGE_BASE_ZH;
        private String databaseName = "solon_ai_db";
        private String collectionName = "solon_ai";

        // 可选参数（使用默认值）
        /**
         * 分片数
         * 指定 Collection 的分片数。分片是把大数据集切成多个子数据集。
         * 取值范围：[1,100]。例如：5。
         * 配置建议：在搜索时，全部分片是并发执行的，分片数量越多，平均耗时越低，但是过多的分片会带来额外开销而影响性能。
         * 单分片数据量建议控制在300万以内，例如500万向量，可设置2个分片。
         * 如果数据量小于300万，建议使用1分片。系统对1分片有特定优化，可显著提升性能。
         */
        private int shardNum = 1;
        /**
         * 副本数
         * 指定 Collection 的副本数。副本数是指每个主分片有多个相同的备份，用来容灾和负载均衡。
         * 取值范围如下所示。搜索请求量越高的索引，建议设置越多的副本数，避免负载不均衡。
         * 单可用区实例：0。
         * 两可用区实例：[1,节点数-1]。
         * 三可用区实例：[2,节点数-1]。
         */
        private int replicaNum = 0;
        //默认相似度度量类型
        private MetricType metricType = MetricType.COSINE;
        //索引类型
        private IndexType indexType = IndexType.HNSW;
        private ParamsSerializer indexParams = null;
        private List<MetadataField> metadataFields = new ArrayList<>();
        //HNSW 图的每层节点的邻居数量
        private int hnswM = 16;
        //HNSW 图构搜索时的候选邻居数量
        private int hnswSearchEf = 500;
        //HNSW 图构建时的候选邻居数量
        private int hnswConstructionEf = 400;

        /**
         * 构造函数
         */
        public Builder(VectorDBClient client) {
            if (client == null) {
                throw new IllegalArgumentException("Client must not be null or empty");
            }

            this.client = client;
        }

        /**
         * 设置数据库名
         */
        public Builder databaseName(String databaseName) {
            if (Utils.isNotEmpty(databaseName)) {
                this.databaseName = databaseName;
            }
            return this;
        }

        /**
         * 设置集合名
         */
        public Builder collectionName(String collectionName) {
            if (Utils.isNotEmpty(collectionName)) {
                this.collectionName = collectionName;
            }
            return this;
        }

        /**
         * 设置向量模型
         */
        public Builder embeddingModel(EmbeddingModelEnum embeddingModel) {
            if (embeddingModel != null) {
                this.embeddingModel = embeddingModel;
            }
            return this;
        }

        /**
         * 设置分片数
         */
        public Builder shardNum(int shardNum) {
            this.shardNum = shardNum;
            return this;
        }

        /**
         * 设置副本数
         */
        public Builder replicaNum(int replicaNum) {
            this.replicaNum = replicaNum;
            return this;
        }

        /**
         * 设置相似度度量类型
         */
        public Builder metricType(MetricType metricType) {
            if (metricType != null) {
                this.metricType = metricType;
            }
            return this;
        }

        /**
         * 设置索引类型
         */
        public Builder indexType(IndexType indexType) {
            if (indexType != null) {
                this.indexType = indexType;
            }
            return this;
        }

        /**
         * 设置向量索引参数
         */
        public Builder indexParams(ParamsSerializer indexParams) {
            if (indexParams != null) {
                this.indexParams = indexParams;
            }
            return this;
        }

        /**
         * 设置 HNSW 图的每层节点的邻居数量
         */
        public Builder hnswM(int hnswM) {
            this.hnswM = hnswM;
            return this;
        }

        /**
         * 设置 HNSW 图构搜索时的候选邻居数量
         */
        public Builder hnswSearchEf(int hnswSearchEf) {
            this.hnswSearchEf = hnswSearchEf;
            return this;
        }

        /**
         * 设置 HNSW 图构建时的候选邻居数量
         */
        public Builder hnswConstructionEf(int hnswConstructionEf) {
            this.hnswConstructionEf = hnswConstructionEf;
            return this;
        }

        /**
         * 设置元数据索引字段
         */
        public Builder metadataFields(List<MetadataField> metadataFields) {
            this.metadataFields = metadataFields;
            return this;
        }

        /**
         * 添加单个元数据索引字段
         */
        public Builder addMetadataField(MetadataField metadataField) {
            this.metadataFields.add(metadataField);
            return this;
        }

        /**
         * 构建 VectorDBRepository
         *
         * @return VectorDBRepository 实例
         */
        public TcVectorDbRepository build() {
            return new TcVectorDbRepository(this);
        }
    }
}