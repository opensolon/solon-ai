package org.noear.solon.ai.rag.repository;

import com.tencent.tcvectordb.client.VectorDBClient;
import com.tencent.tcvectordb.model.Collection;
import com.tencent.tcvectordb.model.Database;
import com.tencent.tcvectordb.model.DocField;
import com.tencent.tcvectordb.model.param.collection.*;
import com.tencent.tcvectordb.model.param.database.ConnectParam;
import com.tencent.tcvectordb.model.param.dml.*;
import com.tencent.tcvectordb.model.param.entity.AffectRes;
import com.tencent.tcvectordb.model.param.entity.SearchRes;
import com.tencent.tcvectordb.model.param.enums.EmbeddingModelEnum;
import com.tencent.tcvectordb.model.param.enums.ReadConsistencyEnum;
import org.noear.solon.Utils;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.RepositoryStorable;
import org.noear.solon.ai.rag.util.QueryCondition;
import org.noear.solon.lang.Preview;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 腾讯云 VectorDB 矢量存储知识库
 *
 * @author 小奶奶花生米
 * @since 3.1
 */
@Preview("3.1")
public class VectorDBRepository implements RepositoryStorable {

    /**
     * 文本字段名
     */
    public static final String TEXT_FIELD_NAME = "__text";

    /**
     * 向量字段名
     */
    public static final String VECTOR_FIELD_NAME = "vector";

    /**
     * 默认超时时间（秒）
     */
    public static final int DEFAULT_TIMEOUT = 30;

    /**
     * 默认连接超时时间（秒）
     */
    public static final int DEFAULT_CONNECT_TIMEOUT = 5;

    /**
     * 默认最大空闲连接数
     */
    public static final int DEFAULT_MAX_IDLE_CONNECTIONS = 10;

    /**
     * 默认连接保持时间（秒）
     */
    public static final int DEFAULT_KEEP_ALIVE_DURATION = 5 * 60;

    /**
     * 默认分片数
     * 指定 Collection 的分片数。分片是把大数据集切成多个子数据集。
     * 取值范围：[1,100]。例如：5。
     * 配置建议：在搜索时，全部分片是并发执行的，分片数量越多，平均耗时越低，但是过多的分片会带来额外开销而影响性能。
     * 单分片数据量建议控制在300万以内，例如500万向量，可设置2个分片。
     * 如果数据量小于300万，建议使用1分片。系统对1分片有特定优化，可显著提升性能。
     */
    public static final int DEFAULT_SHARD_NUM = 1;

    /**
     * 默认副本数
     * 指定 Collection 的副本数。副本数是指每个主分片有多个相同的备份，用来容灾和负载均衡。
     * 取值范围如下所示。搜索请求量越高的索引，建议设置越多的副本数，避免负载不均衡。
     * 单可用区实例：0。
     * 两可用区实例：[1,节点数-1]。
     * 三可用区实例：[2,节点数-1]。
     */
    public static final int DEFAULT_REPLICA_NUM = 0;

    /**
     * 默认相似度度量类型
     * L2：全称是 Euclidean distance，指欧几里得距离，它计算向量之间的直线距离，所得的值越小，越与搜索值相似。L2在低维空间中表现良好，但是在高维空间中，由于维度灾难的影响，L2的效果会逐渐变差。
     * IP：全称为 Inner Product，是一种计算向量之间相似度的度量算法，它计算两个向量之间的点积（内积），所得值越大越与搜索值相似。
     * COSINE：余弦相似度（Cosine Similarity）算法，是一种常用的文本相似度计算方法。它通过计算两个向量在多维空间中的夹角余弦值来衡量它们的相似程度。所得值越大越与搜索值相似。
     * HAMMING：汉明距离（Hamming Distance），计算两个二进制字符串对应位置上不同字符的数量，如果字符不同，两字符串的汉明距离就会加一。汉明距离越小，表示两个字符串之间的相似度越高。
     */
    public static final MetricType DEFAULT_METRIC_TYPE = MetricType.COSINE;

    /**
     * 默认索引类型
     * 指定索引类型，取值如下所示。更多信息，请参见 https://cloud.tencent.com/document/product/1709/95428
     * FLAT：暴力检索，召回率100%，但检索效率低，适用10万以内数据规模。
     * HNSW：召回率95%+，可通过参数调整召回率，检索效率高，但数据量大后写入效率会变低，适用于10万-1亿区间数据规模。
     * BIN_FLAT：二进制索引，暴力检索，召回率100%。
     * IVF_FLAT、IVF_PQ、IVF_SQ4, IVF_SQ8, IVF_SQ16：IVF 系列索引，适用于上亿规模的数据集，检索效率高，内存占用低，写入效率高。
     */
    public static final IndexType DEFAULT_INDEX_TYPE = IndexType.HNSW;

    /**
     * 默认 HNSW 图的每层节点的邻居数量
     */
    public static final int DEFAULT_HNSW_M = 16;

    /**
     * 默认 HNSW 图构建时的候选邻居数量
     */
    public static final int DEFAULT_HNSW_EF_CONSTRUCTION = 200;

    /**
     * 向量模型
     */
    private final EmbeddingModelEnum embeddingModel;

    /**
     * VectorDB 客户端
     */
    private final VectorDBClient client;

    /**
     * 数据库名称
     */
    private final String databaseName;

    /**
     * 集合名称
     */
    private final String collectionName;

    /**
     * 分片数
     */
    private final int shardNum;

    /**
     * 副本数
     */
    private final int replicaNum;

    /**
     * 相似度度量类型
     */
    private final MetricType metricType;

    /**
     * 索引类型
     */
    private final IndexType indexType;

    /**
     * 向量索引参数序列化器
     */
    private final ParamsSerializer indexParams;

    /**
     * 集合对象
     */
    private Collection collection;

    /**
     * 是否已初始化
     */
    private boolean initialized = false;

    /**
     * 简单构造函数
     *
     * @param embeddingModelName 向量模型名称
     * @param url VectorDB 服务地址
     * @param username 用户名
     * @param key 密钥
     * @param databaseName 数据库名称
     * @param collectionName 集合名称
     */
    public VectorDBRepository(String embeddingModelName, String url, String username, String key,
                              String databaseName, String collectionName) {
        this(new Builder(embeddingModelName, url, username, key, databaseName, collectionName));
    }

    /**
     * 私有构造函数，通过 Builder 创建实例
     *
     * @param builder 构建器
     */
    private VectorDBRepository(Builder builder) {
        // 验证必要参数
        if (builder.embeddingModel == null) {
            throw new IllegalArgumentException("EmbeddingModel must not be null");
        }
        if (Utils.isEmpty(builder.url)) {
            throw new IllegalArgumentException("URL must not be null or empty");
        }
        if (Utils.isEmpty(builder.username)) {
            throw new IllegalArgumentException("Username must not be null or empty");
        }
        if (Utils.isEmpty(builder.key)) {
            throw new IllegalArgumentException("Key must not be null or empty");
        }
        if (Utils.isEmpty(builder.databaseName)) {
            throw new IllegalArgumentException("DatabaseName must not be null or empty");
        }
        if (Utils.isEmpty(builder.collectionName)) {
            throw new IllegalArgumentException("CollectionName must not be null or empty");
        }

        // 设置属性
        this.embeddingModel = builder.embeddingModel;
        this.databaseName = builder.databaseName;
        this.collectionName = builder.collectionName;
        this.shardNum = builder.shardNum;
        this.replicaNum = builder.replicaNum;
        this.metricType = builder.metricType;
        this.indexType = builder.indexType;
        this.indexParams = builder.indexParams;

        // 创建连接参数
        ConnectParam connectParam = ConnectParam.newBuilder()
                .withUrl(builder.url)
                .withUsername(builder.username)
                .withKey(builder.key)
                .withTimeout(builder.timeout)
                .withConnectTimeout(builder.connectTimeout)
                .withMaxIdleConnections(builder.maxIdleConnections)
                .withKeepAliveDuration(builder.keepAliveDuration)
                .build();

        // 创建 VectorDB 客户端
        this.client = new VectorDBClient(connectParam, ReadConsistencyEnum.EVENTUAL_CONSISTENCY);

        // 初始化仓库
        initRepository();
    }

    /**
     * 创建构建器
     *
     * @param embeddingModelName 向量模型名称
     * @param url VectorDB 服务地址
     * @param username 用户名
     * @param key 密钥
     * @param databaseName 数据库名称
     * @param collectionName 集合名称
     * @return 构建器
     */
    public static Builder builder(String embeddingModelName, String url, String username, String key,
                                 String databaseName, String collectionName) {
        return new Builder(embeddingModelName, url, username, key, databaseName, collectionName);
    }

    /**
     * VectorDBRepository 构建器
     */
    public static class Builder {
        // 必要参数
        private final EmbeddingModelEnum embeddingModel;
        private final String url;
        private final String username;
        private final String key;
        private final String databaseName;
        private final String collectionName;

        // 可选参数（使用默认值）
        private int timeout = DEFAULT_TIMEOUT;
        private int connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        private int maxIdleConnections = DEFAULT_MAX_IDLE_CONNECTIONS;
        private int keepAliveDuration = DEFAULT_KEEP_ALIVE_DURATION;
        private int shardNum = DEFAULT_SHARD_NUM;
        private int replicaNum = DEFAULT_REPLICA_NUM;
        private MetricType metricType = DEFAULT_METRIC_TYPE;
        private IndexType indexType = DEFAULT_INDEX_TYPE;
        private ParamsSerializer indexParams = null;

        /**
         * 构造函数
         *
         * @param embeddingModelName 向量模型名称
         * @param url VectorDB 服务地址
         * @param username 用户名
         * @param key 密钥
         * @param databaseName 数据库名称
         * @param collectionName 集合名称
         */
        public Builder(String embeddingModelName, String url, String username, String key,
                      String databaseName, String collectionName) {
            if (Utils.isEmpty(embeddingModelName)) {
                throw new IllegalArgumentException("EmbeddingModelName must not be null or empty");
            }

            EmbeddingModelEnum embeddingModelEnum = EmbeddingModelEnum.find(embeddingModelName);
            if (embeddingModelEnum == null) {
                throw new IllegalArgumentException("Invalid embedding model name: " + embeddingModelName);
            }

            this.embeddingModel = embeddingModelEnum;
            this.url = url;
            this.username = username;
            this.key = key;
            this.databaseName = databaseName;
            this.collectionName = collectionName;
        }

        /**
         * 设置超时时间
         *
         * @param timeout 超时时间（秒）
         * @return 构建器
         */
        public Builder timeout(int timeout) {
            this.timeout = timeout;
            return this;
        }

        /**
         * 设置连接超时时间
         *
         * @param connectTimeout 连接超时时间（秒）
         * @return 构建器
         */
        public Builder connectTimeout(int connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        /**
         * 设置最大空闲连接数
         *
         * @param maxIdleConnections 最大空闲连接数
         * @return 构建器
         */
        public Builder maxIdleConnections(int maxIdleConnections) {
            this.maxIdleConnections = maxIdleConnections;
            return this;
        }

        /**
         * 设置连接保持时间
         *
         * @param keepAliveDuration 连接保持时间（秒）
         * @return 构建器
         */
        public Builder keepAliveDuration(int keepAliveDuration) {
            this.keepAliveDuration = keepAliveDuration;
            return this;
        }

        /**
         * 设置分片数
         *
         * @param shardNum 分片数
         * @return 构建器
         */
        public Builder shardNum(int shardNum) {
            this.shardNum = shardNum;
            return this;
        }

        /**
         * 设置副本数
         *
         * @param replicaNum 副本数
         * @return 构建器
         */
        public Builder replicaNum(int replicaNum) {
            this.replicaNum = replicaNum;
            return this;
        }

        /**
         * 设置相似度度量类型
         *
         * @param metricType 相似度度量类型
         * @return 构建器
         */
        public Builder metricType(MetricType metricType) {
            this.metricType = metricType;
            return this;
        }

        /**
         * 设置索引类型
         *
         * @param indexType 索引类型
         * @return 构建器
         */
        public Builder indexType(IndexType indexType) {
            this.indexType = indexType;
            return this;
        }

        /**
         * 设置向量索引参数
         *
         * @param indexParams 向量索引参数
         * @return 构建器
         */
        public Builder indexParams(ParamsSerializer indexParams) {
            this.indexParams = indexParams;
            return this;
        }

        /**
         * 构建 VectorDBRepository
         *
         * @return VectorDBRepository 实例
         */
        public VectorDBRepository build() {
            return new VectorDBRepository(this);
        }
    }

    /**
     * 初始化仓库
     */
    private void initRepository() {
        if (initialized) {
            return;
        }

        try {
            // 检查数据库是否存在
            List<String> databases = client.listDatabase();
            boolean databaseExists = databases.contains(databaseName);

            Database database;
            if (!databaseExists) {
                // 创建数据库
                database = client.createDatabase(databaseName);
            } else {
                // 获取现有数据库
                database = client.database(databaseName);
            }

            // 检查集合是否存在
            List<Collection> collections = database.listCollections();
            boolean collectionExists = collections.stream()
                    .anyMatch(c -> collectionName.equals(c.getCollection()));

            if (!collectionExists) {
                // 创建集合
                CreateCollectionParam.Builder collectionParamBuilder = CreateCollectionParam.newBuilder()
                        .withName(collectionName)
                        .withShardNum(shardNum)
                        .withReplicaNum(replicaNum)
                        .withDescription("Collection created by Solon AI")
                        .addField(new FilterIndex("id", FieldType.String, IndexType.PRIMARY_KEY));

                // 创建向量索引
                VectorIndex vectorIndex = getVectorIndex();

                collectionParamBuilder.addField(vectorIndex);

                // 添加嵌入配置
                collectionParamBuilder.withEmbedding(Embedding.newBuilder()
                        .withVectorField(VECTOR_FIELD_NAME)
                        .withField(TEXT_FIELD_NAME)
                        .withModelName(embeddingModel.getModelName())
                        .build());

                // 创建集合
                database.createCollection(collectionParamBuilder.build());
            }

            // 获取集合
            this.collection = database.describeCollection(collectionName);
            this.initialized = true;
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize VectorDB repository: " + e.getMessage(), e);
        }
    }

    /**
     * 创建索引
     *
     * @return com.tencent.tcvectordb.model.param.collection.VectorIndex
     */
    private VectorIndex getVectorIndex() {
        VectorIndex vectorIndex;
        if (indexParams != null) {
            // 使用自定义参数
            vectorIndex = new VectorIndex(VECTOR_FIELD_NAME, embeddingModel.getDimension(),
                                         indexType, metricType, indexParams);
        } else {
            // 对于其他索引类型
            vectorIndex = new VectorIndex(VECTOR_FIELD_NAME, embeddingModel.getDimension(),
                    indexType, metricType,
                    new HNSWParams(DEFAULT_HNSW_M, DEFAULT_HNSW_EF_CONSTRUCTION));
        }
        return vectorIndex;
    }

    /**
     * 批量存储文档
     *
     * @param documents 要存储的文档列表
     * @throws IOException 如果存储过程发生IO错误
     */
    @Override
    public void insert(List<Document> documents) throws IOException {
        if (documents == null || documents.isEmpty()) {
            return;
        }

        try {
            // 确保所有文档都有ID
            for (Document doc : documents) {
                if (Utils.isEmpty(doc.getId())) {
                    doc.id(java.util.UUID.randomUUID().toString());
                }
            }

            // 准备上传到VectorDB的文档
            List<com.tencent.tcvectordb.model.Document> vectorDbDocs = new ArrayList<>();
            for (Document document : documents) {
                com.tencent.tcvectordb.model.Document.Builder builder = com.tencent.tcvectordb.model.Document.newBuilder()
                        .withId(document.getId())
                        .withDoc(document.getContent())
                        // 确保文档内容被设置到TEXT_FIELD_NAME字段
                        .addDocField(new DocField(TEXT_FIELD_NAME, document.getContent()));

                if (document.getMetadata() != null && !document.getMetadata().isEmpty()){
                    for (Map.Entry<String, Object> entry : document.getMetadata().entrySet()) {
                        builder.addDocField(new DocField(entry.getKey(),entry.getValue()));
                    }
                }

                vectorDbDocs.add(builder.build());
            }

            // 插入文档
            InsertParam insertParam = InsertParam.newBuilder()
                    .addAllDocument(vectorDbDocs)
                    .withBuildIndex(true)
                    .build();
            AffectRes upsert = collection.upsert(insertParam);
            if (upsert.getCode() != 0){
                throw new IOException("Failed to insert documents: " + upsert.getMsg());
            }

        } catch (Exception e) {
            throw new IOException("Failed to insert documents: " + e.getMessage(), e);
        }
    }

    /**
     * 删除文档
     *
     * @param ids 要删除的文档ID
     * @throws IOException 如果删除过程发生IO错误
     */
    @Override
    public void delete(String... ids) throws IOException {
        if (ids == null || ids.length == 0) {
            return;
        }

        try {
            // 准备删除参数
            DeleteParam deleteParam = DeleteParam.newBuilder()
                    .addAllDocumentId(Arrays.asList(ids))
                    .build();

            // 执行删除
            collection.delete(deleteParam);
        } catch (Exception e) {
            throw new IOException("Failed to delete documents: " + e.getMessage(), e);
        }
    }

    /**
     * 检查文档是否存在
     *
     * @param id 文档ID
     * @return 是否存在
     * @throws IOException 如果检查过程发生IO错误
     */
    @Override
    public boolean exists(String id) throws IOException {
        try {
            // 查询指定ID的文档
            QueryParam queryParam = QueryParam.newBuilder()
                    .withDocumentIds(Collections.singletonList(id))
                    .withLimit(1)
                    .build();

            List<com.tencent.tcvectordb.model.Document> documents = collection.query(queryParam);
            return documents != null && !documents.isEmpty();
        } catch (Exception e) {
            throw new IOException("Failed to check document existence: " + e.getMessage(), e);
        }
    }

    /**
     * 搜索文档
     *
     * @param condition 查询条件
     * @return 搜索结果
     * @throws IOException 如果搜索过程发生IO错误
     */
    @Override
    public List<Document> search(QueryCondition condition) throws IOException {
        if (condition == null) {
            throw new IllegalArgumentException("QueryCondition must not be null");
        }

        try {
            // 准备搜索参数
            SearchByEmbeddingItemsParam searchParam = SearchByEmbeddingItemsParam.newBuilder()
                    .withEmbeddingItems(Collections.singletonList(condition.getQuery()))
                    .withParams(new HNSWSearchParams(100))
                    .withLimit(condition.getLimit() > 0 ? condition.getLimit() : 10)
                    .build();

            // 执行搜索
            SearchRes searchRes = collection.searchByEmbeddingItems(searchParam);

            // 解析搜索结果
            List<Document> results = getDocuments(searchRes);

            // 应用过滤器（如果有）
            if (condition.getFilter() != null) {
                return results.stream()
                        .filter(condition.getFilter())
                        .collect(Collectors.toList());
            }

            return results;
        } catch (Exception e) {
            throw new IOException("Failed to search documents: " + e.getMessage(), e);
        }
    }

    /**
     * 结果转换
     *
     * @author 小奶奶花生米
     * @param searchRes
     * @return java.util.List<org.noear.solon.ai.rag.Document>
     */
    private static List<Document> getDocuments(SearchRes searchRes) {
        List<Document> results = new ArrayList<>();
        if (searchRes.getDocuments() != null && !searchRes.getDocuments().isEmpty()) {
            for (List<com.tencent.tcvectordb.model.Document> documents : searchRes.getDocuments()) {
                for (com.tencent.tcvectordb.model.Document doc : documents) {
                    // 提取文档内容
                    String content = doc.getDoc();
                    // 创建文档
                    Document document = new Document(
                            doc.getId(),
                            content,
                            doc.getDocKeyValue(),
                            doc.getScore()
                    );
                    results.add(document);
                }
            }
        }
        return results;
    }

}
