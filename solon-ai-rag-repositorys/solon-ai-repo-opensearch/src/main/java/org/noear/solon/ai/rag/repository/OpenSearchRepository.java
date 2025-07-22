package org.noear.solon.ai.rag.repository;

import java.io.IOException;
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
import org.noear.solon.ai.rag.repository.opensearch.FilterTransformer;
import org.noear.solon.ai.rag.repository.opensearch.MetadataField;
import org.noear.solon.ai.rag.util.*;
import org.noear.solon.core.util.IoUtil;
import org.noear.solon.lang.Preview;
import org.opensearch.action.admin.indices.delete.DeleteIndexRequest;
import org.opensearch.client.Request;
import org.opensearch.client.RequestOptions;
import org.opensearch.client.RestHighLevelClient;
import org.opensearch.client.indices.CreateIndexRequest;
import org.opensearch.client.indices.GetIndexRequest;
import org.opensearch.common.xcontent.XContentBuilder;
import org.opensearch.common.xcontent.XContentFactory;

/**
 * OpenSearch 矢量存储知识库
 *
 * @author 小奶奶花生米
 * @since 3.4
 */
@Preview("3.4")
public class OpenSearchRepository implements RepositoryStorable, RepositoryLifecycle {
    private final Builder config;

    private OpenSearchRepository(Builder config) {
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
            throw new RuntimeException("Failed to initialize OpenSearch index", e);
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
                // 添加索引设置，启用KNN插件
                .startObject("settings")
                .field("index.knn", true)  // 启用KNN
                .field("number_of_shards", 1)
                .field("number_of_replicas", 0)
                .endObject()
                .startObject("mappings")
                .startObject("properties")
                .startObject("content")
                .field("type", "text")
                .endObject()
                .startObject("metadata")
                .field("type", "object")
                .endObject()
                .startObject("embedding")
                .field("type", "knn_vector")
                .field("dimension", dims);

        // 使用 startObject/endObject 创建嵌套的 method 对象
        builder.startObject("method");

        if (IndexMethod.HNSW.toString().equals(config.indexMethod)) {
            builder.field("name", config.indexMethod);
            builder.startObject("parameters")
                    .field("ef_construction", config.efConstruction)
                    .field("m", config.m)
                    .endObject();
        } else if (IndexMethod.IVF.toString().equals(config.indexMethod)) {
            builder.field("name", config.indexMethod);
            builder.startObject("parameters")
                    .field("nlist", 4)  // IVF默认参数
                    .endObject();
        } else {
            // 默认使用HNSW
            builder.field("name", IndexMethod.HNSW.toString());
            builder.startObject("parameters")
                    .field("ef_construction", config.efConstruction)
                    .field("m", config.m)
                    .endObject();
        }

        builder.endObject(); // 结束 method 对象
        builder.endObject(); // 结束 embedding 字段

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
     */
    @Override
    public List<Document> search(QueryCondition condition) throws IOException {
        if (condition.getQuery() == null || condition.getQuery().isEmpty()) {
            throw new IllegalArgumentException("Query text cannot be empty for vector search");
        }

        // 生成查询向量
        float[] queryVector = config.embeddingModel.embed(condition.getQuery());

        String responseBody = executeSearch(condition, queryVector);
        List<Document> documents = parseSearchResponse(responseBody);
        return SimilarityUtil.refilter(documents.stream(), condition);
    }

    /**
     * 执行搜索请求
     */
    private String executeSearch(QueryCondition condition, float[] queryVector) throws IOException {
        Request request = new Request("POST", "/" + config.indexName + "/_search");

        Map<String, Object> requestBody = buildSearchRequest(condition, queryVector);

        request.setJsonEntity(ONode.stringify(requestBody));
        org.opensearch.client.Response response = config.client.getLowLevelClient().performRequest(request);
        return IoUtil.transferToString(response.getEntity().getContent(), "UTF-8");
    }

    /**
     * 构建搜索请求
     */
    private Map<String, Object> buildSearchRequest(QueryCondition condition, float[] queryVector) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("size", condition.getLimit() > 0 ? condition.getLimit() : 10);

        if (condition.getSimilarityThreshold() > 0) {
            requestBody.put("min_score", condition.getSimilarityThreshold());
        }

        Map<String, Object> query = new HashMap<>();
        requestBody.put("query", query);

        // 根据搜索类型构建查询
        SearchType searchType = condition.getSearchType() == null ? SearchType.VECTOR : condition.getSearchType();
        switch (searchType) {
            case FULL_TEXT:
                buildFullTextQuery(query, condition);
                break;
            case HYBRID:
                buildHybridQuery(query, condition, queryVector);
                break;
            case VECTOR:
            default:
                buildVectorQuery(query, condition, queryVector);
                break;
        }

        return requestBody;
    }

    /**
     * 构建全文检索查询
     */
    private void buildFullTextQuery(Map<String, Object> query, QueryCondition condition) {
        Map<String, Object> bool = new HashMap<>();
        query.put("bool", bool);

        List<Map<String, Object>> must = new ArrayList<>();
        bool.put("must", must);

        Map<String, Object> match = new HashMap<>();
        must.add(match);

        Map<String, Object> content = new HashMap<>();
        match.put("match", content);

        Map<String, Object> contentParams = new HashMap<>();
        content.put("content", contentParams);
        contentParams.put("query", condition.getQuery());
        contentParams.put("boost", 1.0);

        // 添加过滤条件
        if (condition.getFilterExpression() != null) {
            Map<String, Object> filter = buildFilter(condition);
            if (filter != null && !filter.isEmpty()) {
                bool.put("filter", filter);
            }
        }
    }

    /**
     * 构建向量查询
     */
    private void buildVectorQuery(Map<String, Object> query, QueryCondition condition, float[] queryVector) {
        Map<String, Object> knn = new HashMap<>();
        query.put("knn", knn);

        Map<String, Object> embedding = new HashMap<>();
        knn.put("embedding", embedding);

        embedding.put("vector", queryVector);
        embedding.put("k", condition.getLimit() > 0 ? condition.getLimit() : 10);

        // 添加过滤条件
        if (condition.getFilterExpression() != null) {
            Map<String, Object> filter = buildFilter(condition);
            if (filter != null && !filter.isEmpty()) {
                embedding.put("filter", filter);
            }
        }
    }

    /**
     * 构建混合查询
     */
    private void buildHybridQuery(Map<String, Object> query, QueryCondition condition, float[] queryVector) {
        Map<String, Object> bool = new HashMap<>();
        query.put("bool", bool);

        List<Map<String, Object>> should = new ArrayList<>();
        bool.put("should", should);
        bool.put("minimum_should_match", 1);

        // 全文检索部分
        Map<String, Object> match = new HashMap<>();
        should.add(match);

        Map<String, Object> content = new HashMap<>();
        match.put("match", content);

        Map<String, Object> contentParams = new HashMap<>();
        content.put("content", contentParams);
        contentParams.put("query", condition.getQuery());

        HybridSearchParams hybridParams = condition.getHybridSearchParams();
        if (hybridParams != null && hybridParams.getFullTextWeight() > 0) {
            contentParams.put("boost", hybridParams.getFullTextWeight());
        } else {
            contentParams.put("boost", 1.0);
        }

        // 向量检索部分
        Map<String, Object> knn = new HashMap<>();
        should.add(knn);

        Map<String, Object> knnQuery = new HashMap<>();
        knn.put("knn", knnQuery);

        Map<String, Object> embedding = new HashMap<>();
        knnQuery.put("embedding", embedding);

        embedding.put("vector", queryVector);
        embedding.put("k", condition.getLimit() > 0 ? condition.getLimit() : 10);

        if (hybridParams != null && hybridParams.getVectorWeight() > 0) {
            embedding.put("boost", hybridParams.getVectorWeight());
        } else {
            embedding.put("boost", 1.0);
        }

        // 添加过滤条件
        if (condition.getFilterExpression() != null) {
            Map<String, Object> filter = buildFilter(condition);
            if (filter != null && !filter.isEmpty()) {
                bool.put("filter", filter);
            }
        }
    }

    /**
     * 构建过滤条件
     *
     * @param condition 查询条件，包含过滤表达式
     * @return OpenSearch查询对象
     */
    private Map<String, Object> buildFilter(QueryCondition condition) {
        if (condition.getFilterExpression() == null) {
            Map<String, Object> matchAll = new HashMap<>();
            matchAll.put("match_all", new HashMap<>());
            return matchAll;
        }

        return FilterTransformer.getInstance().transform(condition.getFilterExpression());
    }

    /**
     * 解析搜索响应
     */
    private List<Document> parseSearchResponse(String responseBody) {
        Map<String, Object> responseMap = ONode.loadStr(responseBody).toObject(Map.class);
        List<Document> results = new ArrayList<>();

        Map<String, Object> hits = (Map<String, Object>) responseMap.get("hits");
        List<Map<String, Object>> hitsList = (List<Map<String, Object>>) hits.get("hits");

        for (Map<String, Object> hit : hitsList) {
            Map<String, Object> source = (Map<String, Object>) hit.get("_source");
            Document doc = new Document(
                    (String) hit.get("_id"),
                    (String) source.get("content"),
                    (Map<String, Object>) source.get("metadata"),
                    (Double) hit.get("_score"));
            doc.url((String) source.get("url"));
            results.add(doc);
        }
        return results;
    }

    /**
     * 批量存储文档
     *
     * @param documents 要存储的文档列表
     * @throws IOException 如果存储过程中发生IO错误
     */
    @Override
    public void insert(List<Document> documents, BiConsumer<Integer, Integer> progressCallback) throws IOException {
        if (Utils.isEmpty(documents)) {
            //回调进度
            progressCallback.accept(0, 0);
            return;
        }

        // 分块处理
        List<List<Document>> batchList = ListUtil.partition(documents, config.embeddingModel.batchSize());
        int batchIndex = 0;
        for (List<Document> batch : batchList) {
            config.embeddingModel.embed(batch);
            batchInsertDo(batch);

            //回调进度
            progressCallback.accept(batchIndex++, batchList.size());
        }
    }

    private void batchInsertDo(List<Document> batch) throws IOException{
        StringBuilder buf = new StringBuilder();
        for (Document doc : batch) {
            if (doc.getId() == null) {
                doc.id(Utils.uuid());
            }

            buf.append("{\"index\":{\"_index\":\"").append(config.indexName)
                    .append("\",\"_id\":\"").append(doc.getId()).append("\"}}\n");

            Map<String, Object> source = new HashMap<>();
            source.put("content", doc.getContent());
            source.put("metadata", doc.getMetadata());
            source.put("embedding", doc.getEmbedding());

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

        executeBulkRequest(buf.toString());
        refreshIndex();
    }

    /**
     * 执行批量请求
     */
    private void executeBulkRequest(String bulkBody) throws IOException {
        Request request = new Request("POST", "/_bulk");
        request.setJsonEntity(bulkBody);
        config.client.getLowLevelClient().performRequest(request);
    }

    /**
     * 刷新索引
     */
    private void refreshIndex() throws IOException {
        Request request = new Request("POST", "/" + config.indexName + "/_refresh");
        config.client.getLowLevelClient().performRequest(request);
    }

    /**
     * 删除指定ID的文档
     */
    @Override
    public void delete(String... ids) throws IOException {
        for (String id : ids) {
            Request request = new Request("DELETE", "/" + config.indexName + "/_doc/" + id);
            config.client.getLowLevelClient().performRequest(request);
        }
        refreshIndex();
    }

    /**
     * 检查文档是否存在
     */
    @Override
    public boolean exists(String id) {
        try {
            Request request = new Request("HEAD", "/" + config.indexName + "/_doc/" + id);
            org.opensearch.client.Response response = config.client.getLowLevelClient().performRequest(request);
            return response.getStatusLine().getStatusCode() == 200;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * OpenSearch 索引方法
     */
    public enum IndexMethod {
        /**
         * HNSW 算法
         */
        HNSW("hnsw"),

        /**
         * IVF 算法
         */
        IVF("ivf");

        private final String value;

        IndexMethod(String value) {
            this.value = value;
        }

        @Override
        public String toString() {
            return value;
        }
    }

    /**
     * 创建 OpenSearch 知识库构建器
     *
     * @param embeddingModel 嵌入模型
     * @param client         OpenSearch 客户端
     * @return 构建器实例
     */
    public static Builder builder(EmbeddingModel embeddingModel, RestHighLevelClient client) {
        return new Builder(embeddingModel, client);
    }

    /**
     * OpenSearch 知识库构建器
     */
    public static class Builder {
        private final EmbeddingModel embeddingModel;
        private final RestHighLevelClient client;
        private String indexName = "solon_ai";
        private List<MetadataField> metadataFields = new ArrayList<>();
        private String indexMethod = IndexMethod.HNSW.toString();
        private int efConstruction = 512;
        private int m = 16;

        /**
         * 构造函数
         *
         * @param embeddingModel 嵌入模型
         * @param client         OpenSearch 客户端
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
         * 设置索引方法
         *
         * @param indexMethod 索引方法
         * @return 构建器实例
         */
        public Builder indexMethod(IndexMethod indexMethod) {
            if (indexMethod != null) {
                this.indexMethod = indexMethod.toString();
            }
            return this;
        }

        /**
         * 设置HNSW算法的ef_construction参数
         *
         * @param efConstruction ef_construction参数
         * @return 构建器实例
         */
        public Builder efConstruction(int efConstruction) {
            this.efConstruction = efConstruction;
            return this;
        }

        /**
         * 设置HNSW算法的m参数
         *
         * @param m m参数
         * @return 构建器实例
         */
        public Builder m(int m) {
            this.m = m;
            return this;
        }

        /**
         * 构建 OpenSearchRepository 实例
         *
         * @return OpenSearchRepository 实例
         */
        public OpenSearchRepository build() {
            return new OpenSearchRepository(this);
        }
    }
}
