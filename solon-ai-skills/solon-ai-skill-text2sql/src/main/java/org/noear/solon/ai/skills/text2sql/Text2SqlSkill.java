/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.skills.text2sql;

import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.annotation.Param;
import org.noear.solon.core.util.Assert;
import org.noear.solon.data.sql.SqlUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 智能 SQL 转换技能
 *
 * @author noear
 * @since 3.9.1
 */
public class Text2SqlSkill extends AbsSkill {
    private final static Logger LOG = LoggerFactory.getLogger(Text2SqlSkill.class);

    private final SqlUtils sqlUtils;
    private final List<String> tableNames;
    private final String cachedSchemaInfo;

    private int maxRows = 50;
    private int maxContextLength = 8000;
    private String dialectName = "Generic SQL";

    public Text2SqlSkill(DataSource dataSource, String... tables) {
        this(SqlUtils.of(dataSource), tables);
    }

    public Text2SqlSkill(SqlUtils sqlUtils, String... tables) {
        super();
        this.sqlUtils = sqlUtils;
        this.tableNames = Arrays.asList(tables);
        this.cachedSchemaInfo = extractSchemaInfo(tableNames);
    }

    public Text2SqlSkill maxRows(int maxRows) { this.maxRows = maxRows; return this; }
    public Text2SqlSkill maxContextLength(int length) { this.maxContextLength = length; return this; }

    @Override
    public String name() { return "sql_expert"; }

    @Override
    public String description() {
        return "数据库专家：深度理解多表结构、主外键及国产方言，支持语义化查询。";
    }

    @Override
    public String getInstruction(Prompt prompt) {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        return "你是一个高级 " + dialectName + " 数据专家。当前系统时间: " + now + "\n" +
                "【数据库结构说明】:\n" + cachedSchemaInfo + "\n" +
                "【执行准则】:\n" +
                "1. 参考字段注释理解业务逻辑。优先通过 [PK] 和 [FK] 进行表关联。\n" +
                "2. 仅允许 SELECT。必须适配 " + dialectName + " 的特定语法（如分页、日期函数）。\n" +
                "3. 严禁多条语句并行，严禁增删改操作。";
    }

    @ToolMapping(name = "execute_sql", description = "执行单条只读 SELECT 语句并获取 JSON 结果。")
    public String executeSql(@Param("sql") String sql) {
        if (Assert.isBlank(sql)) {
            return "Error: SQL is empty.";
        }

        if(LOG.isTraceEnabled()){
            LOG.trace("Executing SQL: {}", sql);
        }

        String cleanSql = sql.trim();
        String upperSql = cleanSql.toUpperCase();

        if (!upperSql.startsWith("SELECT")) return "Error: Only SELECT is permitted.";
        if (upperSql.contains(";") || upperSql.contains("--") || upperSql.contains("/*")) return "Error: Restricted characters.";

        // 分页补全逻辑
        if (!upperSql.contains("LIMIT") && !upperSql.contains("FETCH FIRST") && !upperSql.contains("TOP ")) {
            if (dialectName.contains("MySQL") || dialectName.contains("PostgreSQL") ||
                    dialectName.contains("Kingbase") || dialectName.contains("H2") || dialectName.contains("SQLite")) {
                cleanSql += " LIMIT " + maxRows;
            } else if (dialectName.contains("Dameng") || dialectName.contains("Oracle") || dialectName.contains("DB2")) {
                cleanSql += " FETCH FIRST " + maxRows + " ROWS ONLY";
            } else if (dialectName.contains("SQL Server")) {
                if(!upperSql.startsWith("SELECT TOP")) {
                    cleanSql = cleanSql.replaceFirst("(?i)SELECT", "SELECT TOP " + maxRows);
                }
            }
        }

        try {
            List<Map> rows = sqlUtils.sql(cleanSql).queryRowList(Map.class);
            if (rows == null || rows.isEmpty()) return "Query OK. No data.";
            String json = ONode.serialize(rows);
            return json.length() > maxContextLength ? json.substring(0, maxContextLength) + "... [Truncated]" : json;
        } catch (SQLException e) {
            return "SQL Error: " + e.getMessage();
        }
    }

    private String extractSchemaInfo(List<String> tables) {
        StringBuilder sb = new StringBuilder();

        try (Connection conn = sqlUtils.getDataSource().getConnection()) {
            DatabaseMetaData dbMeta = conn.getMetaData();
            this.dialectName = dbMeta.getDatabaseProductName();
            String catalog = conn.getCatalog();
            String schema = conn.getSchema();

            for (String tableName : tables) {
                // 1. 获取表名与注释
                sb.append("Table: ").append(tableName);
                try (ResultSet rs = dbMeta.getTables(catalog, schema, tableName, new String[]{"TABLE"})) {
                    if (rs.next()) {
                        String remarks = rs.getString("REMARKS");
                        if (Assert.isNotEmpty(remarks)) sb.append(" // ").append(remarks);
                    }
                }
                sb.append("\nColumns:\n");

                // 2. 提取主键与外键索引
                Set<String> pks = new HashSet<>();
                try (ResultSet rs = dbMeta.getPrimaryKeys(catalog, schema, tableName)) {
                    while (rs.next()) pks.add(rs.getString("COLUMN_NAME"));
                }
                Map<String, String> fks = new HashMap<>();
                try (ResultSet rs = dbMeta.getImportedKeys(catalog, schema, tableName)) {
                    while (rs.next()) fks.put(rs.getString("FKCOLUMN_NAME"), rs.getString("PKTABLE_NAME"));
                }

                // 3. 生成“瘦身版”结构 (核心：字段 + 类型 + 标记 + 注释)
                try (ResultSet rs = dbMeta.getColumns(catalog, schema, tableName, null)) {
                    while (rs.next()) {
                        String col = rs.getString("COLUMN_NAME");
                        String type = rs.getString("TYPE_NAME");
                        String remarks = Utils.valueOr(rs.getString("REMARKS"), "");

                        sb.append(" - ").append(col).append(" ").append(type);
                        if (pks.contains(col)) sb.append(" [PK]");
                        if (fks.containsKey(col)) sb.append(" [FK->").append(fks.get(col)).append("]");
                        if (Assert.isNotEmpty(remarks)) sb.append(" // ").append(remarks);
                        sb.append("\n");
                    }
                }
                sb.append("------------------------------------------\n");
            }
        } catch (Exception e) {
            LOG.error("Extract schema error", e);
            return "Metadata Error: " + e.getMessage();
        }
        return sb.toString();
    }
}