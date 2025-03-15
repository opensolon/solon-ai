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
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.noear.snack.ONode;
import org.noear.solon.ai.embedding.EmbeddingModel;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.RepositoryLifecycle;
import org.noear.solon.ai.rag.RepositoryStorable;
import org.noear.solon.ai.rag.util.ListUtil;
import org.noear.solon.ai.rag.util.QueryCondition;
import org.noear.solon.ai.rag.util.SimilarityUtil;

import org.noear.solon.expression.Expression;
import org.noear.solon.expression.snel.*;
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
    private static final String CONTENT = "content";
    private static final String METADATA = "metadata";
    public static final String BLOB = "BLOB";
    public static final String SCORE = "score";
    private static final String QUERY_FORMAT = "%s=>[KNN %s @%s $%s AS %s]";
    private static final String EMBEDDING_NAME = "embedding";
    private static final String VECTOR_TYPE = "FLOAT32";
    private static final String JSON_PATH = "$.";
    /**
     * 嵌入模型，用于生成文档的向量表示
     */
    private final EmbeddingModel embeddingModel;
    /**
     * Redis 客户端
     */
    private final UnifiedJedis client;
    /**
     * 索引名称
     */
    private final String indexName;
    /**
     * 键前缀
     */
    private final String keyPrefix;
    /**
     * metadata用于加索引的值
     */
    private final List<MetadataField> metadataIndexFields;

    /**
     * 向量算法
     */
    private final VectorField.VectorAlgorithm algorithm;

    /**
     * 距离度量方式
     */
    private final String distanceMetric;


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
     * 私有构造函数，通过 Builder 模式创建
     */
    private RedisRepository(Builder builder) {
        this.embeddingModel = builder.embeddingModel;
        this.client = builder.client;
        this.indexName = builder.indexName;
        this.keyPrefix = builder.keyPrefix;
        this.metadataIndexFields = builder.metadataIndexFields;
        this.algorithm = builder.algorithm;
        this.distanceMetric = builder.distanceMetric;
        initRepository();
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
        private List<MetadataField> metadataIndexFields = new ArrayList<>();
        private VectorField.VectorAlgorithm algorithm = VectorField.VectorAlgorithm.HNSW;
        private String distanceMetric = "COSINE";

        /**
         * 构造器
         *
         * @param embeddingModel 嵌入模型
         * @param client Redis 客户端
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
         * 设置元数据索引字段
         *
         * @param metadataIndexFields 元数据索引字段
         * @return Builder
         */
        public Builder metadataIndexFields(List<MetadataField> metadataIndexFields) {
            this.metadataIndexFields = metadataIndexFields;
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

    /**
     * 初始化仓库
     */
    @Override
    public void initRepository() {
        try {
            client.ftInfo(indexName);
        } catch (Exception e) {
            try {
                int dim = embeddingModel.dimensions();

                // 向量字段配置
                Map<String, Object> vectorArgs = new HashMap<>();
                vectorArgs.put("TYPE", VECTOR_TYPE);
                vectorArgs.put("DIM", dim);
                vectorArgs.put("DISTANCE_METRIC", distanceMetric);

                List<SchemaField> fields = new ArrayList<>();
                fields.add( VectorField.builder()
                        .fieldName(JSON_PATH+EMBEDDING_NAME)
                        .as(EMBEDDING_NAME)
                        .algorithm(algorithm)
                        .attributes(vectorArgs)
                        .build());

                for (MetadataField metadataIndexField : metadataIndexFields) {
                    fields.add(toSchemaField(metadataIndexField));
                }


                // 创建索引
                client.ftCreate(
                        indexName,
                        FTCreateParams.createParams()
                                .on(IndexDataType.JSON)
                                .prefix(keyPrefix),
                        fields
                );

            } catch (Exception err) {
                throw new RuntimeException(err);
            }
        }
    }

    private SchemaField toSchemaField(MetadataField field) {
        String fieldName = JSON_PATH+field.getName();
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
        client.ftDropIndex(indexName);
        client.flushDB();
    }

    /**
     * 存储文档列表
     *
     * @param documents 待存储的文档列表
     * @throws IOException 如果存储过程中发生 IO 错误
     */
    @Override
    public void insert(List<Document> documents) throws IOException {
        if (documents == null || documents.isEmpty()) {
            return;
        }

        for (List<Document> batch : ListUtil.partition(documents, 20)) {
            embeddingModel.embed(batch);
            PipelineBase pipeline = null;
            try {
                pipeline = client.pipelined();
                for (Document doc : batch) {
                    if (doc.getId() == null) {
                        doc.id(UUID.randomUUID().toString());
                    }

                    String key = keyPrefix + doc.getId();

                    // 存储为 JSON 格式，注意字段名称需要与索引定义匹配
                    Map<String, Object> jsonDoc = new HashMap<>();
                    jsonDoc.put("content", doc.getContent());
                    jsonDoc.put("embedding", doc.getEmbedding());
                    jsonDoc.put("metadata", doc.getMetadata());
                    if (!this.metadataIndexFields.isEmpty()) {
                        jsonDoc.putAll(doc.getMetadata());
                    }

                    // 使用Jedis直接存储Map
                    pipeline.jsonSet(key, Path.ROOT_PATH, jsonDoc);
                }
                pipeline.sync();
            } catch (Exception e) {
                throw new IOException("Error storing documents: " + e.getMessage(), e);
            } finally {
                if (pipeline != null) {
                    pipeline.close();
                }
            }
        }
    }

    /**
     * 删除指定 ID 的文档
     *
     * @param ids 文档 ID
     */
    @Override
    public void delete(String... ids) throws IOException {
        if (ids == null || ids.length == 0) {
            return;
        }

        PipelineBase pipeline = null;
        try {
            pipeline = client.pipelined();
            for (String id : ids) {
                pipeline.del(keyPrefix + id);
            }
            pipeline.sync();
        } finally {
            if (pipeline != null) {
                pipeline.close();
            }
        }
    }

    @Override
    public boolean exists(String id) throws IOException {
        return client.exists(keyPrefix + id);
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
        Document queryDoc = new Document(condition.getQuery(), new HashMap<>());
        List<Document> documents = new ArrayList<>();
        documents.add(queryDoc);
        embeddingModel.embed(documents);

        String filter = getExpressionFilter(condition);

        // 构建查询，注意字段名称需要与索引定义匹配
        String queryString = String.format(QUERY_FORMAT, filter, condition.getLimit(), EMBEDDING_NAME,
                BLOB, SCORE);

        String[] returnFields = {JSON_PATH+CONTENT, JSON_PATH+METADATA, SCORE};

        try {
            // 创建向量查询对象
            Query query = new Query(queryString)
                    .addParam(BLOB, queryDoc.getEmbedding())
                    .returnFields(returnFields)
                    .setSortBy(SCORE, true) // true表示升序，相似度越高分数越低
                    .limit(0, condition.getLimit())
                    .dialect(2);

            // 执行查询
            SearchResult result = client.ftSearch(indexName, query);

            // 过滤并转换结果
            return SimilarityUtil.filter(condition, result.getDocuments()
                    .stream()
                    .map(this::toDocument));
        } catch (Exception e) {
            throw new IOException("Error searching documents: " + e.getMessage(), e);
        }
    }

    private String getExpressionFilter(QueryCondition condition) {
        if (condition.getFilterExpression() == null) {
            return "*";
        }

        try {
            Expression<Boolean> filterExpression = condition.getFilterExpression();
            StringBuilder buf = new StringBuilder();
            parseFilterExpression(filterExpression, buf);

            if (buf.length() == 0) {
                return "*";
            }

            return buf.toString();
        } catch (Exception e) {
            System.err.println("Error processing filter expression: " + e.getMessage());
            return "*";
        }
    }

   /**
    *  解析QueryCondition中的filterExpression，转换为Redis Search语法
    * @param filterExpression
    * @param buf
    */
    private void parseFilterExpression(Expression<Boolean> filterExpression, StringBuilder buf) {
        if (filterExpression == null) {
            return;
        }

        if (filterExpression instanceof VariableNode) {
            // 变量节点，获取字段名 - 为Redis添加@前缀
            String name = ((VariableNode) filterExpression).getName();
            buf.append("@").append(name);
        } else if (filterExpression instanceof ConstantNode) {
            ConstantNode node = (ConstantNode) filterExpression;
            // 常量节点，获取值
            Object value = node.getValue();

            if (node.isCollection()) {
                // 集合使用Redis的OR语法 {val1|val2|val3}
                buf.append("{");
                boolean first = true;
                for (Object item : (Collection<?>) value) {
                    if (!first) {
                        buf.append("|"); // Redis 使用 | 分隔OR条件
                    }
                    buf.append(item);
                    first = false;
                }
                buf.append("}");
            } else if (value instanceof String) {
                // 字符串值使用大括号
                buf.append("{").append(value).append("}");
            } else {
                buf.append(value);
            }
        } else if (filterExpression instanceof ComparisonNode) {
            ComparisonNode node = (ComparisonNode) filterExpression;
            ComparisonOp operator = node.getOperator();
            Expression left = node.getLeft();
            Expression right = node.getRight();

            // 比较节点
            if (ComparisonOp.eq.equals(operator)) {
                parseFilterExpression(left, buf);
                buf.append(":");
                parseFilterExpression(right, buf);
            } else if (ComparisonOp.neq.equals(operator)) {
                buf.append("-");
                parseFilterExpression(left, buf);
                buf.append(":");
                parseFilterExpression(right, buf);
            } else if (ComparisonOp.gt.equals(operator)) {
                parseFilterExpression(left, buf);
                buf.append(":[");
                parseFilterExpression(right, buf);
                buf.append(" +inf]");
            } else if (ComparisonOp.gte.equals(operator)) {
                parseFilterExpression(left, buf);
                buf.append(":[");
                parseFilterExpression(right, buf);
                buf.append(" +inf]");
            } else if (ComparisonOp.lt.equals(operator)) {
                parseFilterExpression(left, buf);
                buf.append(":[-inf ");
                parseFilterExpression(right, buf);
                buf.append("]");
            } else if (ComparisonOp.lte.equals(operator)) {
                parseFilterExpression(left, buf);
                buf.append(":[-inf ");
                parseFilterExpression(right, buf);
                buf.append("]");
            } else if (ComparisonOp.in.equals(operator)) {
                parseFilterExpression(left, buf);
                buf.append(":");
                parseFilterExpression(right, buf);
            } else if (ComparisonOp.nin.equals(operator)) {
                buf.append("-");
                parseFilterExpression(left, buf);
                buf.append(":");
                parseFilterExpression(right, buf);
            } else {
                parseFilterExpression(left, buf);
                buf.append(":");
                parseFilterExpression(right, buf);
            }
        }
    }

    private Document toDocument(redis.clients.jedis.search.Document jDoc) {
        String id = jDoc.getId().substring(keyPrefix.length());
        String content = jDoc.getString("$.content");
        String metadataStr = jDoc.getString("$.metadata");
        Map<String, Object> metadata = ONode.deserialize(metadataStr, Map.class);

        // 添加相似度分数到元数据
        double similarity = similarityScore(jDoc);
        metadata.put("score", similarity);
        metadata.put("distance", similarity); // 添加距离信息，与Spring AI保持一致

        return new Document(id, content, metadata, similarity);
    }

    private double similarityScore(redis.clients.jedis.search.Document jDoc) {
        return 1.0D - Double.parseDouble(jDoc.getString("score"));
    }
}
