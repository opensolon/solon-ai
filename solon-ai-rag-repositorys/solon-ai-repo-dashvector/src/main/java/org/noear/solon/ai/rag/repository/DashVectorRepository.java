package org.noear.solon.ai.rag.repository;


import org.noear.solon.Utils;
import org.noear.solon.ai.embedding.EmbeddingModel;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.RepositoryLifecycle;
import org.noear.solon.ai.rag.RepositoryStorable;
import org.noear.solon.ai.rag.repository.dashvector.*;
import org.noear.solon.ai.rag.util.ListUtil;
import org.noear.solon.ai.rag.util.QueryCondition;
import org.noear.solon.ai.rag.util.SimilarityUtil;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 阿里云向量数据库 DashVector
 *
 * @author 小奶奶花生米
 */
public class DashVectorRepository implements RepositoryStorable, RepositoryLifecycle {
    private final Builder config;
    private String collectionName;

    private static final String CONTENT_FIELD_KEY = "__content";
    private static final String URL_FIELD_KEY = "__url";

    private DashVectorRepository(Builder config) {
        this.config = config;

        try {
            initRepository();
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize DashVector repository: " + e.getMessage(), e);
        }
    }

    /**
     * 初始化仓库
     */
    public void initRepository() throws IOException {
        if (collectionName != null) {
            return;
        }

        // 尝试查找现有集合
        ListCollectionsResponse collection = config.client.listCollections();

        if (collection.getOutput() != null && collection.getOutput().contains(config.collectionName)) {
            return;
        }

        // 创建新集合
        createNewCollection();
    }


    /**
     * 创建新集合
     *
     * @throws IOException 如果创建失败
     */
    private void createNewCollection() throws IOException {
        // 创建集合
        Map<String, String> fieldsSchema = new HashMap<>();
        fieldsSchema.put(CONTENT_FIELD_KEY, FieldType.STRING.getName());
        fieldsSchema.put(URL_FIELD_KEY, FieldType.STRING.getName());
        if (config.metadataFields != null) {
            Map<String, String> addFields = config.metadataFields.stream().collect(Collectors.toMap(MetadataField::getName, metadataField -> metadataField.getFieldType().getName(), (v1, v2) -> v1));
            fieldsSchema.putAll(addFields);
        }
        config.client.createCollection(config.collectionName, config.embeddingModel.dimensions(), fieldsSchema);
        this.collectionName = config.collectionName;
    }

    /**
     * 批量存储文档
     */
    @Override
    public void insert(List<Document> documents) throws IOException {
        if (Utils.isEmpty(documents)) {
            return;
        }

        // 确保所有文档都有ID
        for (Document doc : documents) {
            if (Utils.isEmpty(doc.getId())) {
                doc.id(Utils.uuid());
            }
        }

        // 分批处理
        for (List<Document> batch : ListUtil.partition(documents, config.embeddingModel.batchSize())) {
            config.embeddingModel.embed(batch);
            addDocuments(batch);
        }
    }

    /**
     * 添加文档到集合
     *
     * @param documents 文档列表
     * @throws IOException 如果添加失败
     */
    private void addDocuments(List<Document> documents) throws IOException {
        List<Doc> docs = new ArrayList<>();
        Doc doc;
        Map<String, Object> map;
        for (Document document : documents) {
            map = document.getMetadata();
            map.put(CONTENT_FIELD_KEY, document.getContent());
            if (!Utils.isEmpty(document.getUrl())) {
                map.put(URL_FIELD_KEY, document.getUrl());
            }
            doc = new Doc(document.getId(), floatArrayToList(document.getEmbedding()), document.getMetadata());
            docs.add(doc);
        }

        // 添加文档到集合
        config.client.addDocuments(config.collectionName, docs);
    }

    /**
     * 删除指定ID的文档
     */
    @Override
    public void delete(String... ids) throws IOException {
        if (ids == null || ids.length == 0) {
            return;
        }

        List<String> idList = new ArrayList<>(Arrays.asList(ids));

        // 删除文档
        config.client.deleteDocuments(config.collectionName, idList);
    }

    /**
     * 检查文档是否存在
     */
    @Override
    public boolean exists(String id) throws IOException {
        if (Utils.isEmpty(id)) {
            return false;
        }

        return config.client.documentExists(config.collectionName, id);
    }

    /**
     * 搜索文档
     */
    @Override
    public List<Document> search(QueryCondition condition) throws IOException {
        // 如果查询条件为空，返回空列表
        if (condition == null || condition.getQuery() == null) {
            return new ArrayList<>();
        }

        // 使用文本查询生成向量
        float[] embedding = config.embeddingModel.embed(condition.getQuery());

        try {
            // 将float[]转换为List<Float>
            List<Float> queryVector = floatArrayToList(embedding);

            // 获取过滤表达式并转换为DashVector支持的格式
            String filter = FilterTransformer.getInstance().transform(condition.getFilterExpression());

            // 执行查询
            QueryResponse response = config.client.queryDocuments(
                    config.collectionName,
                    queryVector,
                    condition.getLimit(),
                    filter);

            // 解析查询结果
            List<Document> result = parseQueryResponse(response);

            // 再次过滤和排序
            return SimilarityUtil.refilter(result.stream(), condition);
        } catch (Exception e) {
            throw new IOException("Failed to search documents: " + e.getMessage(), e);
        }
    }

    /**
     * 解析查询响应
     *
     * @param response 查询响应对象
     * @return 文档列表
     */
    private List<Document> parseQueryResponse(QueryResponse response) {
        List<Document> results = new ArrayList<>();

        // 检查是否有错误
        if (response.hasError()) {
            return results;
        }

        Document document;
        Map<String, Object> fields;
        String content;
        for (Doc doc : response.getOutput()) {
            fields = doc.getFields();
            content = (String) fields.get(CONTENT_FIELD_KEY);
            fields.remove(CONTENT_FIELD_KEY);
            // 计算相似度分数 (1 - 距离)，确保分数在0-1之间
            double score = 1.0 - Math.min(1.0, Math.max(0.0, doc.getScore()));
            document = new Document(doc.getId(), content, fields, score);
            if (fields.containsKey(URL_FIELD_KEY)) {
                document.url((String) fields.get(URL_FIELD_KEY));
            }
            results.add(document);
        }

        return results;
    }

    /**
     * 注销仓库
     *
     * @throws IOException 如果注销过程发生IO错误
     */
    public void dropRepository() throws IOException {
        if (this.collectionName != null) {
            config.client.deleteCollection(this.collectionName);
            this.collectionName = null;
        }
    }

    /**
     * 将float数组转换为Float列表
     */
    private List<Float> floatArrayToList(float[] array) {
        if (array == null) {
            return new ArrayList<>();
        }

        List<Float> list = new ArrayList<>(array.length);
        for (float f : array) {
            list.add(f);
        }
        return list;
    }

    public static Builder builder(EmbeddingModel embeddingModel, DashVectorClient client) {
        return new Builder(embeddingModel, client);
    }

    public static class Builder {
        /**
         * 向量模型，用于将文档内容转换为向量表示
         */
        private final EmbeddingModel embeddingModel;

        /**
         * DashVector API 客户端
         */
        private final DashVectorClient client;

        /**
         * metadata索引类型
         */
        private List<MetadataField> metadataFields = new ArrayList<>();

        /**
         * 集合名称，用于存储文档
         */
        private String collectionName = "solon_ai";

        private Builder(EmbeddingModel embeddingModel, DashVectorClient client) {
            this.embeddingModel = embeddingModel;
            this.client = client;
        }

        public Builder collectionName(String collectionName) {
            this.collectionName = collectionName;
            return this;
        }

        /**
         * @deprecated 3.2 {@link #metadataFields(List)}
         */
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

        public DashVectorRepository build() {
            return new DashVectorRepository(this);
        }
    }
}
