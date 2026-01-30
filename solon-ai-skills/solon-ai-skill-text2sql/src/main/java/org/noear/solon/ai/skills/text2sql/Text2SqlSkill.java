package org.noear.solon.ai.skills.text2sql;

import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.annotation.Param;
import org.noear.solon.core.util.Assert;
import org.noear.solon.data.sql.SqlUtils;

import javax.sql.DataSource;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 工业级通用智能 SQL 转换技能 (支持国产数据库)
 */
public class Text2SqlSkill extends AbsSkill {
    private final SqlUtils sqlUtils;
    private final List<String> tableNames;
    private final String cachedSchemaInfo;

    private int maxRows = 50;
    private int maxContextLength = 8000;
    private String dialectName = "Generic SQL";

    public Text2SqlSkill(DataSource dataSource, String... tables) {
        super();
        this.sqlUtils = SqlUtils.of(dataSource);
        this.tableNames = Arrays.asList(tables);
        this.cachedSchemaInfo = extractSchemaInfo(dataSource, tableNames);
    }

    public Text2SqlSkill maxRows(int maxRows) { this.maxRows = maxRows; return this; }

    @Override
    public String name() { return "sql_expert"; }

    @Override
    public String description() {
        return "数据库专家：深度理解多表结构、主外键及国产方言（达梦、金仓等）。";
    }

    @Override
    public String getInstruction(Prompt prompt) {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        return "你是一个高级 " + dialectName + " 数据专家。当前系统时间: " + now + "\n" +
                "--- SCHEMA START ---\n" + cachedSchemaInfo + "\n--- SCHEMA END ---\n" +
                "【执行准则】：\n" +
                "1. 务必参考字段注释理解业务含义。优先通过 [PK] 和 [FK] 进行表关联。\n" +
                "2. 仅允许 SELECT。严禁多条语句并行（禁止分号）。\n" +
                "3. 必须适配 " + dialectName + " 的特定语法（如分页、日期函数）。";
    }

    @ToolMapping(name = "execute_sql", description = "执行单条只读 SELECT 语句并获取结果。")
    public String executeSql(@Param("sql") String sql) {
        if (Assert.isBlank(sql)) return "Error: SQL is empty.";
        String cleanSql = sql.trim();
        String upperSql = cleanSql.toUpperCase();

        if (!upperSql.startsWith("SELECT")) return "Error: Only SELECT is allowed.";
        if (upperSql.contains(";") || upperSql.contains("--")) return "Error: Illegal characters.";

        // 自动分页逻辑补全 (针对不同方言)
        if (!upperSql.contains("LIMIT") && !upperSql.contains("FETCH FIRST") && !upperSql.contains("TOP ")) {
            if (dialectName.contains("MySQL") || dialectName.contains("PostgreSQL") || dialectName.contains("Kingbase")) {
                cleanSql += " LIMIT " + maxRows;
            } else if (dialectName.contains("Dameng") || dialectName.contains("Oracle")) {
                cleanSql += " FETCH FIRST " + maxRows + " ROWS ONLY";
            }
        }

        try {
            List<Map> rows = sqlUtils.sql(cleanSql).queryRowList(Map.class);
            String json = ONode.serialize(rows);
            return json.length() > maxContextLength ? json.substring(0, maxContextLength) + "... [Truncated]" : json;
        } catch (SQLException e) {
            return "SQL Error: " + e.getMessage();
        }
    }

    private String extractSchemaInfo(DataSource ds, List<String> tables) {
        StringBuilder sb = new StringBuilder();
        try (Connection conn = ds.getConnection()) {
            DatabaseMetaData dbMeta = conn.getMetaData();
            this.dialectName = dbMeta.getDatabaseProductName();
            String catalog = conn.getCatalog();
            String schema = conn.getSchema();

            for (String tableName : tables) {
                sb.append("Table: ").append(tableName);

                // 1. 获取表注释
                try (ResultSet rs = dbMeta.getTables(catalog, schema, tableName, new String[]{"TABLE"})) {
                    if (rs.next()) {
                        String remarks = rs.getString("REMARKS");
                        if (Assert.isNotEmpty(remarks)) sb.append(" (").append(remarks).append(")");
                    }
                }

                // 2. 提取主外键
                Set<String> pks = new HashSet<>();
                try (ResultSet rs = dbMeta.getPrimaryKeys(catalog, schema, tableName)) {
                    while (rs.next()) pks.add(rs.getString("COLUMN_NAME"));
                }
                Map<String, String> fks = new HashMap<>();
                try (ResultSet rs = dbMeta.getImportedKeys(catalog, schema, tableName)) {
                    while (rs.next()) fks.put(rs.getString("FKCOLUMN_NAME"), rs.getString("PKTABLE_NAME"));
                }

                // 3. 提取字段列表
                sb.append("\nColumns:\n");
                try (ResultSet rs = dbMeta.getColumns(catalog, schema, tableName, null)) {
                    while (rs.next()) {
                        String col = rs.getString("COLUMN_NAME");
                        sb.append(String.format(" - %s %s %s%s // %s\n",
                                col, rs.getString("TYPE_NAME"),
                                pks.contains(col) ? "[PK]" : "",
                                fks.containsKey(col) ? "[FK->"+fks.get(col)+"]" : "",
                                Utils.valueOr(rs.getString("REMARKS"), "")));
                    }
                }

                // 4. 方言适配 DDL
                sb.append("DDL: ").append(getDdl(conn, tableName)).append("\n");
                sb.append("------------------------------------------\n");
            }
        } catch (Exception e) { return "Error: " + e.getMessage(); }
        return sb.toString();
    }

    private String getDdl(Connection conn, String tableName) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        String dbType = meta.getDatabaseProductName().toUpperCase();

        // 达梦 (Dameng) - 往往支持 DBMS_METADATA 或类似 Oracle 的查询
        if (dbType.contains("DM") || dbType.contains("DAMENG")) {
            try (PreparedStatement pstmt = conn.prepareStatement("SELECT DBMS_METADATA.GET_DDL('TABLE', ?) FROM DUAL")) {
                pstmt.setString(1, tableName.toUpperCase());
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) return rs.getString(1);
                }
            } catch (Exception e) { /* Fallback */ }
        }

        // 人大金仓 (Kingbase) - 兼容 PG 风格
        if (dbType.contains("KINGBASE") || dbType.contains("POSTGRESQL")) {
            // 人大金仓通常支持标准 PG 系统查询，但也提供原生函数
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT pg_get_tabledef('" + tableName + "')")) {
                if (rs.next()) return rs.getString(1);
            } catch (Exception e) { /* Fallback */ }
        }

        // MySQL / H2 原生适配
        if (dbType.contains("MYSQL") || dbType.contains("H2")) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SHOW CREATE TABLE " + tableName)) {
                return rs.next() ? rs.getString(2) : "";
            }
        }

        // 通用 JDBC 逻辑拼装兜底 (适用于所有不支持原文 DDL 的库)
        return buildMockDdl(meta, conn.getCatalog(), conn.getSchema(), tableName);
    }

    private String buildMockDdl(DatabaseMetaData meta, String cat, String sche, String tab) throws SQLException {
        StringBuilder sb = new StringBuilder("CREATE TABLE " + tab + " (\n");
        try (ResultSet rs = meta.getColumns(cat, sche, tab, null)) {
            boolean first = true;
            while (rs.next()) {
                if (!first) sb.append(",\n");
                sb.append("  ").append(rs.getString("COLUMN_NAME")).append(" ").append(rs.getString("TYPE_NAME"));
                first = false;
            }
        }
        return sb.append("\n);").toString();
    }
}