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
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
import org.noear.solon.ai.rag.repository.elasticsearch.MetadataField;
import org.noear.solon.ai.rag.util.ListUtil;
import org.noear.solon.ai.rag.util.QueryCondition;
import org.noear.solon.core.util.IoUtil;
import org.noear.solon.expression.Expression;
import org.noear.solon.expression.snel.ComparisonNode;
import org.noear.solon.expression.snel.ComparisonOp;
import org.noear.solon.expression.snel.ConstantNode;
import org.noear.solon.expression.snel.LogicalNode;
import org.noear.solon.expression.snel.LogicalOp;
import org.noear.solon.expression.snel.VariableNode;
import org.noear.solon.lang.Preview;

/**
 * Elasticsearch 矢量存储知识库
 *
 * @author 小奶奶花生米
 * @since 3.1
 */
@Preview("3.1")
public class ElasticsearchRepository implements RepositoryStorable, RepositoryLifecycle {
    /**
     * 向量模型，用于将文档内容转换为向量表示
     */
    private final EmbeddingModel embeddingModel;

    /**
     * Elasticsearch 客户端，用于与 ES 服务器交互
     */
    private final RestHighLevelClient client;

    /**
     * ES 索引名称，用于存储文档
     */
    private final String indexName;

    /**
     * metadata需要索引的字段列表
     */
    private final List<MetadataField> metadataFields;


    /**
     * 构造函数
     *
     * @param embeddingModel 向量模型，用于生成文档的向量表示
     * @param client         ES客户端
     * @param indexName      索引名称
     * @author 小奶奶花生米
     */
    public ElasticsearchRepository(EmbeddingModel embeddingModel, RestHighLevelClient client, String indexName) {
        this(embeddingModel, client, indexName, new ArrayList<>());
    }

    /**
     * 构造函数
     *
     * @param embeddingModel 向量模型，用于生成文档的向量表示
     * @param client         ES客户端
     * @param indexName      索引名称
     * @param metadataFields 需要索引的元数据字段列表
     * @author 小奶奶花生米
     */
    public ElasticsearchRepository(EmbeddingModel embeddingModel, RestHighLevelClient client,
                                  String indexName, List<MetadataField> metadataFields) {
        this.embeddingModel = embeddingModel;
        this.client = client;
        this.indexName = indexName;
        this.metadataFields = metadataFields;
        initRepository();
    }

    /**
     * 创建 Elasticsearch 知识库构建器
     *
     * @param embeddingModel 嵌入模型
     * @param client Elasticsearch 客户端
     * @param indexName 索引名称
     * @return 构建器实例
     */
    public static Builder builder(EmbeddingModel embeddingModel, RestHighLevelClient client, String indexName) {
        return new Builder(embeddingModel, client, indexName);
    }

    /**
     * Elasticsearch 知识库构建器
     */
    public static class Builder {
        private final EmbeddingModel embeddingModel;
        private final RestHighLevelClient client;
        private final String indexName;
        private List<MetadataField> metadataFields = new ArrayList<>();

        /**
         * 构造函数
         *
         * @param embeddingModel 嵌入模型
         * @param client Elasticsearch 客户端
         * @param indexName 索引名称
         */
        public Builder(EmbeddingModel embeddingModel, RestHighLevelClient client, String indexName) {
            this.embeddingModel = embeddingModel;
            this.client = client;
            this.indexName = indexName;
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
            return new ElasticsearchRepository(embeddingModel, client, indexName, metadataFields);
        }
    }

    /**
     * 初始化仓库
     */
    @Override
    public void initRepository() {
        try {
            GetIndexRequest getIndexRequest = new GetIndexRequest(indexName);
            if (client.indices().exists(getIndexRequest, RequestOptions.DEFAULT) == false) {
                CreateIndexRequest createIndexRequest = new CreateIndexRequest(indexName);
                createIndexRequest.source(buildIndexMapping());
                client.indices().create(createIndexRequest, RequestOptions.DEFAULT);
            }
        } catch (IOException e) {
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
        int dims = embeddingModel.dimensions();

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
                .endObject();
                
        // 如果有元数据字段需要索引，将其平铺到顶层
        if (!metadataFields.isEmpty()) {
            for (MetadataField field : metadataFields) {
                builder.startObject(field.getName());
                switch (field.getFieldType()) {
                    case NUMERIC:
                        builder.field("type", "float");
                        break;
                    case TAG:
                    case KEYWORD:
                        builder.field("type", "keyword");
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
        Request request = new Request("POST", "/" + indexName + "/_delete_by_query");
        request.setJsonEntity("{\"query\":{\"match_all\":{}}}");
        client.getLowLevelClient().performRequest(request);
    }


    /**
     * 搜索文档
     * 支持文本搜索、向量相似度搜索和元数据过滤
     *
     * @param condition 查询条件，包含查询文本、过滤器等
     * @return 匹配的文档列表
     * @throws IOException 如果搜索过程中发生IO错误
     * @author 小奶奶花生米
     */
    @Override
    public List<Document> search(QueryCondition condition) throws IOException {
        String responseBody = executeSearch(condition);
        return parseSearchResponse(responseBody);
    }

    /**
     * 执行搜索请求
     */
    private String executeSearch(QueryCondition condition) throws IOException {
        Request request = new Request("POST", "/" + indexName + "/_search");

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("query", translate(condition));
        requestBody.put("size", condition.getLimit() > 0 ? condition.getLimit() : 10);

        // 将min_score作为顶层参数添加到请求体中
        if (condition.getSimilarityThreshold() > 0) {
            requestBody.put("min_score", condition.getSimilarityThreshold());
        }

        request.setJsonEntity(ONode.stringify(requestBody));
        org.elasticsearch.client.Response response = client.getLowLevelClient().performRequest(request);
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
        client.getLowLevelClient().performRequest(request);
    }

    /**
     * 刷新索引
     * 确保最近的更改对搜索可见
     *
     * @throws IOException 如果刷新过程发生IO错误
     */
    private void refreshIndex() throws IOException {
        Request request = new Request("POST", "/" + indexName + "/_refresh");
        client.getLowLevelClient().performRequest(request);
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

        // 批量embedding
        for (List<Document> sub : ListUtil.partition(documents, 20)) {
            embeddingModel.embed(sub);

            StringBuilder buf = new StringBuilder();
            for (Document doc : sub) {
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

        buf.append("{\"index\":{\"_index\":\"").append(indexName)
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
            Request request = new Request("DELETE", "/" + indexName + "/_doc/" + id);
            client.getLowLevelClient().performRequest(request);
            refreshIndex();
        }
    }

    @Override
    public boolean exists(String id) {
        try {
            Request request = new Request("HEAD", "/" + indexName + "/_doc/" + id);
            org.elasticsearch.client.Response response = client.getLowLevelClient().performRequest(request);
            return response.getStatusLine().getStatusCode() == 200;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 将过滤表达式转换为Elasticsearch查询
     *
     * @param filterExpression 过滤表达式
     * @return Elasticsearch查询对象
     */
    private Map<String, Object> parseFilterExpression(Expression<Boolean> filterExpression) {
        if (filterExpression == null) {
            return null;
        }

        if (filterExpression instanceof VariableNode) {
            // 变量节点，获取字段名
            String fieldName = ((VariableNode) filterExpression).getName();
            Map<String, Object> exists = new HashMap<>();
            Map<String, Object> field = new HashMap<>();
            field.put("field", fieldName);
            exists.put("exists", field);
            return exists;
        } else if (filterExpression instanceof ConstantNode) {
            // 常量节点，根据值类型和是否为集合创建不同的查询
            ConstantNode node = (ConstantNode) filterExpression;
            Object value = node.getValue();
            Boolean isCollection = node.isCollection();

            if (Boolean.TRUE.equals(value)) {
                Map<String, Object> matchAll = new HashMap<>();
                matchAll.put("match_all", new HashMap<>());
                return matchAll;
            } else if (Boolean.FALSE.equals(value)) {
                Map<String, Object> boolQuery = new HashMap<>();
                Map<String, Object> mustNot = new HashMap<>();
                mustNot.put("match_all", new HashMap<>());
                boolQuery.put("must_not", mustNot);
                return boolQuery;
            }

            return null;
        } else if (filterExpression instanceof ComparisonNode) {
            // 比较节点，处理各种比较运算符
            ComparisonNode node = (ComparisonNode) filterExpression;
            ComparisonOp operator = node.getOperator();
            Expression left = node.getLeft();
            Expression right = node.getRight();

            // 获取字段名和值
            String fieldName = null;
            Object value = null;

            if (left instanceof VariableNode && right instanceof ConstantNode) {
                fieldName = ((VariableNode) left).getName();
                value = ((ConstantNode) right).getValue();
            } else if (right instanceof VariableNode && left instanceof ConstantNode) {
                fieldName = ((VariableNode) right).getName();
                value = ((ConstantNode) left).getValue();
                // 反转操作符
                operator = reverseOperator(operator);
            } else {
                // 不支持的比较节点结构
                return null;
            }

            // 根据操作符构建相应的查询
            switch (operator) {
                case eq:
                    return createTermQuery(fieldName, value);
                case neq:
                    return createMustNotQuery(createTermQuery(fieldName, value));
                case gt:
                    return createRangeQuery(fieldName, "gt", value);
                case gte:
                    return createRangeQuery(fieldName, "gte", value);
                case lt:
                    return createRangeQuery(fieldName, "lt", value);
                case lte:
                    return createRangeQuery(fieldName, "lte", value);
                case in:
                    if (value instanceof Collection) {
                        return createTermsQuery(fieldName, (Collection<?>) value);
                    }
                    return createTermQuery(fieldName, value);
                case nin:
                    if (value instanceof Collection) {
                        return createMustNotQuery(createTermsQuery(fieldName, (Collection<?>) value));
                    }
                    return createMustNotQuery(createTermQuery(fieldName, value));
                default:
                    return null;
            }
        } else if (filterExpression instanceof LogicalNode) {
            // 逻辑节点，处理AND, OR, NOT
            LogicalNode node = (LogicalNode) filterExpression;
            LogicalOp operator = node.getOperator();
            Expression left = node.getLeft();
            Expression right = node.getRight();

            if (right != null) {
                // 二元逻辑运算符 (AND, OR)
                Map<String, Object> leftQuery = parseFilterExpression(left);
                Map<String, Object> rightQuery = parseFilterExpression(right);

                if (leftQuery == null || rightQuery == null) {
                    return null;
                }

                Map<String, Object> boolQuery = new HashMap<>();
                List<Map<String, Object>> conditions = new ArrayList<>();
                conditions.add(leftQuery);
                conditions.add(rightQuery);

                switch (operator) {
                    case and:
                        boolQuery.put("must", conditions);
                        break;
                    case or:
                        boolQuery.put("should", conditions);
                        break;
                    default:
                        return null;
                }

                Map<String, Object> result = new HashMap<>();
                result.put("bool", boolQuery);
                return result;
            } else if (left != null) {
                // 一元逻辑运算符 (NOT)
                Map<String, Object> operandQuery = parseFilterExpression(left);

                if (operandQuery == null) {
                    return null;
                }

                if (operator == LogicalOp.not) {
                    return createMustNotQuery(operandQuery);
                }
            }
        }

        return null;
    }

    /**
     * 反转比较运算符
     *
     * @param op 原运算符
     * @return 反转后的运算符
     */
    private ComparisonOp reverseOperator(ComparisonOp op) {
        switch (op) {
            case gt: return ComparisonOp.lt;
            case gte: return ComparisonOp.lte;
            case lt: return ComparisonOp.gt;
            case lte: return ComparisonOp.gte;
            default: return op;
        }
    }

    /**
     * 创建term查询
     *
     * @param field 字段名
     * @param value 值
     * @return 查询对象
     */
    private Map<String, Object> createTermQuery(String field, Object value) {
        Map<String, Object> termValue = new HashMap<>();
        termValue.put("value", value);
        Map<String, Object> term = new HashMap<>();
        term.put(field, termValue);

        Map<String, Object> result = new HashMap<>();
        result.put("term", term);
        return result;
    }

    /**
     * 创建terms查询（适用于集合）
     *
     * @param field 字段名
     * @param values 值集合
     * @return 查询对象
     */
    private Map<String, Object> createTermsQuery(String field, Collection<?> values) {
        Map<String, Object> terms = new HashMap<>();
        terms.put(field, new ArrayList<>(values));

        Map<String, Object> result = new HashMap<>();
        result.put("terms", terms);
        return result;
    }

    /**
     * 创建范围查询
     *
     * @param field 字段名
     * @param operator 操作符(gt, gte, lt, lte)
     * @param value 值
     * @return 查询对象
     */
    private Map<String, Object> createRangeQuery(String field, String operator, Object value) {
        Map<String, Object> rangeValue = new HashMap<>();
        rangeValue.put(operator, value);

        Map<String, Object> range = new HashMap<>();
        range.put(field, rangeValue);

        Map<String, Object> result = new HashMap<>();
        result.put("range", range);
        return result;
    }

    /**
     * 创建must_not查询（NOT操作）
     *
     * @param query 要否定的查询
     * @return 查询对象
     */
    private Map<String, Object> createMustNotQuery(Map<String, Object> query) {
        if (query == null) {
            return null;
        }

        Map<String, Object> boolQuery = new HashMap<>();
        List<Map<String, Object>> mustNot = new ArrayList<>();
        mustNot.add(query);
        boolQuery.put("must_not", mustNot);

        Map<String, Object> result = new HashMap<>();
        result.put("bool", boolQuery);
        return result;
    }

    /**
     * 转换查询条件为 ES 查询语句
     */
    private Map<String, Object> translate(QueryCondition condition) {
        Map<String, Object> query = new HashMap<>();
        Map<String, Object> bool = new HashMap<>();
        List<Map<String, Object>> must = new ArrayList<>();

        // 构建文本查询
        if (condition.getQuery() != null && !condition.getQuery().isEmpty()) {
            Map<String, Object> match = new HashMap<>();
            Map<String, Object> matchQuery = new HashMap<>();
            matchQuery.put("content", condition.getQuery());
            match.put("match", matchQuery);
            must.add(match);
        } else {
            // 空查询时返回所有文档
            Map<String, Object> matchAll = new HashMap<>();
            matchAll.put("match_all", new HashMap<>());
            must.add(matchAll);
        }

        // 构建过滤条件
        if (condition.getFilterExpression() != null) {
            Map<String, Object> filter = parseFilterExpression(condition.getFilterExpression());
            if (filter != null) {
                bool.put("filter", filter);
            }
        }

        bool.put("must", must);
        query.put("bool", bool);

        // 处理最小相似度过滤
        if (condition.getSimilarityThreshold() > 0) {
            // 不要创建嵌套的query结构，直接在外层添加min_score
            return query;
        }

        return query;
    }
}
