package org.noear.solon.ai.rag.repository;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import org.noear.solon.Utils;
import org.noear.solon.ai.embedding.EmbeddingModel;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.RepositoryLifecycle;
import org.noear.solon.ai.rag.RepositoryStorable;
import org.noear.solon.ai.rag.repository.chroma.*;
import org.noear.solon.ai.rag.util.ListUtil;
import org.noear.solon.ai.rag.util.QueryCondition;
import org.noear.solon.ai.rag.util.SimilarityUtil;
import org.noear.solon.lang.Preview;

/**
 * Chroma 矢量存储知识库
 *
 * @author 小奶奶花生米
 * @since 3.1
 */
@Preview("3.1")
public class ChromaRepository implements RepositoryStorable, RepositoryLifecycle {
    private final Builder config;
    //集合ID
    private String collectionId;

    private ChromaRepository(Builder config) {
        this.config = config;

        try {
            initRepository();
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize Chroma repository: " + e.getMessage(), e);
        }
    }

    /**
     * 初始化仓库
     */
    public void initRepository() throws IOException {
        if (collectionId != null) {
            return;
        }

        // 尝试查找现有集合
        CollectionResponse collection = config.client.getCollectionStats(config.collectionName);

        if (collection != null) {
            collectionId = collection.getId();
        }

        if (collectionId != null) {
            return;
        }

        // 创建新集合
        createNewCollection();

        // 验证集合是否创建成功
        if (collectionId == null) {
            throw new IOException("Failed to create or find collection: " + config.collectionName);
        }
    }

    /**
     * 创建新集合
     *
     * @throws IOException 如果创建失败
     */
    private void createNewCollection() throws IOException {
        // 创建集合元数据
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("description", "Collection created by Solon AI");
        metadata.put("created_at", System.currentTimeMillis());
        metadata.put("hnsw:space", "cosine"); // 使用余弦相似度

        // 创建集合
        CollectionResponse response = config.client.createCollection(config.collectionName, metadata);

        // 获取集合ID
        this.collectionId = response.getId();
    }

    /**
     * 检查服务是否健康
     */
    public boolean isHealthy() {
        return config.client.isHealthy();
    }

    /**
     * 批量存储文档
     */
    @Override
    public void insert(List<Document> documents, BiConsumer<Integer, Integer> progressCallback) throws IOException {
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
                progressCallback.accept(batchIndex++, batchList.size());
            }
        }
    }

    /**
     * 添加文档到集合
     *
     * @param batch 文档列表
     * @throws IOException 如果添加失败
     */
    private void batchInsertDo(List<Document> batch) throws IOException {
        List<String> ids = new ArrayList<>();
        List<List<Float>> embeddings = new ArrayList<>();
        List<Map<String, Object>> metadatas = new ArrayList<>();
        List<String> contents = new ArrayList<>();

        for (Document doc : batch) {
            ids.add(doc.getId());

            // 转换嵌入向量
            List<Float> embedding = floatArrayToList(doc.getEmbedding());
            embeddings.add(embedding);

            // 准备元数据
            Map<String, Object> metadata = new HashMap<>(doc.getMetadata());
            if (!Utils.isEmpty(doc.getUrl())) {
                metadata.put("url", doc.getUrl());
            }
            metadatas.add(metadata);

            // 添加内容
            contents.add(doc.getContent());
        }

        // 添加文档到集合
        config.client.addDocuments(collectionId, ids, embeddings, contents, metadatas);
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
        config.client.deleteDocuments(collectionId, idList);
    }

    /**
     * 检查文档是否存在
     */
    @Override
    public boolean exists(String id) throws IOException {
        if (Utils.isEmpty(id)) {
            return false;
        }

        return config.client.documentExists(collectionId, id);
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

            // 获取过滤表达式并转换为Chroma支持的格式
            Map<String, Object> filter = FilterTransformer.getInstance().transform(condition.getFilterExpression());

            // 执行查询
            QueryResponse response = config.client.queryDocuments(
                    collectionId,
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

        // 获取结果数据
        List<List<String>> ids = response.getIds();
        List<List<String>> documents = response.getDocuments();
        List<List<Map<String, Object>>> metadatas = response.getMetadatas();
        List<List<BigDecimal>> distances = response.getDistances();

        // 检查结果是否为空
        if (ids == null || ids.isEmpty()) {
            return results;
        }


        // 处理每个批次的结果
        for (int batchIndex = 0; batchIndex < ids.size(); batchIndex++) {
            List<String> batchIds = ids.get(batchIndex);

            if (batchIds.isEmpty()) {
                continue;
            }

            List<String> batchDocuments = documents.get(batchIndex);
            List<Map<String, Object>> batchMetadatas = metadatas.get(batchIndex);
            List<BigDecimal> batchDistances = distances.get(batchIndex);

            for (int i = 0; i < batchIds.size(); i++) {
                String id = batchIds.get(i);
                String content = batchDocuments.get(i);
                Map<String, Object> metadata = batchMetadatas.get(i);
                BigDecimal distance = batchDistances.get(i);

                // 计算相似度分数 (1 - 距离)，确保分数在0-1之间
                double score = 1.0 - Math.min(1.0, Math.max(0.0, distance.doubleValue()));

                Document doc = new Document(id, content, metadata, score);

                // 如果元数据中有URL，设置到文档
                if (metadata.containsKey("url")) {
                    doc.url((String) metadata.get("url"));
                }

                results.add(doc);
            }
        }

        return results;
    }

    /**
     * 注销仓库
     *
     * @throws IOException 如果注销过程发生IO错误
     */
    public void dropRepository() throws IOException {
        if (collectionId != null) {
            config.client.deleteCollection(collectionId);
            collectionId = null;
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

    public static Builder builder(EmbeddingModel embeddingModel, ChromaClient client) {
        return new Builder(embeddingModel, client);
    }

    public static class Builder {
        /**
         * 向量模型，用于将文档内容转换为向量表示
         */
        private final EmbeddingModel embeddingModel;

        /**
         * Chroma API 客户端
         */
        private final ChromaClient client;

        /**
         * 集合名称，用于存储文档
         */
        private String collectionName = "solon_ai";

        private Builder(EmbeddingModel embeddingModel, ChromaClient client) {
            this.embeddingModel = embeddingModel;
            this.client = client;
        }

        public Builder collectionName(String collectionName) {
            this.collectionName = collectionName;
            return this;
        }

        public ChromaRepository build() {
            return new ChromaRepository(this);
        }
    }
}