package org.noear.solon.ai.rag.repository.weaviate;

import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.core.util.MultiMap;
import org.noear.solon.net.http.HttpUtils;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Weaviate API 客户端
 * 封装 Weaviate REST / GraphQL 接口调用
 *
 * @author 小奶奶花生米
 * @since 3.1
 */
public class WeaviateClient {


    private final String baseUrl;
    private final MultiMap<String> headers = new MultiMap<>();

    public WeaviateClient(String baseUrl) {
        if (Utils.isEmpty(baseUrl)) {
            throw new IllegalArgumentException("The baseurl cannot be empty.");
        }

        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
    }

    /**
     * 带基本认证的构造方法
     */
    public WeaviateClient(String baseUrl, String username, String password) {
        this(baseUrl);
        setBasicAuth(username, password);
    }

    /**
     * 带令牌认证的构造方法
     */
    public WeaviateClient(String baseUrl, String token) {
        this(baseUrl);
        setBearerAuth(token);
    }

    /**
     * 设置基础鉴权
     */
    public void setBasicAuth(String username, String password) {
        String plainCredentials = username + ":" + password;
        String base64Credentials = java.util.Base64.getEncoder().encodeToString(plainCredentials.getBytes());
        headers.put("Authorization", "Basic " + base64Credentials);
    }

    /**
     * 设置令牌鉴权
     */
    public void setBearerAuth(String token) {
        headers.put("Authorization", "Bearer " + token);
    }

    /**
     * 构建 HTTP 工具
     */
    private HttpUtils http(String endpoint) {
        return HttpUtils.http(endpoint)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .headers(headers);
    }

    /**
     * 构建 API 端点路径
     */
    private String buildEndpoint(String... pathParts) {
        return baseUrl + String.join("/", pathParts);
    }

    /**
     * 获取 schema
     */
    public SchemaResponse getSchema() throws IOException {
        try {
            String endpoint = buildEndpoint("v1", "schema");
            String response = http(endpoint).get();
            return ONode.deserialize(response, SchemaResponse.class);
        } catch (Exception e) {
            throw new IOException("Failed to get schema: " + e.getMessage(), e);
        }
    }

    /**
     * 创建 class
     */
    public void createClass(String className, List<Map<String, Object>> properties) throws IOException {
        try {
            String endpoint = buildEndpoint("v1", "schema");

            Map<String, Object> body = new HashMap<>();
            body.put("class", className);
            body.put("properties", properties);

            // 关闭 Weaviate 内置向量化，使用手动向量
            body.put("vectorizer", "none");
            
            // 配置向量索引
            Map<String, Object> vectorIndexConfig = new HashMap<>();
            vectorIndexConfig.put("skip", false);
            vectorIndexConfig.put("type", "hnsw");
            Map<String, Object> hnswConfig = new HashMap<>();
            hnswConfig.put("distance", "cosine");
            vectorIndexConfig.put("hnsw", hnswConfig);
            body.put("vectorIndexConfig", vectorIndexConfig);

            http(endpoint).bodyOfJson(ONode.serialize(body)).post();
        } catch (Exception e) {
            throw new IOException("Failed to create class: " + e.getMessage(), e);
        }
    }

    /**
     * 删除 class
     */
    public void deleteClass(String className) throws IOException {
        try {
            String endpoint = buildEndpoint("v1", "schema", className);
            http(endpoint).delete();
        } catch (Exception e) {
            throw new IOException("Failed to delete class: " + e.getMessage(), e);
        }
    }

    /**
     * 批量保存对象
     */
    public void batchSaveObjects(List<Map<String, Object>> objects) throws IOException {
        try {
            String endpoint = buildEndpoint("v1", "batch", "objects");

            Map<String, Object> body = new HashMap<>();
            body.put("objects", objects);

            String response = http(endpoint).bodyOfJson(ONode.serialize(body)).post();

            // 简单检查是否有 errors 字段
            if (response != null && response.contains("\"errors\"")) {
                throw new IOException("Weaviate batch insert has errors: " + response);
            }
        } catch (Exception e) {
            throw new IOException("Failed to batch save objects: " + e.getMessage(), e);
        }
    }

    /**
     * 删除对象
     */
    public void deleteObject(String className, String id) throws IOException {
        try {
            String endpoint = buildEndpoint("v1", "objects", className, id);
            http(endpoint).delete();
        } catch (Exception e) {
            throw new IOException("Failed to delete object: " + e.getMessage(), e);
        }
    }

    /**
     * 检查对象是否存在
     */
    public boolean objectExists(String className, String id) throws IOException {
        try {
            String endpoint = buildEndpoint("v1", "objects", className, id);
            String response = http(endpoint).get();
            return response != null && !response.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 执行 GraphQL 查询
     */
    public <T> T executeGraphQL(String query, Class<T> responseType) throws IOException {
        try {
            String endpoint = buildEndpoint("v1", "graphql");

            Map<String, Object> body = new HashMap<>();
            body.put("query", query);

            String requestBody = ONode.serialize(body);
            String response = http(endpoint).bodyOfJson(requestBody).post();
            return ONode.deserialize(response, responseType);
        } catch (Exception e) {
            throw new IOException("Failed to execute GraphQL query: " + e.getMessage(), e);
        }
    }
}

