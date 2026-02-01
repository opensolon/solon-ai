package org.noear.solon.ai.rag.repository.chroma;

import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;

import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.core.util.MultiMap;
import org.noear.solon.net.http.HttpUtils;

/**
 * Chroma API 客户端
 * 自动检测并支持 v1 和 v2 API 版本，默认使用 v2
 *
 * @author 小奶奶花生米
 * @since 3.1
 */
public class ChromaClient {
    private static final Logger logger = Logger.getLogger(ChromaClient.class.getName());

    //Chroma 服务器地址与账号
    private final String baseUrl;
    private final MultiMap<String> headers = new MultiMap<>();
    private final StringSerializer serializer = new StringSerializer();

    // API 版本配置
    private final String apiVersion;
    private final String tenant;
    private final String database;

    // 默认值常量
    private static final String DEFAULT_TENANT = "default_tenant";
    private static final String DEFAULT_DATABASE = "default_database";


    public ChromaClient(String baseUrl) {
        this(baseUrl, DEFAULT_TENANT, DEFAULT_DATABASE);
    }

    public ChromaClient(String baseUrl, String tenant, String database) {
        if (Utils.isEmpty(baseUrl)) {
            throw new IllegalArgumentException("The baseurl cannot be empty.");
        }

        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        
        // 自动检测服务器版本，默认使用 v2
        this.apiVersion = detectApiVersion();
        logger.info("Auto-detected Chroma API version: " + this.apiVersion);
        
        this.tenant = Utils.isEmpty(tenant) ? DEFAULT_TENANT : tenant;
        this.database = Utils.isEmpty(database) ? DEFAULT_DATABASE : database;
    }

    public ChromaClient(Properties properties) {
        this(
            properties.getProperty("url"),
            properties.getProperty("tenant", DEFAULT_TENANT),
            properties.getProperty("database", DEFAULT_DATABASE)
        );
    }

    /**
     * 自动检测 Chroma 服务器的 API 版本
     * 优先尝试 v2 API，如果不可用则降级到 v1
     *
     * @return 检测到的 API 版本（v1 或 v2）
     */
    private String detectApiVersion() {
        try {
            // 优先尝试访问 v2 API 的心跳端点
            String v2Endpoint = baseUrl + "api/v2/heartbeat";
            HttpUtils httpUtils = HttpUtils.http(v2Endpoint)
                    .serializer(serializer)
                    .headers(headers)
                    .header("Accept", "application/json");
            
            String response = httpUtils.get();
            
            // 如果 v2 API 响应成功，则使用 v2
            if (response != null && !response.isEmpty()) {
                try {
                    Map<String, Object> responseMap = ONode.deserialize(response, Map.class);
                    if (responseMap.containsKey("nanosecond heartbeat")) {
                        return "v2";
                    }
                } catch (Exception e) {
                    // 解析失败，继续尝试其他方式
                }
            }
        } catch (Exception e) {
            logger.info("v2 API not available, falling back to v1: " + e.getMessage());
        }
        
        // 如果 v2 API 不可用，降级到 v1
        return "v1";
    }

    /**
     * 设置基础鉴权
     */
    public void setBasicAuth(String username, String password) {
        String plainCredentials = username + ":" + password;
        String base64Credentials = Base64.getEncoder().encodeToString(plainCredentials.getBytes());
        headers.put("Authorization", "Basic " + base64Credentials);
    }

    /**
     * 设置令牌鉴权
     */
    public void setBearerAuth(String token) {
        headers.put("Authorization", "Bearer " + token);
    }

    /**
     * 构建 http 工具
     */
    private HttpUtils http(String endpoint) {
        HttpUtils httpUtils = HttpUtils.http(endpoint)
                .serializer(serializer)
                .headers(headers)
                .header("Accept", "application/json");

        return httpUtils;
    }

    /**
     * 构建 API 端点路径
     * 根据 API 版本自动选择 v1 或 v2 路径格式
     *
     * @param pathParts 路径部分数组
     * @return 完整的 API 端点 URL
     */
    private String buildEndpoint(String... pathParts) {
        if ("v1".equals(apiVersion)) {
            // v1 API 格式: /api/v1/{path}
            return baseUrl + "api/v1/" + String.join("/", pathParts);
        } else if ("v2".equals(apiVersion)) {
            // v2 API 格式: /api/v2/tenants/{tenant}/databases/{database}/{path}
            String basePath = "api/v2/tenants/" + tenant + "/databases/" + database;
            if (pathParts == null || pathParts.length == 0) {
                return baseUrl + basePath;
            }
            return baseUrl + basePath + "/" + String.join("/", pathParts);
        } else {
            throw new IllegalArgumentException("Unsupported API version: " + apiVersion);
        }
    }

    /**
     * 检查服务是否健康
     *
     * @return 是否健康
     */
    public boolean isHealthy() {
        try {
            // heartbeat 端点不包含 tenant 和 database 路径
            String endpoint;
            if ("v1".equals(apiVersion)) {
                endpoint = baseUrl + "api/v1/heartbeat";
            } else if ("v2".equals(apiVersion)) {
                endpoint = baseUrl + "api/v2/heartbeat";
            } else {
                return false;
            }
            
            String response = http(endpoint).get();

            Map<String, Object> responseMap = ONode.deserialize(response, Map.class);
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
            String endpoint = buildEndpoint("collections");

            CollectionsResponse response = http(endpoint).getAs(CollectionsResponse.class);

            if (response.hasError()) {
                throw new IOException("Error listing collections: " + response.getMessage());
            }

            return response;
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
     * @param name     集合名称
     * @param metadata 集合元数据
     * @return 集合ID
     * @throws IOException 如果创建失败
     */
    public CollectionResponse createCollection(String name, Map<String, Object> metadata) throws IOException {
        try {
            String endpoint = buildEndpoint("collections");

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

            CollectionResponse response = http(endpoint).bodyOfBean(requestBody).postAs(CollectionResponse.class);

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
     * @param collectionName 集合Name
     * @return 集合信息
     * @throws IOException 如果请求失败
     */
    public CollectionResponse getCollectionStats(String collectionName) throws IOException {
        try {
            String endpoint = buildEndpoint("collections", collectionName);

            CollectionResponse response = http(endpoint).getAs(CollectionResponse.class);

            if (response.hasError()) {
                if ("NotFoundError".equals(response.getError()) || response.getError().contains("not exist")){
                    return null;
                }
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
            String endpoint = buildEndpoint("collections", collectionId);

            String responseStr = http(endpoint).delete();

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
                ChromaResponse response = ONode.deserialize(responseStr, ChromaResponse.class);
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
     * @param ids          文档ID列表
     * @param embeddings   文档向量列表
     * @param documents    文档内容列表
     * @param metadatas    文档元数据列表
     * @throws IOException 如果添加失败
     */
    public void addDocuments(String collectionId, List<String> ids, List<List<Float>> embeddings,
                             List<String> documents, List<Map<String, Object>> metadatas) throws IOException {
        try {
            String endpoint = buildEndpoint("collections", collectionId, "add");

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("ids", ids);
            requestBody.put("embeddings", embeddings);

            if (documents != null && !documents.isEmpty()) {
                requestBody.put("documents", documents);
            }

            if (metadatas != null && !metadatas.isEmpty()) {
                requestBody.put("metadatas", metadatas);
            }

            String jsonBody = ONode.serialize(requestBody);

            String responseStr = http(endpoint).bodyOfJson(jsonBody).post();

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
                Map<String, Object> response = ONode.deserialize(responseStr, Map.class);
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
     * @param ids          文档ID列表
     * @throws IOException 如果删除失败
     */
    public void deleteDocuments(String collectionId, List<String> ids) throws IOException {
        try {
            String endpoint = buildEndpoint("collections", collectionId, "delete");

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("ids", ids);

            // Chroma API在成功时不返回任何内容
            http(endpoint).bodyOfBean(requestBody)
                    .post();

        } catch (Exception e) {
            throw new IOException("Failed to delete documents: " + e.getMessage(), e);
        }
    }

    /**
     * 检查文档是否存在
     *
     * @param collectionId 集合ID
     * @param id           文档ID
     * @return 是否存在
     * @throws IOException 如果请求失败
     */
    public boolean documentExists(String collectionId, String id) throws IOException {
        try {
            String endpoint = buildEndpoint("collections", collectionId, "get");

            Map<String, Object> requestBody = new HashMap<>();
            List<String> ids = new ArrayList<>();
            ids.add(id);
            requestBody.put("ids", ids);

            GetResponse response = http(endpoint).bodyOfBean(requestBody).postAs(GetResponse.class);

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
     * @param collectionId   集合ID
     * @param queryEmbedding 查询向量
     * @param limit          结果数量限制
     * @param metadataFilter 元数据过滤条件
     * @return 查询响应
     * @throws IOException 如果查询失败
     */
    public QueryResponse queryDocuments(String collectionId, List<Float> queryEmbedding,
                                        int limit, Map<String, Object> metadataFilter) throws IOException {
        try {
            String endpoint = buildEndpoint("collections", collectionId, "query");

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

            QueryResponse response = http(endpoint).bodyOfBean(requestBody).postAs(QueryResponse.class);

            return response;
        } catch (Exception e) {
            throw new IOException("Failed to query documents: " + e.getMessage(), e);
        }
    }
}