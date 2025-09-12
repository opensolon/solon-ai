package org.noear.solon.ai.rag.repository;

import org.noear.snack.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.embedding.EmbeddingModel;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.RepositoryLifecycle;
import org.noear.solon.ai.rag.RepositoryStorable;
import org.noear.solon.ai.rag.repository.mysql.FilterTransformer;
import org.noear.solon.ai.rag.repository.mysql.MetadataField;
import org.noear.solon.ai.rag.util.ListUtil;
import org.noear.solon.ai.rag.util.QueryCondition;
import org.noear.solon.ai.rag.util.SimilarityUtil;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * MySQL 矢量存储知识库
 *
 * @author 小奶奶花生米
 */
public class MySqlRepository implements RepositoryStorable, RepositoryLifecycle {
    private final Builder config;
    private final DataSource dataSource;

    /**
     * 私有构造函数，通过 Builder 模式创建
     */
    private MySqlRepository(Builder config) {
        this.config = config;
        this.dataSource = config.dataSource;

        try {
            initRepository();
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize mysql repository", e);
        }
    }

    /**
     * 初始化仓库
     */
    @Override
    public void initRepository() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            // 检查表是否存在
            if (!tableExists(conn, config.tableName)) {
                createTable(conn);
            }
            // 创建余弦相似度计算函数
            createCosineSimilarityFunction(conn);
        } catch (SQLException e) {
            throw new Exception("Failed to initialize mysql repository", e);
        }
    }

    /**
     * 检查表是否存在
     */
    private boolean tableExists(Connection conn, String tableName) throws SQLException {
        String sql = "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    /**
     * 创建表
     */
    private void createTable(Connection conn) throws SQLException {
        StringBuilder sql = new StringBuilder();
        sql.append("CREATE TABLE `").append(config.tableName).append("` (");
        sql.append("`id` VARCHAR(255) PRIMARY KEY,");
        sql.append("`content` TEXT NOT NULL,");
        sql.append("`embedding` JSON,"); // 使用 JSON 存储向量数据
        sql.append("`metadata` JSON,"); // MySQL 5.7+ 支持 JSON 类型
        sql.append("`created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP");

        // 添加元数据字段
        if (Utils.isNotEmpty(config.metadataFields)) {
            for (MetadataField field : config.metadataFields) {
                switch (field.getFieldType()) {
                    case TEXT:
                        sql.append(", `").append(field.getName()).append("` TEXT");
                        break;
                    case NUMERIC:
                        sql.append(", `").append(field.getName()).append("` DECIMAL(65,30)");
                        break;
                    case JSON:
                        sql.append(", `").append(field.getName()).append("` JSON");
                        break;
                }
            }
        }

        sql.append(")");

        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql.toString());
        }

        // 创建普通索引
        String indexSql = String.format(
                "CREATE INDEX `idx_%s_created_at` ON `%s` (`created_at`)",
                config.tableName, config.tableName
        );

        try (Statement stmt = conn.createStatement()) {
            stmt.execute(indexSql);
        }
    }

    /**
     * 注销仓库
     */
    @Override
    public void dropRepository() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS `" + config.tableName + "`");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to drop mysql repository", e);
        }
    }

    /**
     * 存储文档列表（支持更新）
     */
    @Override
    public void save(List<Document> documents, BiConsumer<Integer, Integer> progressCallback) throws IOException {
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

            try (Connection conn = dataSource.getConnection()) {
                batchInsertDo(conn, batch);
            } catch (SQLException e) {
                throw new IOException("Failed to insert documents", e);
            }

            //回调进度
            if (progressCallback != null) {
                progressCallback.accept(++batchIndex, batchList.size());
            }
        }
    }

    /**
     * 批量插入文档
     */
    private void batchInsertDo(Connection conn, List<Document> documents) throws SQLException {
        StringBuilder sql = new StringBuilder();
        sql.append("INSERT INTO `").append(config.tableName).append("` (`id`, `content`, `embedding`, `metadata`");

        // 添加元数据字段
        if (Utils.isNotEmpty(config.metadataFields)) {
            for (MetadataField field : config.metadataFields) {
                sql.append(", `").append(field.getName()).append("`");
            }
        }

        sql.append(") VALUES (?, ?, ?, ?");

        // 添加元数据字段占位符
        if (Utils.isNotEmpty(config.metadataFields)) {
            for (int i = 0; i < config.metadataFields.size(); i++) {
                sql.append(", ?");
            }
        }

        sql.append(") ON DUPLICATE KEY UPDATE ");
        sql.append("`content` = VALUES(`content`),");
        sql.append("`embedding` = VALUES(`embedding`),");
        sql.append("`metadata` = VALUES(`metadata`)");

        // 添加元数据字段更新
        if (Utils.isNotEmpty(config.metadataFields)) {
            for (MetadataField field : config.metadataFields) {
                sql.append(", `").append(field.getName()).append("` = VALUES(`").append(field.getName()).append("`)");
            }
        }

        try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            for (Document doc : documents) {
                int paramIndex = 1;
                stmt.setString(paramIndex++, doc.getId());
                stmt.setString(paramIndex++, doc.getContent());
                
                // 将 float[] 转换为 JSON 数组存储
                stmt.setObject(paramIndex++, floatArrayToJson(doc.getEmbedding()), Types.VARCHAR);
                stmt.setObject(paramIndex++, ONode.stringify(doc.getMetadata()), Types.VARCHAR);

                // 设置元数据字段
                if (Utils.isNotEmpty(config.metadataFields)) {
                    for (MetadataField field : config.metadataFields) {
                        Object value = doc.getMetadata().get(field.getName());
                        if (value != null) {
                            switch (field.getFieldType()) {
                                case TEXT:
                                    stmt.setString(paramIndex++, value.toString());
                                    break;
                                case NUMERIC:
                                    if (value instanceof Number) {
                                        stmt.setBigDecimal(paramIndex++, new java.math.BigDecimal(value.toString()));
                                    } else {
                                        stmt.setNull(paramIndex++, Types.DECIMAL);
                                    }
                                    break;
                                case JSON:
                                    stmt.setObject(paramIndex++, ONode.stringify(value), Types.VARCHAR);
                                    break;
                            }
                        } else {
                            // 根据字段类型设置正确的 NULL 类型
                            switch (field.getFieldType()) {
                                case TEXT:
                                    stmt.setNull(paramIndex++, Types.VARCHAR);
                                    break;
                                case NUMERIC:
                                    stmt.setNull(paramIndex++, Types.DECIMAL);
                                    break;
                                case JSON:
                                    stmt.setNull(paramIndex++, Types.VARCHAR);
                                    break;
                            }
                        }
                    }
                }

                stmt.addBatch();
            }
            stmt.executeBatch();
        } catch (Exception e) {
            throw new SQLException("Failed to insert documents", e);
        }
    }

    /**
     * 删除指定 ID 的文档
     */
    @Override
    public void deleteById(String... ids) throws IOException {
        if (Utils.isEmpty(ids)) {
            return;
        }

        StringBuilder sql = new StringBuilder();
        sql.append("DELETE FROM `").append(config.tableName).append("` WHERE `id` IN (");
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) sql.append(",");
            sql.append("?");
        }
        sql.append(")");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < ids.length; i++) {
                stmt.setString(i + 1, ids[i]);
            }
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IOException("Failed to delete documents", e);
        }
    }

    /**
     * 检查文档是否存在
     */
    @Override
    public boolean existsById(String id) throws IOException {
        String sql = "SELECT COUNT(*) FROM `" + config.tableName + "` WHERE `id` = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            throw new IOException("Failed to check document existence", e);
        }
    }

    /**
     * 搜索文档
     */
    @Override
    public List<Document> search(QueryCondition condition) throws IOException {
        float[] queryEmbedding = config.embeddingModel.embed(condition.getQuery());
        try {
            return searchWithDatabaseFunction(condition, queryEmbedding);
        } catch (Exception e) {
            throw new IOException(e);
        }
    }
    
    /**
     * 搜索文档：使用数据库层面的余弦相似度计算
     */
    private List<Document> searchWithDatabaseFunction(QueryCondition condition, float[] queryEmbedding) throws IOException, SQLException {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT `id`, `content`, `metadata`, ");
        sql.append("COSINE_SIMILARITY(`embedding`, ?) AS similarity ");
        sql.append("FROM `").append(config.tableName).append("`");

        // 添加过滤条件
        String filterClause = FilterTransformer.getInstance().transform(condition.getFilterExpression());
        if (Utils.isNotEmpty(filterClause)) {
            sql.append(" WHERE ").append(filterClause);
        }

        sql.append(" ORDER BY similarity DESC");
        sql.append(" LIMIT ?");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {

            // 设置查询向量参数
            stmt.setString(1, floatArrayToJson(queryEmbedding));
            stmt.setInt(2, condition.getLimit());

            List<Document> results = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString("id");
                    String content = rs.getString("content");
                    String metadataJson = rs.getString("metadata");
                    double similarity = rs.getDouble("similarity");

                    Map<String, Object> metadata = new HashMap<>();
                    if (Utils.isNotEmpty(metadataJson)) {
                        metadata = ONode.deserialize(metadataJson, Map.class);
                    }

                    Document doc = new Document(id, content, metadata, similarity);
                    results.add(doc);
                }
            }

            return SimilarityUtil.refilter(results.stream(), condition);
        }
    }


    /**
     * 将 float 数组转换为 JSON 字符串
     */
    private String floatArrayToJson(float[] array) {
        if (array == null) return "[]";
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < array.length; i++) {
            if (i > 0) json.append(",");
            json.append(array[i]);
        }
        json.append("]");
        return json.toString();
    }


    /**
     * 创建余弦相似度计算函数
     */
    private void createCosineSimilarityFunction(Connection conn) throws SQLException {
        // 删除已存在的函数
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DROP FUNCTION IF EXISTS COSINE_SIMILARITY");
        } catch (SQLException e) {
            // 忽略删除函数时的错误
        }

        // 创建余弦相似度计算函数
        String functionSQL = "CREATE FUNCTION COSINE_SIMILARITY(doc_embedding JSON, query_embedding JSON) " +
                "RETURNS DOUBLE " +
                "READS SQL DATA " +
                "DETERMINISTIC " +
                "BEGIN " +
                "  DECLARE dot_product DOUBLE DEFAULT 0.0; " +
                "  DECLARE norm_doc DOUBLE DEFAULT 0.0; " +
                "  DECLARE norm_query DOUBLE DEFAULT 0.0; " +
                "  DECLARE i INT DEFAULT 0; " +
                "  DECLARE doc_len INT; " +
                "  DECLARE query_len INT; " +
                "  DECLARE doc_val DOUBLE; " +
                "  DECLARE query_val DOUBLE; " +
                "  " +
                "  SET doc_len = JSON_LENGTH(doc_embedding); " +
                "  SET query_len = JSON_LENGTH(query_embedding); " +
                "  " +
                "  IF doc_len != query_len OR doc_len = 0 THEN " +
                "    RETURN 0.0; " +
                "  END IF; " +
                "  " +
                "  WHILE i < doc_len DO " +
                "    SET doc_val = CAST(JSON_EXTRACT(doc_embedding, CONCAT('$[', i, ']')) AS DECIMAL(20,10)); " +
                "    SET query_val = CAST(JSON_EXTRACT(query_embedding, CONCAT('$[', i, ']')) AS DECIMAL(20,10)); " +
                "    " +
                "    SET dot_product = dot_product + (doc_val * query_val); " +
                "    SET norm_doc = norm_doc + (doc_val * doc_val); " +
                "    SET norm_query = norm_query + (query_val * query_val); " +
                "    SET i = i + 1; " +
                "  END WHILE; " +
                "  " +
                "  IF norm_doc = 0.0 OR norm_query = 0.0 THEN " +
                "    RETURN 0.0; " +
                "  END IF; " +
                "  " +
                "  RETURN dot_product / (SQRT(norm_doc) * SQRT(norm_query)); " +
                "END";

        try (Statement stmt = conn.createStatement()) {
            stmt.execute(functionSQL);
        }
    }



    /**
     * 创建 MySqlRepository 构建器
     */
    public static Builder builder(EmbeddingModel embeddingModel, DataSource dataSource) {
        return new Builder(embeddingModel, dataSource);
    }

    /**
     * Builder 类用于链式构建 MySqlRepository
     */
    public static class Builder {
        // 必需参数
        private final EmbeddingModel embeddingModel;
        private final DataSource dataSource;

        // 可选参数，设置默认值
        private String tableName = "solon_ai_documents";
        private List<MetadataField> metadataFields = new ArrayList<>();

        /**
         * 构造器
         */
        public Builder(EmbeddingModel embeddingModel, DataSource dataSource) {
            this.embeddingModel = embeddingModel;
            this.dataSource = dataSource;
        }

        /**
         * 设置表名
         */
        public Builder tableName(String tableName) {
            this.tableName = tableName;
            return this;
        }

        /**
         * 设置元数据字段
         */
        public Builder metadataFields(List<MetadataField> metadataFields) {
            this.metadataFields = metadataFields;
            return this;
        }

        /**
         * 构建 MySqlRepository 实例
         */
        public MySqlRepository build() {
            return new MySqlRepository(this);
        }
    }
}