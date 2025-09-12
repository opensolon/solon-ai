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
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import org.noear.snack.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.embedding.EmbeddingModel;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.RepositoryLifecycle;
import org.noear.solon.ai.rag.RepositoryStorable;
import org.noear.solon.ai.rag.repository.redis.FilterTransformer;
import org.noear.solon.ai.rag.repository.redis.MetadataField;
import org.noear.solon.ai.rag.util.ListUtil;
import org.noear.solon.ai.rag.util.QueryCondition;
import org.noear.solon.ai.rag.util.SimilarityUtil;

import redis.clients.jedis.PipelineBase;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.json.Path;
import redis.clients.jedis.search.FTCreateParams;
import redis.clients.jedis.search.IndexDataType;
import redis.clients.jedis.search.Query;
import redis.clients.jedis.search.SearchResult;
import redis.clients.jedis.search.schemafields.NumericField;
import redis.clients.jedis.search.schemafields.SchemaField;
import redis.clients.jedis.search.schemafields.TagField;
import redis.clients.jedis.search.schemafields.TextField;
import redis.clients.jedis.search.schemafields.VectorField;

/**
 * Redis 矢量存储知识库，基于 Redis Search 实现
 *
 * @author noear
 * @since 3.1
 */
public class RedisRepository implements RepositoryStorable, RepositoryLifecycle {
    public static final String BLOB = "BLOB";
    public static final String SCORE = "score";
    private static final String QUERY_FORMAT = "%s=>[KNN %s @%s $%s AS %s]";
    private static final String EMBEDDING_NAME = "embedding";
    private static final String VECTOR_TYPE = "FLOAT32";
    private static final String JSON_PATH = "$.";
    //配置
    private final Builder config;

    /**
     * 私有构造函数，通过 Builder 模式创建
     */
    private RedisRepository(Builder config) {
        this.config = config;
        initRepository();
    }

    /**
     * 初始化仓库
     */
    @Override
    public void initRepository() {
        try {
            config.client.ftInfo(config.indexName);
        } catch (Exception e) {
            try {
                int dim = config.embeddingModel.dimensions();

                // 向量字段配置
                Map<String, Object> vectorArgs = new HashMap<>();
                vectorArgs.put("TYPE", VECTOR_TYPE);
                vectorArgs.put("DIM", dim);
                vectorArgs.put("DISTANCE_METRIC", config.distanceMetric);

                List<SchemaField> fields = new ArrayList<>();
                fields.add(VectorField.builder()
                        .fieldName(JSON_PATH + EMBEDDING_NAME)
                        .as(EMBEDDING_NAME)
                        .algorithm(config.algorithm)
                        .attributes(vectorArgs)
                        .build());

                for (MetadataField metadataIndexField : config.metadataFields) {
                    fields.add(toSchemaField(metadataIndexField));
                }


                // 创建索引
                config.client.ftCreate(
                        config.indexName,
                        FTCreateParams.createParams()
                                .on(IndexDataType.JSON)
                                .prefix(config.keyPrefix),
                        fields
                );

            } catch (Exception err) {
                throw new RuntimeException(err);
            }
        }
    }

    private SchemaField toSchemaField(MetadataField field) {
        String fieldName = JSON_PATH + field.getName();
        switch (field.getFieldType()) {
            case NUMERIC:
                return NumericField.of(fieldName).as(field.getName());
            case TAG:
                return TagField.of(fieldName).as(field.getName());
            case TEXT:
                return TextField.of(fieldName).as(field.getName());
            default:
                throw new IllegalArgumentException(
                        MessageFormat.format("Field {0} has unsupported type {1}", field.getName(), field.getFieldType()));
        }
    }

    /**
     * 注销仓库
     */
    @Override
    public void dropRepository() {
        config.client.ftDropIndex(config.indexName);
        config.client.flushDB();
    }

    /**
     * 批量存储文档（支持更新）
     *
     * @param documents 待存储的文档列表
     * @throws IOException 如果存储过程中发生 IO 错误
     */
    @Override
    public void save(List<Document> documents, BiConsumer<Integer, Integer> progressCallback) throws IOException {
        if (Utils.isEmpty(documents)) {
            //回调进度
            if (progressCallback != null) {
                progressCallback.accept(0, 0);
            }
            return;
        }

        // 确保所有文档都有ID
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
            batchSaveDo(batch);

            //回调进度
            if (progressCallback != null) {
                progressCallback.accept(++batchIndex, batchList.size());
            }
        }
    }

    private void batchSaveDo(List<Document> batch) {
        PipelineBase pipeline = null;
        try {
            pipeline = config.client.pipelined();
            for (Document doc : batch) {
                String key = config.keyPrefix + doc.getId();

                // 存储为 JSON 格式，注意字段名称需要与索引定义匹配
                Map<String, Object> jsonDoc = new HashMap<>();
                jsonDoc.put("content", doc.getContent());
                jsonDoc.put("embedding", doc.getEmbedding());
                jsonDoc.put("metadata", doc.getMetadata());
                if (Utils.isNotEmpty(config.metadataFields)) {
                    jsonDoc.putAll(doc.getMetadata());
                }

                // 使用Jedis直接存储Map
                pipeline.jsonSet(key, Path.ROOT_PATH, jsonDoc);
            }
            pipeline.sync();
        } finally {
            if (pipeline != null) {
                pipeline.close();
            }
        }
    }

    /**
     * 删除指定 ID 的文档
     *
     * @param ids 文档 ID
     */
    @Override
    public void deleteById(String... ids) throws IOException {
        if (Utils.isEmpty(ids)) {
            return;
        }

        PipelineBase pipeline = null;
        try {
            pipeline = config.client.pipelined();
            for (String id : ids) {
                pipeline.del(config.keyPrefix + id);
            }
            pipeline.sync();
        } finally {
            if (pipeline != null) {
                pipeline.close();
            }
        }
    }

    @Override
    public boolean existsById(String id) throws IOException {
        return config.client.exists(config.keyPrefix + id);
    }

    /**
     * 搜索文档
     *
     * @param condition 搜索条件
     * @return 匹配的文档列表
     * @throws IOException 如果搜索过程中发生 IO 错误
     */
    @Override
    public List<Document> search(QueryCondition condition) throws IOException {
        float[] embedding = config.embeddingModel.embed(condition.getQuery());

        String filter = FilterTransformer.getInstance().transform(condition.getFilterExpression());

        // 构建查询，注意字段名称需要与索引定义匹配
        String queryString = String.format(QUERY_FORMAT, filter, condition.getLimit(), EMBEDDING_NAME,
                BLOB, SCORE);

        String[] returnFields = {JSON_PATH + config.contentFieldName, JSON_PATH + config.metadataFieldName, SCORE};

        try {
            // 创建向量查询对象
            Query query = new Query(queryString)
                    .addParam(BLOB, embedding)
                    .returnFields(returnFields)
                    .setSortBy(SCORE, true) // true表示升序，相似度越高分数越低
                    .limit(0, condition.getLimit())
                    .dialect(2);

            // 执行查询
            SearchResult result = config.client.ftSearch(config.indexName, query);

            // 过滤并转换结果
            return SimilarityUtil.refilter(result.getDocuments()
                            .stream()
                            .map(this::toDocument),
                    condition);
        } catch (Exception e) {
            throw new IOException("Error searching documents: " + e.getMessage(), e);
        }
    }


    private Document toDocument(redis.clients.jedis.search.Document jDoc) {
        String id = jDoc.getId().substring(config.keyPrefix.length());
        String content = jDoc.getString("$.content");
        String metadataStr = jDoc.getString("$.metadata");
        Map<String, Object> metadata = ONode.deserialize(metadataStr, Map.class);

        // 添加相似度分数到元数据
        double score = similarityScore(jDoc);
        return new Document(id, content, metadata, score);
    }

    private double similarityScore(redis.clients.jedis.search.Document jDoc) {
        return 1.0D - Double.parseDouble(jDoc.getString("score"));
    }


    /**
     * 创建 Redis 知识库
     *
     * @param embeddingModel 嵌入模型
     * @param client         Redis 客户端
     */
    public static Builder builder(EmbeddingModel embeddingModel, UnifiedJedis client) {
        return new Builder(embeddingModel, client);
    }

    /**
     * Builder 类用于链式构建 RedisRepository
     */
    public static class Builder {
        // 必需参数
        private final EmbeddingModel embeddingModel;
        private final UnifiedJedis client;

        // 可选参数，设置默认值
        private String indexName = "idx:solon-ai";
        private String keyPrefix = "doc:";
        private List<MetadataField> metadataFields = new ArrayList<>();
        private VectorField.VectorAlgorithm algorithm = VectorField.VectorAlgorithm.HNSW;
        private String distanceMetric = "COSINE";

        private String contentFieldName = "content";
        private String metadataFieldName = "metadata";

        /**
         * 构造器
         *
         * @param embeddingModel 嵌入模型
         * @param client         Redis 客户端
         */
        public Builder(EmbeddingModel embeddingModel, UnifiedJedis client) {
            this.embeddingModel = embeddingModel;
            this.client = client;
        }

        /**
         * 设置索引名称
         *
         * @param indexName 索引名称
         * @return Builder
         */
        public Builder indexName(String indexName) {
            this.indexName = indexName;
            return this;
        }

        /**
         * 设置键前缀
         *
         * @param keyPrefix 键前缀
         * @return Builder
         */
        public Builder keyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
            return this;
        }

        /**
         * @deprecated 3.2 {@link #metadataFields(List)}
         * */
        @Deprecated
        public Builder metadataIndexFields(List<MetadataField> metadataFields) {
            return metadataFields(metadataFields);
        }

        /**
         * 设置元数据索引字段
         *
         * @param metadataFields 元数据索引字段
         * @return Builder
         */
        public Builder metadataFields(List<MetadataField> metadataFields) {
            this.metadataFields = metadataFields;
            return this;
        }

        /**
         * 设置向量算法
         *
         * @param algorithm 向量算法
         * @return Builder
         */
        public Builder algorithm(VectorField.VectorAlgorithm algorithm) {
            this.algorithm = algorithm;
            return this;
        }

        /**
         * 设置距离度量方式
         *
         * @param distanceMetric 距离度量方式
         * @return Builder
         */
        public Builder distanceMetric(String distanceMetric) {
            this.distanceMetric = distanceMetric;
            return this;
        }


        /**
         * 构建 RedisRepository 实例
         *
         * @return RedisRepository
         */
        public RedisRepository build() {
            return new RedisRepository(this);
        }
    }
}