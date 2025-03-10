package org.noear.solon.ai.rag.repository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.noear.snack.ONode;
import org.noear.solon.net.http.HttpUtils;

/**
 * Chroma API 客户端
 *
 * @author 小奶奶花生米
 * @since 3.1
 */
public class ChromaApi {
    private static final Logger logger = Logger.getLogger(ChromaApi.class.getName());

    /**
     * Chroma 服务器地址
     */
    private final String serverUrl;

    /**
     * 构造函数
     *
     * @param serverUrl Chroma 服务器地址
     */
    public ChromaApi(String serverUrl) {
        this.serverUrl = serverUrl.endsWith("/") ? serverUrl : serverUrl + "/";
    }

    /**
     * 检查服务是否健康
     *
     * @return 是否健康
     */
    public boolean isHealthy() {
        try {
            String endpoint = serverUrl + "api/v1/heartbeat";
            String response = HttpUtils.http(endpoint)
                    .header("Accept", "application/json")
                    .get();

            Map<String, Object> responseMap = ONode.loadStr(response).toObject(Map.class);
            return responseMap.containsKey("nanosecond heartbeat");
        } catch (Exception e) {
            logger.warning("Health check failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * 获取所有集合
     *
     * @return 集合列表
     * @throws IOException 如果请求失败
     */
    public CollectionsResponse listCollections() throws IOException {
        try {
            String endpoint = serverUrl + "api/v1/collections";
            String response = HttpUtils.http(endpoint)
                    .header("Accept", "application/json")
                    .get();

            CollectionsResponse collectionsResponse = ONode.loadStr(response).toObject(CollectionsResponse.class);

            if (collectionsResponse.hasError()) {
                throw new IOException("Error listing collections: " + collectionsResponse.getMessage());
            }

            return collectionsResponse;
        } catch (Exception e) {
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            throw new IOException("Failed to list collections: " + e.getMessage(), e);
        }
    }

    /**
     * 创建集合
     *
     * @param name 集合名称
     * @param metadata 集合元数据
     * @return 集合ID
     * @throws IOException 如果创建失败
     */
    public CollectionResponse createCollection(String name, Map<String, Object> metadata) throws IOException {
        try {
            String endpoint = serverUrl + "api/v1/collections";

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("name", name);

            if (metadata != null) {
                requestBody.put("metadata", metadata);
            } else {
                // 提供默认元数据
                Map<String, Object> defaultMetadata = new HashMap<>();
                defaultMetadata.put("description", "Collection created by Solon AI");
                defaultMetadata.put("created_at", System.currentTimeMillis());
                defaultMetadata.put("hnsw:space", "cosine");
                requestBody.put("metadata", defaultMetadata);
            }

            String jsonBody = ONode.stringify(requestBody);

            String responseStr = HttpUtils.http(endpoint)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .bodyOfTxt(jsonBody)
                    .post();

            CollectionResponse response = ONode.loadStr(responseStr).toObject(CollectionResponse.class);

            if (response.hasError()) {
                throw new IOException("Failed to create collection: " + response.getMessage());
            }

            return response;
        } catch (Exception e) {
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            throw new IOException("Failed to create collection: " + e.getMessage(), e);
        }
    }

    /**
     * 获取集合信息
     *
     * @param collectionId 集合ID
     * @return 集合信息
     * @throws IOException 如果请求失败
     */
    public CollectionResponse getCollectionStats(String collectionId) throws IOException {
        try {
            String endpoint = serverUrl + "api/v1/collections/" + collectionId;

            String responseStr = HttpUtils.http(endpoint)
                    .header("Accept", "application/json")
                    .get();

            CollectionResponse response = ONode.loadStr(responseStr).toObject(CollectionResponse.class);

            if (response.hasError()) {
                throw new IOException("Failed to get collection stats: " + response.getMessage());
            }

            return response;
        } catch (Exception e) {
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            throw new IOException("Failed to get collection stats: " + e.getMessage(), e);
        }
    }

    /**
     * 删除集合
     *
     * @param collectionId 集合ID
     * @throws IOException 如果删除失败
     */
    public void deleteCollection(String collectionId) throws IOException {
        try {
            String endpoint = serverUrl + "api/v1/collections/" + collectionId;

            String responseStr = HttpUtils.http(endpoint)
                    .header("Accept", "application/json")
                    .delete();

            // 处理空响应
            if (responseStr == null || responseStr.trim().isEmpty()) {
                // 空响应通常表示成功
                return;
            }

            // 有些API返回布尔值而不是对象，需要特殊处理
            if ("true".equals(responseStr) || "false".equals(responseStr)) {
                boolean success = Boolean.parseBoolean(responseStr);
                if (!success) {
                    throw new IOException("Failed to delete collection");
                }
                return;
            }

            // 尝试解析为ChromaResponse
            try {
                ChromaResponse response = ONode.loadStr(responseStr).toObject(ChromaResponse.class);
                if (response.hasError()) {
                    throw new IOException("Failed to delete collection: " + response.getMessage());
                }
            } catch (Exception e) {
                // 如果无法解析为ChromaResponse，检查是否为成功响应
                if (!responseStr.contains("true") && !responseStr.contains("success")) {
                    throw new IOException("Failed to delete collection: " + responseStr);
                }
            }
        } catch (Exception e) {
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            throw new IOException("Failed to delete collection: " + e.getMessage(), e);
        }
    }

    /**
     * 添加文档
     *
     * @param collectionId 集合ID
     * @param ids 文档ID列表
     * @param embeddings 文档向量列表
     * @param documents 文档内容列表
     * @param metadatas 文档元数据列表
     * @throws IOException 如果添加失败
     */
    public void addDocuments(String collectionId, List<String> ids, List<List<Float>> embeddings,
                             List<String> documents, List<Map<String, Object>> metadatas) throws IOException {
        try {
            String endpoint = serverUrl + "api/v1/collections/" + collectionId + "/add";

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("ids", ids);
            requestBody.put("embeddings", embeddings);

            if (documents != null && !documents.isEmpty()) {
                requestBody.put("documents", documents);
            }

            if (metadatas != null && !metadatas.isEmpty()) {
                requestBody.put("metadatas", metadatas);
            }

            String jsonBody = ONode.stringify(requestBody);

            String responseStr = HttpUtils.http(endpoint)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .bodyOfTxt(jsonBody)
                    .post();

            // 处理空响应
            if (responseStr == null || responseStr.trim().isEmpty()) {
                // 空响应通常表示成功
                return;
            }

            // 有些API返回布尔值而不是对象，需要特殊处理
            if ("true".equals(responseStr) || "false".equals(responseStr)) {
                boolean success = Boolean.parseBoolean(responseStr);
                if (!success) {
                    throw new IOException("Failed to add documents");
                }
                return;
            }

            // 尝试解析为Map
            try {
                Map<String, Object> response = ONode.loadStr(responseStr).toObject(Map.class);
                if (response.containsKey("error")) {
                    throw new IOException("Failed to add documents: " + response.get("message"));
                }
            } catch (Exception e) {
                // 如果无法解析为Map，检查是否为成功响应
                if (!responseStr.contains("true") && !responseStr.contains("success")) {
                    throw new IOException("Failed to add documents: " + responseStr);
                }
            }
        } catch (Exception e) {
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            throw new IOException("Failed to add documents: " + e.getMessage(), e);
        }
    }

    /**
     * 删除文档
     *
     * @param collectionId 集合ID
     * @param ids 文档ID列表
     * @throws IOException 如果删除失败
     */
    public void deleteDocuments(String collectionId, List<String> ids) throws IOException {
        try {
            String endpoint = serverUrl + "api/v1/collections/" + collectionId + "/delete";

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("ids", ids);

            String jsonBody = ONode.stringify(requestBody);

            // Chroma API在成功时不返回任何内容
           HttpUtils.http(endpoint)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .bodyOfTxt(jsonBody)
                    .post();

        } catch (Exception e) {
            throw new IOException("Failed to delete documents: " + e.getMessage(), e);
        }
    }

    /**
     * 检查文档是否存在
     *
     * @param collectionId 集合ID
     * @param id 文档ID
     * @return 是否存在
     * @throws IOException 如果请求失败
     */
    public boolean documentExists(String collectionId, String id) throws IOException {
        try {
            String endpoint = serverUrl + "api/v1/collections/" + collectionId + "/get";

            Map<String, Object> requestBody = new HashMap<>();
            List<String> ids = new ArrayList<>();
            ids.add(id);
            requestBody.put("ids", ids);

            String jsonBody = ONode.stringify(requestBody);

            String responseStr = HttpUtils.http(endpoint)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .bodyOfTxt(jsonBody)
                    .post();

            GetResponse response = ONode.loadStr(responseStr).toObject(GetResponse.class);

            if (response.hasError()) {
                return false;
            }

            List<String> responseIds = response.getIds();
            return responseIds != null && !responseIds.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 查询文档
     *
     * @param collectionId 集合ID
     * @param queryEmbedding 查询向量
     * @param limit 结果数量限制
     * @param metadataFilter 元数据过滤条件
     * @return 查询响应
     * @throws IOException 如果查询失败
     */
    public QueryResponse queryDocuments(String collectionId, List<Float> queryEmbedding,
                                        int limit, Map<String, Object> metadataFilter) throws IOException {
        try {
            String endpoint = serverUrl + "api/v1/collections/" + collectionId + "/query";

            Map<String, Object> requestBody = new HashMap<>();

            // 创建嵌套的查询向量列表
            List<List<Float>> queryEmbeddings = new ArrayList<>();
            queryEmbeddings.add(queryEmbedding);
            requestBody.put("query_embeddings", queryEmbeddings);

            requestBody.put("n_results", limit > 0 ? limit : 10);
            requestBody.put("include", new String[]{"documents", "metadatas", "distances"});

            // 添加元数据过滤
            if (metadataFilter != null && !metadataFilter.isEmpty()) {
                requestBody.put("where", metadataFilter);
            }

            String jsonBody = ONode.stringify(requestBody);

            String response = HttpUtils.http(endpoint)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .bodyOfTxt(jsonBody)
                    .post();

            return ONode.loadStr(response).toObject(QueryResponse.class);
        } catch (Exception e) {
            throw new IOException("Failed to query documents: " + e.getMessage(), e);
        }
    }
}
