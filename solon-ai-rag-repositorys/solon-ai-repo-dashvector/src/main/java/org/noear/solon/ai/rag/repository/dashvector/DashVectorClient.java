package org.noear.solon.ai.rag.repository.dashvector;

import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;

import org.noear.snack.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.core.util.MultiMap;
import org.noear.solon.net.http.HttpUtils;


/**
 * DashVectorClient API 客户端
 *
 * @author 小奶奶花生米
 */
public class DashVectorClient{

    private static final Logger logger = Logger.getLogger(DashVectorClient.class.getName());

    //DashVector 服务器地址与账号
    private final String baseUrl;
    private final String apiKey;
    private final MultiMap<String> headers = new MultiMap<>();
    private final StringSerializer serializer = new StringSerializer();


    public DashVectorClient(String baseUrl, String apiKey) {
        if (Utils.isEmpty(baseUrl)) {
            throw new IllegalArgumentException("The baseurl cannot be empty.");
        }
        if (Utils.isEmpty(apiKey)) {
            throw new IllegalArgumentException("The apiKey cannot be empty.");
        }

        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.apiKey = apiKey;
    }

    public DashVectorClient(Properties properties) {
        this(properties.getProperty("url"), properties.getProperty("apiKey"));
    }



    /**
     * 构建 http 工具
     */
    private HttpUtils http(String endpoint) {
        HttpUtils httpUtils = HttpUtils.http(endpoint)
                .serializer(serializer)
                .headers(headers)
                .header("Accept", "application/json")
                .header("dashvector-auth-token", apiKey);

        return httpUtils;
    }


    /**
     * 获取所有集合
     *
     * @return 集合列表
     * @throws IOException 如果请求失败
     */
    public ListCollectionsResponse listCollections() throws IOException {
        try {
            String endpoint = baseUrl + "v1/collections";

            ListCollectionsResponse response = http(endpoint).getAs(ListCollectionsResponse.class);

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
     * @return 集合ID
     * @throws IOException 如果创建失败
     */
    public CreateCollectionResponse createCollection(String name, int dimension, Map<String, String> fieldsSchema) throws IOException {
        try {
            String endpoint = baseUrl + "v1/collections";

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("name", name);
            requestBody.put("dimension", dimension);

            if(fieldsSchema != null){
                requestBody.put("fields_schema", fieldsSchema);
            }

            CreateCollectionResponse response = http(endpoint).bodyOfBean(requestBody).postAs(CreateCollectionResponse.class);

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
     * 删除集合
     *
     * @param collectionName 集合名称
     * @throws IOException 如果删除失败
     */
    public void deleteCollection(String collectionName) throws IOException {
        try {
            String endpoint = baseUrl + "v1/collections/" + collectionName;

            DeleteCollectionResponse response = http(endpoint).deleteAs(DeleteCollectionResponse.class);
            if (response.hasError()) {
                throw new IOException("Failed to delete collection: " + response.getMessage());
            }
        } catch (Exception e) {
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            throw new IOException("Failed to delete collection: " + e.getMessage(), e);
        }
    }

    /**
     * 检查文档是否存在
     *
     * @param collectionName 集合名称
     * @param id           文档ID
     * @return 是否存在
     */
    public boolean documentExists(String collectionName, String id) throws IOException{
        try {
            String endpoint = baseUrl + "v1/collections/" + collectionName + "/docs?ids="+id;
            QueryByIdResponse response = http(endpoint).getAs(QueryByIdResponse.class);

            if (response.hasError()) {
                throw new IOException("Error checking document existence: " + response.getMessage());
            }
            Map<String, Object> output = response.getOutput();
            if (output == null || output.isEmpty()){
                return false;
            }
            return output.containsKey(id);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 删除文档
     *
     * @param collectionName 集合名称
     * @param ids          文档ID列表
     * @throws IOException 如果删除失败
     */
    public void deleteDocuments(String collectionName, List<String> ids) throws IOException {
        try {
            String endpoint = baseUrl + "v1/collections/" + collectionName + "/docs";

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("ids", ids);

            DeleteDocResponse response = http(endpoint).bodyOfBean(requestBody).deleteAs(DeleteDocResponse.class);
            if (response.hasError()) {
                throw new IOException("Failed to delete documents: " + response.getMessage());
            }


        } catch (Exception e) {
            throw new IOException("Failed to delete documents: " + e.getMessage(), e);
        }
    }



    /**
     * 添加文档
     *
     * @param collectionName 集合名称
     * @param documents    文档内容列表
     * @throws IOException 如果添加失败
     */
    public void addDocuments(String collectionName, List<Doc> documents) throws IOException {
        try {
            String endpoint = baseUrl + "v1/collections/" + collectionName + "/docs";

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("docs",documents);

            String jsonBody = ONode.stringify(requestBody);
            AddDocResponse response = http(endpoint).bodyOfJson(jsonBody).postAs(AddDocResponse.class);

            if (response.hasError()) {
                throw new IOException("Failed to add documents: " + response.getMessage());
            }
        } catch (Exception e) {
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            throw new IOException("Failed to add documents: " + e.getMessage(), e);
        }
    }

    /**
     * 查询文档
     *
     * @param collectionName   集合名称
     * @param queryEmbedding 查询向量
     * @param limit          结果数量限制
     * @param metadataFilter 元数据过滤条件
     * @return 查询响应
     * @throws IOException 如果查询失败
     */
    public QueryResponse queryDocuments(String collectionName, List<Float> queryEmbedding,
                                                             int limit, String metadataFilter) throws IOException {
        try {
            String endpoint = baseUrl + "v1/collections/" + collectionName + "/query";

            Map<String, Object> requestBody = new HashMap<>();

            // 创建嵌套的查询向量列表
            requestBody.put("vector", queryEmbedding);
            requestBody.put("topk", limit > 0 ? limit : 10);

            // 添加元数据过滤
            if (metadataFilter != null && !metadataFilter.isEmpty()) {
                requestBody.put("filter", metadataFilter);
            }

            QueryResponse response = http(endpoint).bodyOfBean(requestBody).postAs(QueryResponse.class);

            if (response.hasError()) {
                throw new IOException("Failed to query documents: " + response.getMessage());
            }
            return response;
        } catch (Exception e) {
            throw new IOException("Failed to query documents: " + e.getMessage(), e);
        }
    }
}
