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

import org.noear.snack.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.embedding.EmbeddingModel;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.RepositoryLifecycle;
import org.noear.solon.ai.rag.RepositoryStorable;
import org.noear.solon.ai.rag.util.ListUtil;
import org.noear.solon.ai.rag.util.QueryCondition;
import org.noear.solon.ai.rag.util.SimilarityUtil;
import org.noear.solon.lang.Preview;
import redis.clients.jedis.PipelineBase;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.json.Path;
import redis.clients.jedis.search.FTCreateParams;
import redis.clients.jedis.search.IndexDataType;
import redis.clients.jedis.search.Query;
import redis.clients.jedis.search.SearchResult;
import redis.clients.jedis.search.schemafields.SchemaField;
import redis.clients.jedis.search.schemafields.VectorField;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Redis 矢量存储知识库，基于 Redis Search 实现
 *
 * @author noear
 * @since 3.1
 */
@Preview("3.1")
public class RedisRepository implements RepositoryStorable, RepositoryLifecycle {
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
     * 创建 Redis 知识库
     *
     * @param embeddingModel 嵌入模型
     * @param client         Redis 客户端
     */
    public RedisRepository(EmbeddingModel embeddingModel, UnifiedJedis client) {
        this(embeddingModel, client, "idx:solon-ai", "doc:");
    }

    /**
     * 创建 Redis 知识库
     *
     * @param embeddingModel 嵌入模型
     * @param client         Redis 客户端
     * @param indexName      索引名称
     * @param keyPrefix      键前缀
     */
    public RedisRepository(EmbeddingModel embeddingModel, UnifiedJedis client, String indexName, String keyPrefix) {
        this.embeddingModel = embeddingModel;
        this.client = client;
        this.indexName = indexName;
        this.keyPrefix = keyPrefix;

        initRepository();
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
                vectorArgs.put("TYPE", "FLOAT32");
                vectorArgs.put("DIM", dim);
                vectorArgs.put("DISTANCE_METRIC", "COSINE");

                SchemaField[] fields = new SchemaField[]{
                        VectorField.builder()
                                .fieldName("$.embedding")
                                .as("embedding")
                                .algorithm(VectorField.VectorAlgorithm.HNSW)
                                .attributes(vectorArgs)
                                .build()
                };

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
        if (Utils.isEmpty(documents)) {
            return;
        }

        for (List<Document> batch : ListUtil.partition(documents, 20)) {
            embeddingModel.embed(batch);
            PipelineBase pipeline = null;
            try {
                pipeline = client.pipelined();
                for (Document doc : batch) {
                    if (doc.getId() == null) {
                        doc.id(Utils.uuid());
                    }

                    String key = keyPrefix + doc.getId();

                    // 存储为 JSON 格式，注意字段名称需要与索引定义匹配
                    Map<String, Object> jsonDoc = new HashMap<>();
                    jsonDoc.put("content", doc.getContent());
                    jsonDoc.put("embedding", doc.getEmbedding());
                    jsonDoc.put("metadata", doc.getMetadata());

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

        // 构建查询，注意字段名称需要与索引定义匹配
        String queryString = "*=>[KNN " + condition.getLimit() + " @embedding $BLOB AS score]";
        String[] returnFields = {"$.content", "$.metadata", "score"};

        try {
            // 创建向量查询对象
            Query query = new Query(queryString)
                    .addParam("BLOB", queryDoc.getEmbedding())
                    .returnFields(returnFields)
                    .setSortBy("score", true) // true表示升序，相似度越高分数越低
                    .limit(0, condition.getLimit())
                    .dialect(2);

            // 执行查询
            SearchResult result = client.ftSearch(indexName, query);

            // 过滤并转换结果
            return SimilarityUtil.sorted(condition, result.getDocuments()
                    .stream()
                    .map(this::toDocument));
        } catch (Exception e) {
            throw new IOException("Error searching documents: " + e.getMessage(), e);
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
