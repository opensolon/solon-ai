/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.rag.repository;

import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.embedding.EmbeddingModel;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.RepositoryLifecycle;
import org.noear.solon.ai.rag.RepositoryStorable;
import org.noear.solon.ai.rag.repository.mariadb.MetadataField;
import org.noear.solon.ai.rag.repository.mariadb.FilterTransformer;
import org.noear.solon.ai.rag.util.ListUtil;
import org.noear.solon.ai.rag.util.QueryCondition;
import org.noear.solon.ai.rag.util.SimilarityUtil;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * MariaDB Vector 矢量存储知识库
 * <p>
 * 使用 MariaDB 原生 VECTOR 数据类型，支持 HNSW 索引和向量相似度搜索
 *
 * @author chw
 */
public class MariaDbRepository implements RepositoryStorable, RepositoryLifecycle {
    private final Builder config;
    private final DataSource dataSource;

    private MariaDbRepository(Builder config) {
        this.config = config;
        this.dataSource = config.dataSource;

        try {
            initRepository();
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize MariaDB repository", e);
        }
    }

    @Override
    public void initRepository() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            if (!tableExists(conn, config.tableName)) {
                createTable(conn);
            }
        } catch (SQLException e) {
            throw new Exception("Failed to initialize MariaDB repository", e);
        }
    }

    private boolean tableExists(Connection conn, String tableName) throws SQLException {
        String sql = "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    private int getEmbeddingDimensions() {
        try {
            return config.embeddingModel.dimensions();
        } catch (Exception e) {
            throw new RuntimeException("Failed to get embedding dimensions", e);
        }
    }

    private void createTable(Connection conn) throws SQLException {
        StringBuilder sql = new StringBuilder();
        sql.append("CREATE TABLE `").append(config.tableName).append("` (");
        sql.append("`id` VARCHAR(36) PRIMARY KEY,");
        sql.append("`content` TEXT NOT NULL,");
        sql.append("`embedding` VECTOR(").append(getEmbeddingDimensions()).append(") NOT NULL,");
        sql.append("`metadata` JSON,");
        sql.append("`created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP");

        sql.append(buildMetadataFieldsSql());

        sql.append(")");

        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql.toString());
        }

        // 创建 HNSW 向量索引
        String indexSql = String.format(
                "CREATE VECTOR INDEX `idx_%s_embedding` ON `%s` (`embedding`) M=6 DISTANCE=euclidean",
                config.tableName, config.tableName
        );

        try (Statement stmt = conn.createStatement()) {
            stmt.execute(indexSql);
        }
    }

    /**
     * 构建元数据字段的 SQL 片段
     *
     * @return 元数据字段 SQL 片段
     */
    private String buildMetadataFieldsSql() {
        StringBuilder sql = new StringBuilder();
        if (Utils.isNotEmpty(config.metadataFields)) {
            for (MetadataField field : config.metadataFields) {
                sql.append(", `").append(field.getName()).append("` ").append(toSqlType(field.getFieldType()));
            }
        }
        return sql.toString();
    }

    /**
     * 将字段类型转换为 SQL 类型
     *
     * @param fieldType 字段类型
     * @return SQL 类型字符串
     */
    private String toSqlType(MetadataField.FieldType fieldType) {
        switch (fieldType) {
            case TEXT:
                return "TEXT";
            case NUMERIC:
                return "DECIMAL(65,30)";
            case JSON:
                return "JSON";
            default:
                return "TEXT";
        }
    }

    /**
     * 将字段类型转换为 SQL 类型索引（用于 setNull）
     *
     * @param fieldType 字段类型
     * @return SQL 类型常量
     */
    private int toSqlTypeIndex(MetadataField.FieldType fieldType) {
        switch (fieldType) {
            case TEXT:
                return Types.VARCHAR;
            case NUMERIC:
                return Types.DECIMAL;
            case JSON:
                return Types.VARCHAR;
            default:
                return Types.VARCHAR;
        }
    }

    /**
     * 设置参数到 PreparedStatement
     *
     * @param stmt       PreparedStatement
     * @param paramIndex 参数索引
     * @param value      值
     * @param fieldType  字段类型
     * @throws SQLException SQL 异常
     */
    private void setParameter(PreparedStatement stmt, int paramIndex, Object value, MetadataField.FieldType fieldType) throws SQLException {
        if (value != null) {
            switch (fieldType) {
                case TEXT:
                    stmt.setString(paramIndex, value.toString());
                    break;
                case NUMERIC:
                    if (value instanceof Number) {
                        stmt.setBigDecimal(paramIndex, new java.math.BigDecimal(value.toString()));
                    } else {
                        stmt.setNull(paramIndex, Types.DECIMAL);
                    }
                    break;
                case JSON:
                    stmt.setObject(paramIndex, ONode.serialize(value), Types.VARCHAR);
                    break;
            }
        } else {
            stmt.setNull(paramIndex, toSqlTypeIndex(fieldType));
        }
    }

    @Override
    public void dropRepository() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS `" + config.tableName + "`");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to drop MariaDB repository", e);
        }
    }

    @Override
    public void save(List<Document> documents, BiConsumer<Integer, Integer> progressCallback) throws IOException {
        if (Utils.isEmpty(documents)) {
            if (progressCallback != null) {
                progressCallback.accept(0, 0);
            }
            return;
        }

        for (Document doc : documents) {
            if (Utils.isEmpty(doc.getId())) {
                doc.id(Utils.uuid());
            }
        }

        List<List<Document>> batchList = ListUtil.partition(documents, config.embeddingModel.batchSize());
        int batchIndex = 0;
        for (List<Document> batch : batchList) {
            config.embeddingModel.embed(batch);

            try (Connection conn = dataSource.getConnection()) {
                batchSaveDo(conn, batch);
            } catch (SQLException e) {
                throw new IOException("Failed to insert documents", e);
            }

            if (progressCallback != null) {
                progressCallback.accept(++batchIndex, batchList.size());
            }
        }
    }

    private void batchSaveDo(Connection conn, List<Document> documents) throws SQLException {
        String sql = buildInsertSql();

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (Document doc : documents) {
                setDocumentParameters(stmt, doc);
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }

    /**
     * 构建插入 SQL 语句
     *
     * @return 插入 SQL 语句
     */
    private String buildInsertSql() {
        StringBuilder sql = new StringBuilder();
        sql.append("INSERT INTO `").append(config.tableName).append("` (`id`, `content`, `embedding`, `metadata`");

        if (Utils.isNotEmpty(config.metadataFields)) {
            for (MetadataField field : config.metadataFields) {
                sql.append(", `").append(field.getName()).append("`");
            }
        }

        sql.append(") VALUES (?, ?, VEC_FromText(?), ?");

        if (Utils.isNotEmpty(config.metadataFields)) {
            for (int i = 0; i < config.metadataFields.size(); i++) {
                sql.append(", ?");
            }
        }

        sql.append(") ON DUPLICATE KEY UPDATE ");
        sql.append("`content` = VALUES(`content`),");
        sql.append("`embedding` = VALUES(`embedding`),");
        sql.append("`metadata` = VALUES(`metadata`)");

        if (Utils.isNotEmpty(config.metadataFields)) {
            for (MetadataField field : config.metadataFields) {
                sql.append(", `").append(field.getName()).append("` = VALUES(`").append(field.getName()).append("`)");
            }
        }

        return sql.toString();
    }

    /**
     * 设置文档参数到 PreparedStatement
     *
     * @param stmt PreparedStatement
     * @param doc  文档
     * @throws SQLException SQL 异常
     */
    private void setDocumentParameters(PreparedStatement stmt, Document doc) throws SQLException {
        int paramIndex = 1;
        stmt.setString(paramIndex++, doc.getId());
        stmt.setString(paramIndex++, doc.getContent());
        stmt.setString(paramIndex++, floatArrayToText(doc.getEmbedding()));
        stmt.setObject(paramIndex++, ONode.serialize(doc.getMetadata()), Types.VARCHAR);

        if (Utils.isNotEmpty(config.metadataFields)) {
            for (MetadataField field : config.metadataFields) {
                Object value = doc.getMetadata().get(field.getName());
                setParameter(stmt, paramIndex++, value, field.getFieldType());
            }
        }
    }

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

    @Override
    public List<Document> search(QueryCondition condition) throws IOException {
        float[] queryEmbedding = config.embeddingModel.embed(condition.getQuery());

        String sql = buildSearchSql(condition);
        String queryVectorText = floatArrayToText(queryEmbedding);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement execStmt = conn.prepareStatement(sql)) {
            execStmt.setString(1, queryVectorText);
            execStmt.setInt(2, condition.getLimit());

            List<Document> results = new ArrayList<>();
            try (ResultSet rs = execStmt.executeQuery()) {
                while (rs.next()) {
                    Document doc = mapResultSetToDocument(rs, queryEmbedding);
                    results.add(doc);
                }
            }

            return SimilarityUtil.refilter(results.stream(), condition);

        } catch (SQLException e) {
            throw new IOException("Failed to search documents", e);
        }
    }

    /**
     * 构建搜索 SQL 语句
     *
     * @param condition 查询条件
     * @return 搜索 SQL 语句
     */
    private String buildSearchSql(QueryCondition condition) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT `id`, `content`, `metadata`, VEC_ToText(`embedding`) as `embedding` ");
        sql.append("FROM `").append(config.tableName).append("`");

        String filterClause = FilterTransformer.getInstance().transform(condition.getFilterExpression());
        if (Utils.isNotEmpty(filterClause)) {
            sql.append(" WHERE ").append(filterClause);
        }

        sql.append(" ORDER BY VEC_DISTANCE_EUCLIDEAN(`embedding`, VEC_FromText(?))");
        sql.append(" LIMIT ?");

        return sql.toString();
    }

    /**
     * 将 ResultSet 映射为 Document 对象
     *
     * @param rs             ResultSet
     * @param queryEmbedding 查询向量
     * @return Document 对象
     * @throws SQLException SQL 异常
     */
    private Document mapResultSetToDocument(ResultSet rs, float[] queryEmbedding) throws SQLException {
        String id = rs.getString("id");
        String content = rs.getString("content");
        String metadataJson = rs.getString("metadata");
        String embeddingText = rs.getString("embedding");

        float[] docEmbedding = parseVectorText(embeddingText);
        double similarity = SimilarityUtil.cosineSimilarity(queryEmbedding, docEmbedding);

        Map<String, Object> metadata = parseMetadata(metadataJson);

        return new Document(id, content, metadata, similarity);
    }

    /**
     * 解析元数据 JSON 字符串
     *
     * @param metadataJson 元数据 JSON 字符串
     * @return 元数据 Map
     */
    private Map<String, Object> parseMetadata(String metadataJson) {
        Map<String, Object> metadata = new HashMap<>();
        if (Utils.isNotEmpty(metadataJson)) {
            metadata = ONode.deserialize(metadataJson, Map.class);
        }
        return metadata;
    }

    /**
     * 将 float 数组转换为 MariaDB VECTOR 格式字符串
     * <p>
     * 使用 %.4g 格式限制精度，防止浮点数精度问题影响向量搜索准确性
     * 例如：0.1f 会变成 0.10000000149011612，直接拼接会导致精度问题
     *
     * @param array float 数组
     * @return MariaDB VECTOR 格式字符串
     */
    private String floatArrayToText(float[] array) {
        if (array == null) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < array.length; i++) {
            if (i > 0) sb.append(",");
            // 使用 %.4g 格式，保留 4 位有效数字，平衡精度和存储空间
            sb.append(String.format("%.4g", array[i]));
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * 将 MariaDB VECTOR 格式文本解析为 float 数组
     *
     * @param text VECTOR 格式文本，如 "[0.1,0.2,0.3]"
     * @return float 数组
     */
    private float[] parseVectorText(String text) {
        if (text == null || text.isEmpty()) {
            return new float[0];
        }
        // 移除方括号并分割
        String trimmed = text.trim();
        String content = trimmed.startsWith("[") ? trimmed.substring(1) : trimmed;
        content = content.endsWith("]") ? content.substring(0, content.length() - 1) : content;

        // 使用 Stream API 转换
        java.util.List<String> parts = java.util.Arrays.asList(content.split(","));
        java.util.List<Double> doubles = parts.stream()
                .map(String::trim)
                .map(Double::parseDouble)
                .collect(java.util.stream.Collectors.toList());
        float[] result = new float[doubles.size()];
        for (int i = 0; i < doubles.size(); i++) {
            result[i] = doubles.get(i).floatValue();
        }
        return result;
    }

    public static Builder builder(EmbeddingModel embeddingModel, DataSource dataSource) {
        return new Builder(embeddingModel, dataSource);
    }

    public static class Builder {
        private final EmbeddingModel embeddingModel;
        private final DataSource dataSource;
        private String tableName = "solon_ai_documents";
        private List<MetadataField> metadataFields = new ArrayList<>();

        public Builder(EmbeddingModel embeddingModel, DataSource dataSource) {
            this.embeddingModel = embeddingModel;
            this.dataSource = dataSource;
        }

        public Builder tableName(String tableName) {
            this.tableName = tableName;
            return this;
        }

        public Builder metadataFields(List<MetadataField> metadataFields) {
            this.metadataFields = metadataFields;
            return this;
        }

        public MariaDbRepository build() {
            return new MariaDbRepository(this);
        }
    }
}
