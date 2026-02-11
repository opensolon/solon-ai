
package org.noear.solon.ai.rag.repository;

import org.noear.solon.Utils;
import org.noear.solon.ai.embedding.EmbeddingModel;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.RepositoryLifecycle;
import org.noear.solon.ai.rag.RepositoryStorable;
import org.noear.solon.ai.rag.repository.weaviate.*;
import org.noear.solon.ai.rag.repository.weaviate.MetadataField;
import org.noear.solon.ai.rag.util.ListUtil;
import org.noear.solon.ai.rag.util.QueryCondition;
import org.noear.solon.ai.rag.util.SimilarityUtil;
import org.noear.solon.lang.Preview;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Weaviate 矢量存储知识库
 * 基于 Weaviate REST / GraphQL 接口
 *
 * 说明：
 * 1. 使用外部 EmbeddingModel 生成向量，通过 nearVector GraphQL 查询实现向量搜索；
 * 2. 文档内容、元数据以属性形式写入 Weaviate，对应属性名为：
 *    - content: 文本内容
 *    - url: 文档原始地址（如果有）
 *    - 其他 metadata: 直接展开为属性；
 * 3. Document.id 对应 Weaviate 对象的 uuid（使用我们生成的 uuid，传给 Weaviate）。
 *
 * @author 小奶奶花生米
 * @since 3.1
 */
@Preview("3.1")
public class WeaviateRepository implements RepositoryStorable, RepositoryLifecycle {

    private static final String DEFAULT_COLLECTION_NAME = "solon_ai";

    /**
     * 基础配置
     */
    private final Builder config;
    private final WeaviateClient client;

    private WeaviateRepository(Builder config) {
        this.config = config;
        this.client = config.client;
    }

    /**
     * 初始化仓库：创建 collection（class）Schema（如果不存在）
     */
    @Override
    public void initRepository() throws Exception {
        String className = getClassName();

        // 检查 class 是否存在，不存在则创建
        SchemaResponse schemaResponse = client.getSchema();
        if (!schemaResponse.hasClass(className)) {
            List<Map<String, Object>> properties = new ArrayList<>();

            Map<String, Object> contentProp = new HashMap<>();
            contentProp.put("name", "content");
            contentProp.put("dataType", new String[]{"text"});
            properties.add(contentProp);

            Map<String, Object> urlProp = new HashMap<>();
            urlProp.put("name", "url");
            urlProp.put("dataType", new String[]{"string"});
            properties.add(urlProp);

            // 添加元数据字段
            if (config.metadataFields != null && !config.metadataFields.isEmpty()) {
                for (MetadataField field : config.metadataFields) {
                    Map<String, Object> metadataProp = new HashMap<>();
                    metadataProp.put("name", field.getName());
                    // 根据字段类型设置 dataType
                    switch (field.getFieldType()) {
                        case STRING:
                            metadataProp.put("dataType", new String[]{"string"});
                            break;
                        case INTEGER:
                            metadataProp.put("dataType", new String[]{"int"});
                            break;
                        case FLOAT:
                            metadataProp.put("dataType", new String[]{"number"});
                            break;
                        case BOOLEAN:
                            metadataProp.put("dataType", new String[]{"boolean"});
                            break;
                        default:
                            metadataProp.put("dataType", new String[]{"string"});
                            break;
                    }
                    properties.add(metadataProp);
                }
            }

            client.createClass(className, properties);
        }
    }

    /**
     * 删除整个集合（删除 class）
     */
    @Override
    public void dropRepository() throws Exception {
        String className = getClassName();
        client.deleteClass(className);
    }

    private String getClassName() {
        String className = (config.collectionName != null ? config.collectionName : DEFAULT_COLLECTION_NAME);
        // Weaviate 类名需要首字母大写
        if (className != null && !className.isEmpty()) {
            return Character.toUpperCase(className.charAt(0)) + className.substring(1);
        }
        return className;
    }

    /**
     * 批量保存文档（无进度回调）
     */
    public void save(List<Document> documents) throws IOException {
        save(documents, null);
    }

    /**
     * 批量保存文档（支持进度回调）
     */
    @Override
    public void save(List<Document> documents, BiConsumer<Integer, Integer> progressCallback) throws IOException {
        if (Utils.isEmpty(documents)) {
            if (progressCallback != null) {
                progressCallback.accept(0, 0);
            }
            return;
        }

        try {
            initRepository();
        } catch (Exception e) {
            throw new IOException("Failed to initialize Weaviate repository before saving: " + e.getMessage(), e);
        }

        // 确保所有文档有 id
        for (Document doc : documents) {
            if (Utils.isEmpty(doc.getId())) {
                doc.id(Utils.uuid());
            }
        }

        // 通过 embeddingModel 生成向量（如果有配置）
        if (config.embeddingModel != null) {
            List<List<Document>> batchList = ListUtil.partition(documents, config.embeddingModel.batchSize());
            int batchIndex = 0;

            for (List<Document> batch : batchList) {
                config.embeddingModel.embed(batch);
                batchSaveDo(batch);

                if (progressCallback != null) {
                    progressCallback.accept(++batchIndex, batchList.size());
                }
            }
        } else {
            List<List<Document>> batchList = ListUtil.partition(documents, 64);
            int batchIndex = 0;

            for (List<Document> batch : batchList) {
                batchSaveDo(batch);

                if (progressCallback != null) {
                    progressCallback.accept(++batchIndex, batchList.size());
                }
            }
        }
    }

    /**
     * 单批次写入 Weaviate（使用 /v1/batch/objects）
     */
    private void batchSaveDo(List<Document> batch) throws IOException {
        if (batch == null || batch.isEmpty()) {
            return;
        }

        String className = getClassName();
        List<Map<String, Object>> objects = new ArrayList<>(batch.size());

        for (Document doc : batch) {
            Map<String, Object> obj = new HashMap<>();
            obj.put("id", doc.getId());
            obj.put("class", className);

            Map<String, Object> props = new HashMap<>(doc.getMetadata());
            props.put("content", doc.getContent());
            if (!Utils.isEmpty(doc.getUrl())) {
                props.put("url", doc.getUrl());
            }

            obj.put("properties", props);

            // 写入向量（如果有）
            if (doc.getEmbedding() != null) {
                float[] emb = doc.getEmbedding();
                double[] vec = new double[emb.length];
                for (int i = 0; i < emb.length; i++) {
                    vec[i] = emb[i];
                }
                obj.put("vector", vec);
            }

            objects.add(obj);
        }

        client.batchSaveObjects(objects);
    }

    /**
     * 按 Weaviate 对象 uuid 删除
     */
    @Override
    public void deleteById(String... ids) throws IOException {
        if (ids == null || ids.length == 0) {
            return;
        }

        String className = getClassName();
        for (String id : ids) {
            if (Utils.isEmpty(id)) {
                continue;
            }
            client.deleteObject(className, id);
        }
    }

    /**
     * 检查对象是否存在（按 uuid）
     */
    @Override
    public boolean existsById(String id) throws IOException {
        if (Utils.isEmpty(id)) {
            return false;
        }

        String className = getClassName();
        return client.objectExists(className, id);
    }

    /**
     * 向量搜索（使用 GraphQL nearVector）
     */
    @Override
    public List<Document> search(QueryCondition condition) throws IOException {
        if (condition == null || condition.getQuery() == null) {
            return new ArrayList<>();
        }

        if (config.embeddingModel == null) {
            throw new IOException("EmbeddingModel is required for WeaviateRepository.search (nearVector)");
        }

        // 使用 EmbeddingModel 生成查询向量
        float[] embedding = config.embeddingModel.embed(condition.getQuery());
        double[] queryVec = new double[embedding.length];
        for (int i = 0; i < embedding.length; i++) {
            queryVec[i] = embedding[i];
        }

        String className = getClassName();

        // 构造 GraphQL 查询
        StringBuilder sb = new StringBuilder();
        sb.append("{\n")
                .append("  Get {\n")
                .append("    ").append(className).append("(\n")
                .append("      nearVector: {\n")
                .append("        vector: [");
        for (int i = 0; i < queryVec.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            if (i % 10 == 0) {
                sb.append("\n          ");
            }
            sb.append(queryVec[i]);
        }
        sb.append("\n        ],\n")
                .append("        certainty: 0.7\n")
                .append("      }");

        if (condition.getLimit() > 0) {
            sb.append(",\n      limit: ").append(condition.getLimit());
        }

        if (condition.getFilterExpression() != null) {
            Map<String, Object> filter = FilterTransformer.getInstance().transform(condition.getFilterExpression());
            if (filter != null && !filter.isEmpty()) {
                // 将过滤表达式转换为 Weaviate 的 where 参数格式
                Map<String, Object> where = convertToWeaviateWhere(filter);
                if (where != null && !where.isEmpty()) {
                    // 将 where 参数添加到 GraphQL 查询中，使用GraphQL对象字面量格式
                    sb.append(",\n      where: ").append(FilterTransformer.getInstance().convertToGraphQLObject(where));
                }
            }
        }

        sb.append("\n    ) {\n")
                .append("      content\n")
                .append("      url\n");

        // 动态添加metadata字段
        if (config.metadataFields != null && !config.metadataFields.isEmpty()) {
            for (MetadataField field : config.metadataFields) {
                sb.append("      ").append(field.getName()).append("\n");
            }
        }

        sb.append("      _additional {\n")
                .append("        id\n")
                .append("        certainty\n")
                .append("      }\n")
                .append("    }\n")
                .append("  }\n")
                .append("}");

        // 移除多余的逗号
        String query = sb.toString();
        // 移除 limit 前的多余逗号
        query = query.replaceAll(",\\s*limit:", " limit:");
        // 移除 nearVector 后的多余逗号
        query = query.replaceAll("certainty: 0.7\\s*},\\s*", "certainty: 0.7\\n      },");

        // 执行 GraphQL 查询
        GraphQLResponse response = client.executeGraphQL(query, GraphQLResponse.class);

        List<Document> docs = new ArrayList<>();

        if (response == null || response.getData() == null) {
            return docs;
        }

        Map<String, List<DocumentData>> getResult = response.getData().getGet();
        List<DocumentData> documentDataList = getResult.get(className);

        if (documentDataList == null || documentDataList.isEmpty()) {
            return docs;
        }

        for (DocumentData item : documentDataList) {
            String content = item.getContent();
            String urlVal = item.getUrl();
            double score = 0.0;
            String id = null;
            if (item.getAdditional() != null) {
                score = item.getAdditional().getCertainty();
                id = item.getAdditional().getId();
            }
            // 使用 certainty 作为 score，不需要转换，因为它已经是 0-1 之间的值

            Map<String, Object> metadata = new HashMap<>();
            if (!Utils.isEmpty(urlVal)) {
                metadata.put("url", urlVal);
            }
            // 动态添加metadata字段
            if (item.getMetadata() != null && !item.getMetadata().isEmpty()) {
                metadata.putAll(item.getMetadata());
            }

            Document doc = new Document(id, content, metadata, score);
            if (!Utils.isEmpty(urlVal)) {
                doc.url(urlVal);
            }
            docs.add(doc);
        }

        // 再次基于 Vector/文本条件做过滤与排序
        return SimilarityUtil.refilter(docs.stream(), condition);
    }

    /**
     * 将过滤表达式转换为 Weaviate 的 where 参数格式
     */
    private Map<String, Object> convertToWeaviateWhere(Map<String, Object> filter) {
        if (filter == null || filter.isEmpty()) {
            return null;
        }

        // 处理 AND 操作
        if (filter.containsKey("$and")) {
            List<Object> operands = new ArrayList<>();
            List<Map<String, Object>> andConditions = (List<Map<String, Object>>) filter.get("$and");
            for (Map<String, Object> condition : andConditions) {
                Map<String, Object> converted = convertToWeaviateWhere(condition);
                if (converted != null) {
                    operands.add(converted);
                }
            }
            if (!operands.isEmpty()) {
                Map<String, Object> result = new HashMap<>();
                result.put("operator", "And");
                result.put("operands", operands);
                return result;
            }
        }

        // 处理 OR 操作
        if (filter.containsKey("$or")) {
            List<Object> operands = new ArrayList<>();
            List<Map<String, Object>> orConditions = (List<Map<String, Object>>) filter.get("$or");
            for (Map<String, Object> condition : orConditions) {
                Map<String, Object> converted = convertToWeaviateWhere(condition);
                if (converted != null) {
                    operands.add(converted);
                }
            }
            if (!operands.isEmpty()) {
                Map<String, Object> result = new HashMap<>();
                result.put("operator", "Or");
                result.put("operands", operands);
                return result;
            }
        }

        // 处理 NOT 操作
        if (filter.containsKey("$not")) {
            Map<String, Object> notCondition = (Map<String, Object>) filter.get("$not");
            Map<String, Object> converted = convertToWeaviateWhere(notCondition);
            if (converted != null) {
                Map<String, Object> result = new HashMap<>();
                result.put("operator", "Not");
                result.put("operands", new ArrayList<>(Collections.singletonList(converted)));
                return result;
            }
        }

        // 处理基本比较操作
        for (Map.Entry<String, Object> entry : filter.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("$")) {
                continue; // 跳过特殊操作符
            }

            Object value = entry.getValue();
            if (value instanceof Map) {
                Map<String, Object> valueMap = (Map<String, Object>) value;
                // 处理 $eq, $ne, $gt, $gte, $lt, $lte, $in, $nin 操作
                for (Map.Entry<String, Object> opEntry : valueMap.entrySet()) {
                    String op = opEntry.getKey();
                    Object opValue = opEntry.getValue();
                    Map<String, Object> condition = createCondition(key, op, opValue);
                    if (condition != null) {
                        return condition;
                    }
                }
            } else {
                // 默认为等于操作
                Map<String, Object> condition = createCondition(key, "$eq", value);
                if (condition != null) {
                    return condition;
                }
            }
        }

        return null;
    }

    /**
     * 创建单个条件
     */
    private Map<String, Object> createCondition(String field, String operator, Object value) {
        Map<String, Object> condition = new HashMap<>();
        condition.put("path", Collections.singletonList(field));

        switch (operator) {
            case "$eq":
                condition.put("operator", "Equal");
                setValueByType(condition, value);
                break;
            case "$ne":
                condition.put("operator", "NotEqual");
                setValueByType(condition, value);
                break;
            case "$gt":
                condition.put("operator", "GreaterThan");
                setValueByType(condition, value);
                break;
            case "$gte":
                condition.put("operator", "GreaterThanEqual");
                setValueByType(condition, value);
                break;
            case "$lt":
                condition.put("operator", "LessThan");
                setValueByType(condition, value);
                break;
            case "$lte":
                condition.put("operator", "LessThanEqual");
                setValueByType(condition, value);
                break;
            case "$in":
                condition.put("operator", "ContainsAny");
                if (value instanceof List) {
                    condition.put("valueTextArray", value);
                }
                break;
            case "$nin":
                condition.put("operator", "NotContainsAny");
                if (value instanceof List) {
                    condition.put("valueTextArray", value);
                }
                break;
            default:
                return null;
        }

        return condition;
    }

    /**
     * 根据值的类型设置相应的字段
     */
    private void setValueByType(Map<String, Object> condition, Object value) {
        if (value == null) {
            return;
        }

        if (value instanceof String) {
            condition.put("valueString", value);
        } else if (value instanceof Integer) {
            condition.put("valueInt", value);
        } else if (value instanceof Long) {
            condition.put("valueInt", value);
        } else if (value instanceof Double) {
            condition.put("valueNumber", value);
        } else if (value instanceof Float) {
            condition.put("valueNumber", value);
        } else if (value instanceof Boolean) {
            condition.put("valueBoolean", value);
        }
    }

    /**
     * 创建 Builder
     */
    public static Builder builder(EmbeddingModel embeddingModel, String baseUrl) {
        return new Builder(embeddingModel, new WeaviateClient(baseUrl));
    }

    public static Builder builder(EmbeddingModel embeddingModel, String baseUrl, String token) {
        return new Builder(embeddingModel, new WeaviateClient(baseUrl, token));
    }

    public static Builder builder(EmbeddingModel embeddingModel, String baseUrl, String username, String password) {
        return new Builder(embeddingModel, new WeaviateClient(baseUrl, username, password));
    }

    public static Builder builder(EmbeddingModel embeddingModel, WeaviateClient client) {
        return new Builder(embeddingModel, client);
    }

    public static class Builder {
        private final EmbeddingModel embeddingModel;
        private final WeaviateClient client;

        private String collectionName = DEFAULT_COLLECTION_NAME;
        private List<MetadataField> metadataFields = new ArrayList<>();

        private Builder(EmbeddingModel embeddingModel, WeaviateClient client) {
            this.embeddingModel = embeddingModel;
            this.client = client;
        }

        public Builder collectionName(String collectionName) {
            this.collectionName = collectionName;
            return this;
        }


        public Builder metadataFields(List<MetadataField> metadataFields) {
            this.metadataFields = metadataFields;
            return this;
        }

        public Builder addMetadataField(MetadataField metadataField) {
            this.metadataFields.add(metadataField);
            return this;
        }

        public WeaviateRepository build() {
            return new WeaviateRepository(this);
        }
    }
}