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

import org.noear.snack.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.embedding.EmbeddingModel;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.RepositoryLifecycle;
import org.noear.solon.ai.rag.RepositoryStorable;
import org.noear.solon.ai.rag.repository.pgvector.FilterTransformer;
import org.noear.solon.ai.rag.repository.pgvector.MetadataField;
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
 * PostgreSQL pgvector 矢量存储知识库
 *
 * @author 小奶奶花生米
 */
public class PgVectorRepository implements RepositoryStorable, RepositoryLifecycle {
    private final Builder config;
    private final DataSource dataSource;

    /**
     * 私有构造函数，通过 Builder 模式创建
     */
    private PgVectorRepository(Builder config) {
        this.config = config;
        this.dataSource = config.dataSource;

        try {
            initRepository();
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize pgvector repository", e);
        }
    }

    /**
     * 初始化仓库
     */
    @Override
    public void initRepository() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            // 确保 pgvector 扩展已安装
            ensurePgVectorExtension(conn);

            // 检查表是否存在
            if (!tableExists(conn, config.tableName)) {
                createTable(conn);
            }
        } catch (SQLException e) {
            throw new Exception("Failed to initialize pgvector repository", e);
        } catch (IOException e) {
            throw new Exception("Failed to initialize pgvector repository", e);
        }
    }

    /**
     * 确保 pgvector 扩展已安装
     */
    private void ensurePgVectorExtension(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE EXTENSION IF NOT EXISTS vector");
        }
    }

    /**
     * 检查表是否存在
     */
    private boolean tableExists(Connection conn, String tableName) throws SQLException {
        String sql = "SELECT EXISTS (SELECT FROM information_schema.tables WHERE table_name = ?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    /**
     * 创建表
     */
    private void createTable(Connection conn) throws SQLException, IOException {
        StringBuilder sql = new StringBuilder();
        sql.append("CREATE TABLE ").append(config.tableName).append(" (");
        sql.append("id VARCHAR(255) PRIMARY KEY,");
        sql.append("content TEXT NOT NULL,");
        sql.append("embedding VECTOR(").append(config.embeddingModel.dimensions()).append("),");
        sql.append("metadata JSONB,");
        sql.append("created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP");

        // 添加元数据字段
        if (Utils.isNotEmpty(config.metadataFields)) {
            for (MetadataField field : config.metadataFields) {
                switch (field.getFieldType()) {
                    case TEXT:
                        sql.append(", \"").append(field.getName()).append("\" TEXT");
                        break;
                    case NUMERIC:
                        sql.append(", \"").append(field.getName()).append("\" NUMERIC");
                        break;
                    case JSON:
                        sql.append(", \"").append(field.getName()).append("\" JSONB");
                        break;
                }
            }
        }

        sql.append(")");

        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql.toString());
        }

        // 创建向量索引
        String indexSql = String.format(
                "CREATE INDEX IF NOT EXISTS idx_%s_embedding ON %s USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100)",
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
            stmt.execute("DROP TABLE IF EXISTS " + config.tableName);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to drop pgvector repository", e);
        }
    }

    /**
     * 存储文档列表
     */
    @Override
    public void insert(List<Document> documents, BiConsumer<Integer, Integer> progressCallback) throws IOException {
        if (Utils.isEmpty(documents)) {
            //回调进度
            progressCallback.accept(0, 0);
            return;
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
            progressCallback.accept(batchIndex++, batchList.size());
        }
    }

    /**
     * 批量插入文档
     */
    private void batchInsertDo(Connection conn, List<Document> documents) throws SQLException {
        StringBuilder sql = new StringBuilder();
        sql.append("INSERT INTO ").append(config.tableName).append(" (id, content, embedding, metadata");

        // 添加元数据字段
        if (Utils.isNotEmpty(config.metadataFields)) {
            for (MetadataField field : config.metadataFields) {
                sql.append(", \"").append(field.getName()).append("\"");
            }
        }

        sql.append(") VALUES (?, ?, ?, ?");

        // 添加元数据字段占位符
        if (Utils.isNotEmpty(config.metadataFields)) {
            for (int i = 0; i < config.metadataFields.size(); i++) {
                sql.append(", ?");
            }
        }

        sql.append(") ON CONFLICT (id) DO UPDATE SET ");
        sql.append("content = EXCLUDED.content,");
        sql.append("embedding = EXCLUDED.embedding,");
        sql.append("metadata = EXCLUDED.metadata");

        // 添加元数据字段更新
        if (Utils.isNotEmpty(config.metadataFields)) {
            for (MetadataField field : config.metadataFields) {
                sql.append(", \"").append(field.getName()).append("\" = EXCLUDED.\"").append(field.getName()).append("\"");
            }
        }

        try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            for (Document doc : documents) {
                if (doc.getId() == null) {
                    doc.id(Utils.uuid());
                }

                int paramIndex = 1;
                stmt.setString(paramIndex++, doc.getId());
                stmt.setString(paramIndex++, doc.getContent());
                stmt.setArray(paramIndex++, conn.createArrayOf("float4", toFloatArray(doc.getEmbedding())));
                stmt.setObject(paramIndex++, ONode.stringify(doc.getMetadata()), Types.OTHER);

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
                                        stmt.setNull(paramIndex++, Types.NUMERIC);
                                    }
                                    break;
                                case JSON:
                                    stmt.setObject(paramIndex++, ONode.stringify(value), Types.OTHER);
                                    break;
                            }
                        } else {
                            // 根据字段类型设置正确的 NULL 类型
                            switch (field.getFieldType()) {
                                case TEXT:
                                    stmt.setNull(paramIndex++, Types.VARCHAR);
                                    break;
                                case NUMERIC:
                                    stmt.setNull(paramIndex++, Types.NUMERIC);
                                    break;
                                case JSON:
                                    stmt.setNull(paramIndex++, Types.OTHER);
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
    public void delete(String... ids) throws IOException {
        if (ids == null || ids.length == 0) {
            return;
        }

        String sql = "DELETE FROM " + config.tableName + " WHERE id = ANY(?)";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setArray(1, conn.createArrayOf("varchar", ids));
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IOException("Failed to delete documents", e);
        }
    }

    /**
     * 检查文档是否存在
     */
    @Override
    public boolean exists(String id) throws IOException {
        String sql = "SELECT COUNT(*) FROM " + config.tableName + " WHERE id = ?";

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

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT id, content, metadata, 1 - (embedding <=> ?::vector) as similarity ");
        sql.append("FROM ").append(config.tableName);

        // 添加过滤条件
        String filterClause = FilterTransformer.getInstance().transform(condition.getFilterExpression());
        if (Utils.isNotEmpty(filterClause)) {
            sql.append(" WHERE ").append(filterClause);
        }

        sql.append(" ORDER BY embedding <=> ?::vector");
        sql.append(" LIMIT ?");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {

            int paramIndex = 1;
            stmt.setArray(paramIndex++, conn.createArrayOf("float4", toFloatArray(queryEmbedding)));
            stmt.setArray(paramIndex++, conn.createArrayOf("float4", toFloatArray(queryEmbedding)));
            stmt.setInt(paramIndex++, condition.getLimit());

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
        } catch (SQLException e) {
            throw new IOException("Failed to search documents", e);
        }
    }

    /**
     * 将 float 数组转换为 Float 数组
     */
    private Float[] toFloatArray(float[] array) {
        if (array == null) {
            return new Float[0];
        }
        Float[] result = new Float[array.length];
        for (int i = 0; i < array.length; i++) {
            result[i] = array[i];
        }
        return result;
    }

    /**
     * 创建 PgVectorRepository 构建器
     */
    public static Builder builder(EmbeddingModel embeddingModel, DataSource dataSource) {
        return new Builder(embeddingModel, dataSource);
    }

    /**
     * Builder 类用于链式构建 PgVectorRepository
     */
    public static class Builder {
        // 必需参数
        private final EmbeddingModel embeddingModel;
        private final DataSource dataSource;

        // 可选参数，设置默认值
        private String tableName = "solon_ai";
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
         * 构建 PgVectorRepository 实例
         */
        public PgVectorRepository build() {
            return new PgVectorRepository(this);
        }
    }
}