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
import org.noear.solon.ai.rag.util.HybridSearchParams;
import org.noear.solon.ai.rag.util.ListUtil;
import org.noear.solon.ai.rag.util.QueryCondition;
import org.noear.solon.ai.rag.util.SearchType;
import org.noear.solon.core.util.IoUtil;
import org.noear.solon.lang.Preview;

import java.io.IOException;
import java.util.*;
import java.util.function.BiConsumer;

/**
 * Elasticsearch 矢量存储知识库
 *
 * @author 小奶奶花生米
 * @since 3.1
 * @since 3.3
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
                .field("index", true)
                .field("similarity", "cosine")
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
        float[] queryVector = config.embeddingModel.embed(condition.getQuery());

        String responseBody = executeSearch(condition, queryVector);
        return parseSearchResponse(responseBody);
    }

    /**
     * 执行搜索请求
     */
    private String executeSearch(QueryCondition condition, float[] queryVector) throws IOException {
        Request request = new Request("POST", "/" + config.indexName + "/_search");

        Map<String, Object> requestBody = translate(condition, queryVector);

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
     * 批量存储文档（支持更新）
     * 将文档内容转换为向量并存储到ES中
     *
     * @param documents 要存储的文档列表
     * @throws IOException 如果存储过程中发生IO错误
     * @author 小奶奶花生米
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
            batchInsertDo(batch);

            //回调进度
            if (progressCallback != null) {
                progressCallback.accept(++batchIndex, batchList.size());
            }
        }
    }

    private void batchInsertDo(List<Document> batch) throws IOException{
        StringBuilder buf = new StringBuilder();
        for (Document doc : batch) {
            insertBuild(buf, doc);
        }

        executeBulkRequest(buf.toString());
        refreshIndex();
    }


    /**
     * 将文档添加到批量索引操作中
     */
    private void insertBuild(StringBuilder buf, Document doc) {
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
    public void deleteById(String... ids) throws IOException {
        if (Utils.isEmpty(ids)) {
            return;
        }

        for (String id : ids) {
            //不支持星号删除
            Request request = new Request("DELETE", "/" + config.indexName + "/_doc/" + id);
            config.client.getLowLevelClient().performRequest(request);
            refreshIndex();
        }
    }

    @Override
    public boolean existsById(String id) {
        try {
            Request request = new Request("HEAD", "/" + config.indexName + "/_doc/" + id);
            org.elasticsearch.client.Response response = config.client.getLowLevelClient().performRequest(request);
            return response.getStatusLine().getStatusCode() == 200;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Elasticsearch 矢量检索类型
     */
    public enum VectorSearchType {
        /**
         * 近似 knn 搜索
         * 使用 knn 查询，性能高
         */
        APPROXIMATE_KNN,

        /**
         * 精确 knn 检索
         * 使用 script_score 脚本计算余弦相似度，性能低
         */
        EXACT_KNN
    }

    /**
     * 转换查询条件为 ES 查询语句
     */
    private Map<String, Object> translate(QueryCondition condition, float[] queryVector) {
        Map<String, Object> requestBody = new HashMap<>();

        // 添加顶层参数 size
        requestBody.put("size", condition.getLimit() > 0 ? condition.getLimit() : 10);

        // 添加顶层参数 min_score
        if (condition.getSimilarityThreshold() > 0) {
            requestBody.put("min_score", condition.getSimilarityThreshold());
        }

        // 提取 baseFilter 逻辑
        Map<String, Object> baseFilter = new HashMap<>();
        if (condition.getFilterExpression() != null) {
            Map<String, Object> filter = FilterTransformer.getInstance().transform(condition.getFilterExpression());
            if (filter != null) {
                baseFilter = filter;
            } else {
                baseFilter.put("match_all", new HashMap<>());
            }
        } else {
            baseFilter.put("match_all", new HashMap<>());
        }

        // 构建核心搜索DSL
        Map<String, Object> searchBodyContent = buildSearchBodyContent(condition, queryVector, baseFilter);
        requestBody.putAll(searchBodyContent);

        return requestBody;
    }

    /**
     * 根据搜索类型和向量搜索类型构建核心搜索DSL
     */
    private Map<String, Object> buildSearchBodyContent(QueryCondition condition, float[] queryVector, Map<String, Object> baseFilter) {
        Map<String, Object> searchDSL = new HashMap<>();

        SearchType searchType = condition.getSearchType() == null ? SearchType.VECTOR : condition.getSearchType();

        switch (searchType) {
            case FULL_TEXT:
                searchDSL.put("query", buildFullTextQuery(condition, baseFilter));
                break;

            case HYBRID:
                searchDSL.put("query", buildHybridQuery(condition, queryVector, baseFilter));
                break;

            case VECTOR:
            default:
                searchDSL.put("query", buildVectorQuery(condition, queryVector, baseFilter));
                break;
        }

        return searchDSL;
    }

    /**
     * 构建全文检索查询子句 (match query)
     */
    private Map<String, Object> buildFullTextClause(QueryCondition condition) {
        Map<String, Object> fullTextClause = new HashMap<>();
        Map<String, Object> matchClause = new HashMap<>();
        Map<String, Object> contentParams = new HashMap<>();

        fullTextClause.put("match", matchClause);
        matchClause.put("content", contentParams);
        contentParams.put("query", condition.getQuery());

        HybridSearchParams hybridSearchParams = condition.getHybridSearchParams();
        if (SearchType.HYBRID.equals(condition.getSearchType()) && hybridSearchParams != null && hybridSearchParams.getFullTextWeight() > 0) {
            contentParams.put("boost", hybridSearchParams.getFullTextWeight());
        } else {
            contentParams.put("boost", 1.0);
        }

        return fullTextClause;
    }

    /**
     * 构建向量检索查询子句 (knn or script_score)
     */
    private Map<String, Object> buildVectorClause(QueryCondition condition, float[] queryVector) {
        VectorSearchType vectorSearchType = config.vectorSearchType;

        if (vectorSearchType == VectorSearchType.APPROXIMATE_KNN) {
            Map<String, Object> knnQuery = new HashMap<>();
            Map<String, Object> knnParams = new HashMap<>();
            knnQuery.put("knn", knnParams);
            knnParams.put("field", "embedding");
            knnParams.put("query_vector", queryVector);
            knnParams.put("k", condition.getLimit());
            knnParams.put("num_candidates", Math.min(condition.getLimit() * 10, 10000));

            HybridSearchParams hybridSearchParams = condition.getHybridSearchParams();
            if (SearchType.HYBRID.equals(condition.getSearchType()) && hybridSearchParams != null && hybridSearchParams.getVectorWeight() > 0) {
                knnParams.put("boost", hybridSearchParams.getVectorWeight());
            } else {
                knnParams.put("boost", 1.0);
            }

            return knnQuery;
        } else {
            Map<String, Object> scriptScoreQuery = new HashMap<>();
            Map<String, Object> scriptScoreDetails = new HashMap<>();
            Map<String, Object> scriptClause = new HashMap<>();
            Map<String, Object> scriptParams = new HashMap<>();
            scriptScoreQuery.put("script_score", scriptScoreDetails);
            // 为 script_score 提供一个默认的 query，后续会被 buildVectorQuery 中的 baseFilter 覆盖
            scriptScoreDetails.put("query", new HashMap<String, Object>() {{
                put("match_all", new HashMap<>());
            }});
            scriptScoreDetails.put("script", scriptClause);
            scriptClause.put("source", "cosineSimilarity(params.query_vector, 'embedding') + 1.0");
            scriptClause.put("params", scriptParams);
            scriptParams.put("query_vector", queryVector);

            return scriptScoreQuery;
        }
    }

    /**
     * 构建全文检索查询
     */
    private Map<String, Object> buildFullTextQuery(QueryCondition condition, Map<String, Object> baseFilter) {
        Map<String, Object> query = new HashMap<>();
        Map<String, Object> boolQuery = new HashMap<>();
        query.put("bool", boolQuery);

        List<Map<String, Object>> mustClauses = new ArrayList<>();
        mustClauses.add(buildFullTextClause(condition));

        boolQuery.put("must", mustClauses);
        boolQuery.put("filter", baseFilter);
        return query;
    }

    /**
     * 构建向量检索查询
     */
    private Map<String, Object> buildVectorQuery(QueryCondition condition, float[] queryVector, Map<String, Object> baseFilter) {
        Map<String, Object> query = new HashMap<>();
        VectorSearchType vectorSearchType = config.vectorSearchType;

        if (vectorSearchType == VectorSearchType.APPROXIMATE_KNN) {
            Map<String, Object> boolQuery = new HashMap<>();
            // buildVectorClause 返回 {"knn": {...}}
            Map<String, Object> knnClause = buildVectorClause(condition, queryVector);
            ((Map<String, Object>) knnClause.get("knn")).put("filter", baseFilter);

            boolQuery.put("must", knnClause);
            boolQuery.put("filter", baseFilter);
            query.put("bool", boolQuery);


        } else if (vectorSearchType == VectorSearchType.EXACT_KNN) {
            // buildVectorClause 返回 {"script_score": {"query": {"match_all":{}}, "script": {...}}}
            Map<String, Object> scriptScoreClause = buildVectorClause(condition, queryVector);

            // 获取 script_score 的详细内容
            Map<String, Object> scriptScoreDetails = (Map<String, Object>) scriptScoreClause.get("script_score");
            if (scriptScoreDetails != null) {
                // 将 baseFilter 设置为 script_score 的查询条件
                scriptScoreDetails.put("query", baseFilter);
            }
            query.putAll(scriptScoreClause);
        }
        return query;
    }

    /**
     * 构建混合检索查询
     */
    private Map<String, Object> buildHybridQuery(QueryCondition condition, float[] queryVector, Map<String, Object> baseFilter) {
        Map<String, Object> boolQuery = new HashMap<>();
        List<Map<String, Object>> shouldClauses = new ArrayList<>();

        // 全文检索部分
        Map<String, Object> fullTextClause = buildFullTextClause(condition);
        shouldClauses.add(fullTextClause);

        // 向量检索部分
        Map<String, Object> vectorClause = buildVectorClause(condition, queryVector);
        if (vectorClause != null) {
            shouldClauses.add(vectorClause);
        }

        boolQuery.put("should", shouldClauses);
        boolQuery.put("minimum_should_match", 1);

        boolQuery.put("filter", baseFilter);

        Map<String, Object> query = new HashMap<>();
        query.put("bool", boolQuery);
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
        private VectorSearchType vectorSearchType = VectorSearchType.APPROXIMATE_KNN;

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
         * 设置矢量搜索算法类型
         *
         * @param vectorSearchType 矢量搜索算法类型
         * @return 构建器
         */
        public Builder vectorSearchType(VectorSearchType vectorSearchType) {
            if (vectorSearchType != null) {
                this.vectorSearchType = vectorSearchType;
            }
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
