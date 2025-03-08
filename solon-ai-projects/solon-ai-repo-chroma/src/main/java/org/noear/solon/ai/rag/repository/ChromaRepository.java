package org.noear.solon.ai.rag.repository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.noear.snack.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.embedding.EmbeddingModel;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.RepositoryStorable;
import org.noear.solon.ai.rag.util.ListUtil;
import org.noear.solon.ai.rag.util.QueryCondition;
import org.noear.solon.lang.Preview;
import org.noear.solon.net.http.HttpUtils;

/**
 * Chroma 矢量存储知识库
 *
 * @author 小奶奶花生米
 * @since 3.1
 */
@Preview("3.1")
public class ChromaRepository implements RepositoryStorable {
    /**
     * 向量模型，用于将文档内容转换为向量表示
     */
    private final EmbeddingModel embeddingModel;

    /**
     * Chroma 服务器地址
     */
    private final String serverUrl;

    /**
     * 集合名称，用于存储文档
     */
    private final String collectionName;

    /**
     * 构造函数
     *
     * @param embeddingModel 向量模型，用于生成文档的向量表示
     * @param serverUrl      Chroma 服务器地址
     * @param collectionName 集合名称
     */
    public ChromaRepository(EmbeddingModel embeddingModel, String serverUrl, String collectionName) {
        this.embeddingModel = embeddingModel;
        this.serverUrl = serverUrl.endsWith("/") ? serverUrl : serverUrl + "/";
        this.collectionName = collectionName;
        initRepository();
    }

    /**
     * 初始化仓库
     */
    public void initRepository() {
        try {
            // 检查集合是否存在
            if (!collectionExists()) {
                // 创建集合
                createCollection();
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize Chroma collection", e);
        }
    }

    /**
     * 检查集合是否存在
     *
     * @return 集合是否存在
     * @throws IOException 如果检查过程发生IO错误
     */
    private boolean collectionExists() throws IOException {
        String endpoint = serverUrl + "api/v1/collections";
        String response = HttpUtils.http(endpoint)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .get();
        
        Map<String, Object> responseMap = ONode.loadStr(response).toObject(Map.class);
        List<Map<String, Object>> collections = (List<Map<String, Object>>) responseMap.get("collections");
        
        for (Map<String, Object> collection : collections) {
            if (collectionName.equals(collection.get("name"))) {
                return true;
            }
        }
        
        return false;
    }

    /**
     * 创建集合
     *
     * @throws IOException 如果创建过程发生IO错误
     */
    private void createCollection() throws IOException {
        String endpoint = serverUrl + "api/v1/collections";
        
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("name", collectionName);
        requestBody.put("metadata", new HashMap<>());
        
        String jsonBody = ONode.stringify(requestBody);
        
        try {
            HttpUtils.http(endpoint)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .bodyTxt(jsonBody)
                    .post();
        } catch (Exception e) {
            throw new IOException("Failed to create collection: " + e.getMessage(), e);
        }
    }

    /**
     * 批量存储文档
     *
     * @param documents 要存储的文档列表
     * @throws IOException 如果存储过程中发生IO错误
     */
    @Override
    public void insert(List<Document> documents) throws IOException {
        if (Utils.isEmpty(documents)) {
            return;
        }

        // 批量embedding
        for (List<Document> batch : ListUtil.partition(documents, 20)) {
            embeddingModel.embed(batch);
            addDocuments(batch);
        }
    }

    /**
     * 添加文档到Chroma
     *
     * @param documents 文档列表
     * @throws IOException 如果添加过程发生IO错误
     */
    private void addDocuments(List<Document> documents) throws IOException {
        String endpoint = serverUrl + "api/v1/collections/" + collectionName + "/add";
        
        Map<String, Object> requestBody = new HashMap<>();
        
        List<String> ids = new ArrayList<>();
        List<List<Float>> embeddings = new ArrayList<>();
        List<Map<String, Object>> metadatas = new ArrayList<>();
        List<String> documents_content = new ArrayList<>();

        for (Document doc : documents) {
            if (doc.getId() == null) {
                doc.id(Utils.uuid());
            }
            
            ids.add(doc.getId());
            
            float[] embedding = doc.getEmbedding();
            List<Float> embeddingList = floatArrayToList(embedding);
            embeddings.add(embeddingList);
            
            Map<String, Object> metadata = new HashMap<>(doc.getMetadata());
            if (doc.getUrl() != null) {
                metadata.put("url", doc.getUrl());
            }
            metadatas.add(metadata);
            
            documents_content.add(doc.getContent());
        }

        requestBody.put("ids", ids);
        requestBody.put("embeddings", embeddings);
        requestBody.put("metadatas", metadatas);
        requestBody.put("documents", documents_content);

        String jsonBody = ONode.stringify(requestBody);
        
        try {
            HttpUtils.http(endpoint)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .bodyTxt(jsonBody)
                    .post();
        } catch (Exception e) {
            throw new IOException("Failed to add documents: " + e.getMessage(), e);
        }
    }

    /**
     * 删除指定ID的文档
     *
     * @param ids 要删除的文档ID
     * @throws IOException 如果删除过程发生IO错误
     */
    @Override
    public void delete(String... ids) throws IOException {
        if (ids == null || ids.length == 0) {
            return;
        }

        String endpoint = serverUrl + "api/v1/collections/" + collectionName + "/delete";
        
        Map<String, Object> requestBody = new HashMap<>();
        List<String> idsList = new ArrayList<>();
        for (String id : ids) {
            idsList.add(id);
        }
        requestBody.put("ids", idsList);

        String jsonBody = ONode.stringify(requestBody);
        
        try {
            HttpUtils.http(endpoint)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .bodyTxt(jsonBody)
                    .post();
        } catch (Exception e) {
            throw new IOException("Failed to delete documents: " + e.getMessage(), e);
        }
    }

    /**
     * 检查文档是否存在
     *
     * @param id 文档ID
     * @return 文档是否存在
     * @throws IOException 如果检查过程发生IO错误
     */
    @Override
    public boolean exists(String id) throws IOException {
        String endpoint = serverUrl + "api/v1/collections/" + collectionName + "/get";
        
        Map<String, Object> requestBody = new HashMap<>();
        List<String> ids = new ArrayList<>();
        ids.add(id);
        requestBody.put("ids", ids);

        String jsonBody = ONode.stringify(requestBody);
        
        try {
            String response = HttpUtils.http(endpoint)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .bodyTxt(jsonBody)
                    .post();
            
            Map<String, Object> responseMap = ONode.loadStr(response).toObject(Map.class);
            List<String> responseIds = (List<String>) responseMap.get("ids");
            return responseIds != null && !responseIds.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 搜索文档
     *
     * @param condition 查询条件
     * @return 匹配的文档列表
     * @throws IOException 如果搜索过程发生IO错误
     */
    @Override
    public List<Document> search(QueryCondition condition) throws IOException {
        return searchWithMetadataFilter(condition, null);
    }

    /**
     * 搜索文档，支持元数据过滤
     */
    public List<Document> searchWithMetadataFilter(QueryCondition condition, Map<String, Object> metadataFilter) throws IOException {
        // 如果查询条件为空，返回空列表
        if (condition == null || condition.getQuery() == null) {
            return new ArrayList<>();
        }

        // 使用文本查询生成向量
        Document queryDoc = new Document(condition.getQuery(), new HashMap<>());
        List<Document> docList = new ArrayList<>();
        docList.add(queryDoc);
        embeddingModel.embed(docList);
        
        // float[] 转换为 List<Float>
        float[] embedding = queryDoc.getEmbedding();
        List<Float> queryEmbedding = floatArrayToList(embedding);

        // 执行向量搜索，带元数据过滤
        List<Document> results = queryByVectorWithFilter(queryEmbedding, condition.getLimit(), metadataFilter);
        
        // 应用过滤器（如果有）
        if (condition.getFilter() != null) {
            List<Document> filteredResults = new ArrayList<>();
            for (Document doc : results) {
                if (condition.getFilter().test(doc)) {
                    filteredResults.add(doc);
                }
            }
            return filteredResults;
        }
        
        return results;
    }

    /**
     * 通过向量查询文档，支持元数据过滤
     */
    private List<Document> queryByVectorWithFilter(List<Float> queryVector, int limit, Map<String, Object> metadataFilter) throws IOException {
        String endpoint = serverUrl + "api/v1/collections/" + collectionName + "/query";
        
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("query_embeddings", queryVector);
        requestBody.put("n_results", limit > 0 ? limit : 10);
        requestBody.put("include", new String[]{"documents", "metadatas", "distances"});
        
        // 添加元数据过滤
        if (metadataFilter != null && !metadataFilter.isEmpty()) {
            requestBody.put("where", metadataFilter);
        }

        String jsonBody = ONode.stringify(requestBody);
        
        try {
            String response = HttpUtils.http(endpoint)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .bodyTxt(jsonBody)
                    .post();
            
            return parseQueryResponse(response);
        } catch (Exception e) {
            throw new IOException("Failed to query documents: " + e.getMessage(), e);
        }
    }

    /**
     * 解析查询响应
     *
     * @param responseBody 响应JSON字符串
     * @return 文档列表
     */
    private List<Document> parseQueryResponse(String responseBody) {
        Map<String, Object> responseMap = ONode.loadStr(responseBody).toObject(Map.class);
        List<Document> results = new ArrayList<>();

        List<List<String>> ids = (List<List<String>>) responseMap.get("ids");
        List<List<String>> documents = (List<List<String>>) responseMap.get("documents");
        List<List<Map<String, Object>>> metadatas = (List<List<Map<String, Object>>>) responseMap.get("metadatas");
        List<List<Double>> distances = (List<List<Double>>) responseMap.get("distances");

        if (ids != null && !ids.isEmpty() && !ids.get(0).isEmpty()) {
            List<String> batchIds = ids.get(0);
            List<String> batchDocuments = documents.get(0);
            List<Map<String, Object>> batchMetadatas = metadatas.get(0);
            List<Double> batchDistances = distances.get(0);

            for (int i = 0; i < batchIds.size(); i++) {
                String id = batchIds.get(i);
                String content = batchDocuments.get(i);
                Map<String, Object> metadata = batchMetadatas.get(i);
                Double distance = batchDistances.get(i);

                // 计算相似度分数 (1 - 距离)，确保分数在0-1之间
                double score = 1.0 - Math.min(1.0, Math.max(0.0, distance));
                
                // 添加评分到元数据
                metadata.put("score", score);
                
                Document doc = new Document(content, metadata);
                doc.id(id);
                
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
        String endpoint = serverUrl + "api/v1/collections/" + collectionName;
        
        try {
            HttpUtils.http(endpoint)
                    .header("Accept", "application/json")
                    .delete();
        } catch (Exception e) {
            throw new IOException("Failed to drop collection: " + e.getMessage(), e);
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

    /**
     * 检查Chroma服务是否可用
     */
    public boolean isHealthy() {
        try {
            String endpoint = serverUrl + "api/v1/heartbeat";
            HttpUtils.http(endpoint).get();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取集合统计信息
     */
    public Map<String, Object> getCollectionStats() throws IOException {
        String endpoint = serverUrl + "api/v1/collections/" + collectionName;
        
        try {
            String response = HttpUtils.http(endpoint)
                    .header("Accept", "application/json")
                    .get();
            
            return ONode.loadStr(response).toObject(Map.class);
        } catch (Exception e) {
            throw new IOException("Failed to get collection stats: " + e.getMessage(), e);
        }
    }

    /**
     * 异步批量存储文档
     * 
     * @param documents 要存储的文档列表
     * @return 异步任务
     */
    public Runnable insertAsync(List<Document> documents) {
        return () -> {
            try {
                insert(documents);
            } catch (IOException e) {
                throw new RuntimeException("Failed to insert documents asynchronously", e);
            }
        };
    }
}
