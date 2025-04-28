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

import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentFactory;
import org.noear.snack.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.embedding.EmbeddingModel;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.RepositoryLifecycle;
import org.noear.solon.ai.rag.RepositoryStorable;
import org.noear.solon.ai.rag.repository.elasticsearch.FilterTransformer;
import org.noear.solon.ai.rag.repository.elasticsearch.MetadataField;
import org.noear.solon.ai.rag.util.ListUtil;
import org.noear.solon.ai.rag.util.QueryCondition;
import org.noear.solon.core.util.IoUtil;
import org.noear.solon.lang.Preview;

import java.io.IOException;
import java.util.*;

/**
 * Elasticsearch 矢量存储知识库
 *
 * @author 小奶奶花生米
 * @since 3.1
 */
@Preview("3.1")
public class ElasticsearchRepository implements RepositoryStorable, RepositoryLifecycle {
    private final Builder config;

    private ElasticsearchRepository(Builder config) {
        this.config = config;
        initRepository();
    }

    /**
     * 初始化仓库
     */
    @Override
    public void initRepository() {
        try {
            GetIndexRequest getIndexRequest = new GetIndexRequest(config.indexName);
            if (!config.client.indices().exists(getIndexRequest, RequestOptions.DEFAULT)) {
                CreateIndexRequest createIndexRequest = new CreateIndexRequest(config.indexName);
                createIndexRequest.source(buildIndexMapping());
                config.client.indices().create(createIndexRequest, RequestOptions.DEFAULT);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize Elasticsearch index", e);
        }
    }

    /**
     * 构建索引的映射配置
     * 定义文档字段的类型和属性
     *
     * @return 索引映射的XContent构建器
     * @throws IOException 如果构建过程发生IO错误
     */
    private XContentBuilder buildIndexMapping() throws IOException {
        int dims = config.embeddingModel.dimensions();

        XContentBuilder builder = XContentFactory.jsonBuilder()
                .startObject()
                .startObject("mappings")
                .startObject("properties")
                .startObject("content")
                .field("type", "text")
                .endObject()
                .startObject("metadata")
                .field("type", "object")
                .endObject()
                .startObject("embedding")
                .field("type", "dense_vector")
                .field("dims", dims)
                .field("index",true)
                .field("similarity","cosine")
                .endObject();

        // 如果有元数据字段需要索引，将其平铺到顶层
        if (Utils.isNotEmpty(config.metadataFields)) {
            for (MetadataField field : config.metadataFields) {
                builder.startObject(field.getName());
                switch (field.getFieldType()) {
                    case NUMERIC:
                        builder.field("type", "float");
                        break;
                    case TEXT:
                        builder.field("type", "text");
                        break;
                    case BOOLEAN:
                        builder.field("type", "boolean");
                        break;
                    case DATE:
                        builder.field("type", "date");
                        break;
                    default:
                        builder.field("type", "keyword");
                }
                builder.endObject();
            }
        }

        builder.endObject() // 结束 properties
                .endObject() // 结束 mappings
                .endObject(); // 结束根对象

        return builder;
    }

    /**
     * 注销仓库
     */
    @Override
    public void dropRepository() throws IOException {
        DeleteIndexRequest request = new DeleteIndexRequest(config.indexName);
        config.client.indices().delete(request, RequestOptions.DEFAULT);
    }


    /**
     * 搜索文档
     * 支持向量相似度搜索和元数据过滤
     *
     * @param condition 查询条件，包含查询文本、过滤器等
     * @return 匹配的文档列表
     * @throws IOException 如果搜索过程中发生IO错误
     * @author 小奶奶花生米
     */
    @Override
    public List<Document> search(QueryCondition condition) throws IOException {
        if (condition.getQuery() == null || condition.getQuery().isEmpty()) {
            throw new IllegalArgumentException("Query text cannot be empty for vector search");
        }

        // 生成查询向量
        Document queryDoc = new Document(condition.getQuery());
        config.embeddingModel.embed(Collections.singletonList(queryDoc));
        float[] queryVector = queryDoc.getEmbedding();

        String responseBody = executeSearch(condition, queryVector);
        return parseSearchResponse(responseBody);
    }

    /**
     * 执行搜索请求
     */
    private String executeSearch(QueryCondition condition, float[] queryVector) throws IOException {
        Request request = new Request("POST", "/" + config.indexName + "/_search");

        Map<String, Object> requestBody = new HashMap<>();
        Map<String, Object> query = translate(condition, queryVector);
        requestBody.put("query", query);
        requestBody.put("size", condition.getLimit() > 0 ? condition.getLimit() : 10);

        // 将min_score作为顶层参数添加到请求体中
        if (condition.getSimilarityThreshold() > 0) {
            requestBody.put("min_score", condition.getSimilarityThreshold());
        }

        request.setJsonEntity(ONode.stringify(requestBody));
        org.elasticsearch.client.Response response = config.client.getLowLevelClient().performRequest(request);
        return IoUtil.transferToString(response.getEntity().getContent(), "UTF-8");
    }

    /**
     * 解析搜索响应
     *
     * @param responseBody 响应JSON字符串
     * @return 文档列表
     */
    private List<Document> parseSearchResponse(String responseBody) {
        Map<String, Object> responseMap = ONode.loadStr(responseBody).toObject(Map.class);
        List<Document> results = new ArrayList<>();

        Map<String, Object> hits = (Map<String, Object>) responseMap.get("hits");
        List<Map<String, Object>> hitsList = (List<Map<String, Object>>) hits.get("hits");

        for (Map<String, Object> hit : hitsList) {
            results.add(createDocumentFromHit(hit));
        }

        return results;
    }

    /**
     * 从搜索结果创建文档对象
     *
     * @param hit 搜索结果项
     * @return 文档对象
     */
    private Document createDocumentFromHit(Map<String, Object> hit) {
        Map<String, Object> source = (Map<String, Object>) hit.get("_source");
        Document doc = new Document(
                (String) hit.get("_id"),
                (String) source.get("content"),
                (Map<String, Object>) source.get("metadata"),
                (Double) hit.get("_score"));
        doc.url((String) source.get("url"));
        return doc;
    }

    /**
     * 执行批量请求
     *
     * @param bulkBody 批量操作的JSON字符串
     * @throws IOException 如果执行过程发生IO错误
     */
    private void executeBulkRequest(String bulkBody) throws IOException {
        Request request = new Request("POST", "/_bulk");
        request.setJsonEntity(bulkBody);
        config.client.getLowLevelClient().performRequest(request);
    }

    /**
     * 刷新索引
     * 确保最近的更改对搜索可见
     *
     * @throws IOException 如果刷新过程发生IO错误
     */
    private void refreshIndex() throws IOException {
        Request request = new Request("POST", "/" + config.indexName + "/_refresh");
        config.client.getLowLevelClient().performRequest(request);
    }

    /**
     * 批量存储文档
     * 将文档内容转换为向量并存储到ES中
     *
     * @param documents 要存储的文档列表
     * @throws IOException 如果存储过程中发生IO错误
     * @author 小奶奶花生米
     */
    @Override
    public void insert(List<Document> documents) throws IOException {
        if (Utils.isEmpty(documents)) {
            return;
        }

        // 分批处理
        for (List<Document> batch : ListUtil.partition(documents, config.embeddingModel.batchSize())) {
            config.embeddingModel.embed(batch);

            StringBuilder buf = new StringBuilder();
            for (Document doc : batch) {
                insertBuild(buf, doc);
            }

            executeBulkRequest(buf.toString());
            refreshIndex();
        }
    }


    /**
     * 将文档添加到批量索引操作中
     */
    private void insertBuild(StringBuilder buf, Document doc) {
        if (doc.getId() == null) {
            doc.id(Utils.uuid());
        }

        buf.append("{\"index\":{\"_index\":\"").append(config.indexName)
                .append("\",\"_id\":\"").append(doc.getId()).append("\"}}\n");

        Map<String, Object> source = new HashMap<>();
        source.put("content", doc.getContent());
        source.put("metadata", doc.getMetadata());
        source.put("embedding", doc.getEmbedding());

        // 保存URL，如果存在
        if (doc.getUrl() != null) {
            source.put("url", doc.getUrl());
        }

        // 将metadata内部字段平铺到顶层
        if (doc.getMetadata() != null) {
            for (Map.Entry<String, Object> entry : doc.getMetadata().entrySet()) {
                source.put(entry.getKey(), entry.getValue());
            }
        }

        buf.append(ONode.stringify(source)).append("\n");
    }

    /**
     * 删除指定ID的文档
     */
    @Override
    public void delete(String... ids) throws IOException {
        for (String id : ids) {
            //不支持星号删除
            Request request = new Request("DELETE", "/" + config.indexName + "/_doc/" + id);
            config.client.getLowLevelClient().performRequest(request);
            refreshIndex();
        }
    }

    @Override
    public boolean exists(String id) {
        try {
            Request request = new Request("HEAD", "/" + config.indexName + "/_doc/" + id);
            org.elasticsearch.client.Response response = config.client.getLowLevelClient().performRequest(request);
            return response.getStatusLine().getStatusCode() == 200;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 转换查询条件为 ES 查询语句
     */
    private Map<String, Object> translate(QueryCondition condition, float[] queryVector) {
        Map<String, Object> query = new HashMap<>();
        Map<String, Object> scriptScore = new HashMap<>();

        // 创建基础查询
        Map<String, Object> baseQuery = new HashMap<>();
        if (condition.getFilterExpression() != null) {
            Map<String, Object> filter = FilterTransformer.getInstance().transform(condition.getFilterExpression());
            if (filter != null) {
                baseQuery = filter;
            } else {
                // 如果没有过滤条件，使用match_all
                Map<String, Object> matchAll = new HashMap<>();
                baseQuery.put("match_all", matchAll);
            }
        } else {
            // 如果没有过滤条件，使用match_all
            Map<String, Object> matchAll = new HashMap<>();
            baseQuery.put("match_all", matchAll);
        }

        // 设置基础查询
        scriptScore.put("query", baseQuery);

        // 构建脚本计算余弦相似度
        Map<String, Object> script = new HashMap<>();

        // 在ES 7.x中使用内置的cosineSimilarity函数
        String scriptContent = "cosineSimilarity(params.query_vector, 'embedding') + 1.0";

        Map<String, Object> params = new HashMap<>();
        params.put("query_vector", queryVector);

        script.put("source", scriptContent);
        script.put("params", params);

        scriptScore.put("script", script);

        // 设置最终查询
        query.put("script_score", scriptScore);

        return query;
    }

    /**
     * 创建 Elasticsearch 知识库构建器
     *
     * @param embeddingModel 嵌入模型
     * @param client         Elasticsearch 客户端
     * @return 构建器实例
     */
    public static Builder builder(EmbeddingModel embeddingModel, RestHighLevelClient client) {
        return new Builder(embeddingModel, client);
    }

    /**
     * Elasticsearch 知识库构建器
     */
    public static class Builder {
        private final EmbeddingModel embeddingModel;
        private final RestHighLevelClient client;
        private String indexName = "solon_ai";
        private List<MetadataField> metadataFields = new ArrayList<>();

        /**
         * 构造函数
         *
         * @param embeddingModel 嵌入模型
         * @param client         Elasticsearch 客户端
         */
        public Builder(EmbeddingModel embeddingModel, RestHighLevelClient client) {
            this.embeddingModel = embeddingModel;
            this.client = client;
        }

        /**
         * 设置索引名
         */
        public Builder indexName(String indexName) {
            if (indexName != null) {
                this.indexName = indexName;
            }
            return this;
        }

        /**
         * 设置需要索引的元数据字段
         *
         * @param metadataFields 元数据字段列表
         * @return 构建器实例
         */
        public Builder metadataFields(List<MetadataField> metadataFields) {
            this.metadataFields = metadataFields;
            return this;
        }

        /**
         * 添加需要索引的元数据字段
         *
         * @param metadataField 元数据字段
         * @return 构建器实例
         */
        public Builder addMetadataField(MetadataField metadataField) {
            this.metadataFields.add(metadataField);
            return this;
        }

        /**
         * 构建 ElasticsearchRepository 实例
         *
         * @return ElasticsearchRepository 实例
         */
        public ElasticsearchRepository build() {
            return new ElasticsearchRepository(this);
        }
    }
}
