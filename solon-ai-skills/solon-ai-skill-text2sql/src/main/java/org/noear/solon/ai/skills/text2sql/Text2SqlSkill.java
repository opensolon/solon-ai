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
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.annotation.Param;
import org.noear.solon.core.util.Assert;
import org.noear.solon.data.sql.SqlUtils;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 智能 SQL 转换技能 (通用增强版)
 * 适配主流数据库（MySQL, PG, Oracle, SQL Server）及国产数据库（达梦, 金仓, 瀚高）
 *
 * @author noear
 * @since 3.9.1.2
 */
@Preview("3.9.1.2")
public class Text2SqlSkill extends AbsSkill {
    protected final static Logger LOG = LoggerFactory.getLogger(Text2SqlSkill.class);

    // 预编译保留字别名修复正则（防御 AI 遗漏引号）
    private static final Pattern RESERVED_ALIAS_PATTERN = Pattern.compile(
            "(?i)\\s+AS\\s+(YEAR|MONTH|DAY|ORDER|USER|GROUP|LIMIT|DESC|ASC|TABLE|KEY|LEVEL|VALUE)(\\s+|,|$)",
            Pattern.CASE_INSENSITIVE);

    protected final SqlUtils sqlUtils;
    protected final List<String> tableNames;
    protected final Map<String, String> tableRemarksMap = new LinkedHashMap<>();
    protected final Map<String, List<String>> globalRelations = new HashMap<>();
    protected final String cachedSchemaInfo;

    protected int maxRows = 50;
    protected int maxContextLength = 8000;
    protected String dialectName = "Generic SQL";
    protected SchemaMode schemaMode = SchemaMode.FULL;
    protected boolean readOnly = true;

    public Text2SqlSkill(DataSource dataSource, String... tables) {
        this(SqlUtils.of(dataSource), tables);
    }

    public Text2SqlSkill(SqlUtils sqlUtils, String... tables) {
        super();
        this.sqlUtils = sqlUtils;
        this.tableNames = Arrays.asList(tables);

        // 初始化元数据并自动识别方言
        loadGlobalMetadata();

        this.schemaMode = tableNames.size() > 20 ? SchemaMode.DYNAMIC : SchemaMode.FULL;
        this.cachedSchemaInfo = (schemaMode == SchemaMode.FULL) ? extractSchemaInfo(tableNames) : null;
    }

    private void loadGlobalMetadata() {
        try (Connection conn = sqlUtils.getDataSource().getConnection()) {
            DatabaseMetaData dbMeta = conn.getMetaData();
            // 自动识别方言名称（如 "H2", "MySQL", "DM DBMS" 等）
            this.dialectName = dbMeta.getDatabaseProductName();
            String catalog = conn.getCatalog();

            for (String tableName : tableNames) {
                // 1. 获取表注释
                try (ResultSet rs = dbMeta.getTables(catalog, null, tableName, new String[]{"TABLE", "VIEW"})) {
                    if (rs.next()) {
                        String remarks = rs.getString("REMARKS");
                        if (Utils.isNotEmpty(remarks)) tableRemarksMap.put(tableName, remarks);
                    }
                }
                // 2. 获取关联关系（外键线索）
                try (ResultSet rs = dbMeta.getImportedKeys(catalog, null, tableName)) {
                    while (rs.next()) {
                        String pkTable = rs.getString("PKTABLE_NAME");
                        String pkCol = rs.getString("PKCOLUMN_NAME");
                        String fkCol = rs.getString("FKCOLUMN_NAME");
                        String rel = tableName + "." + fkCol + " -> " + pkTable + "." + pkCol;
                        globalRelations.computeIfAbsent(tableName, k -> new ArrayList<>()).add(rel);
                    }
                }
            }
        } catch (Exception e) {
            LOG.error("Load global metadata error", e);
        }
    }

    public Text2SqlSkill maxRows(int maxRows) {
        this.maxRows = maxRows;
        return this;
    }

    public Text2SqlSkill maxContextLength(int length) {
        this.maxContextLength = length;
        return this;
    }

    public Text2SqlSkill schemaMode(SchemaMode schemaMode) {
        this.schemaMode = schemaMode;
        return this;
    }

    public Text2SqlSkill readOnly(boolean readOnly) {
        this.readOnly = readOnly;
        return this;
    }

    @Override
    public String name() { return "sql_expert"; }

    @Override
    public String description() { return "数据库专家：精通多表关联、数据分析及各类数据库方言语法。"; }

    @Override
    public String getInstruction(Prompt prompt) {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        StringBuilder sb = new StringBuilder();

        sb.append("##### 1. 环境与上下文\n")
                .append("- **当前库类型**: ").append(dialectName).append("\n")
                .append("- **系统时间**: ").append(now).append("\n\n");

        if (schemaMode == SchemaMode.FULL) {
            sb.append("##### 2. 数据库结构说明 (Schema)\n").append(cachedSchemaInfo);
        } else {
            sb.append("##### 2. 数据库目录与关联图谱\n")
                    .append("编写查询前请先调用 `get_table_schema` 确认结构:\n\n");
            for (String tableName : tableNames) {
                String remarks = tableRemarksMap.getOrDefault(tableName, "");
                sb.append("- **").append(tableName).append("**").append(Utils.isEmpty(remarks) ? "" : ": " + remarks);
                List<String> rels = globalRelations.get(tableName);
                if (rels != null && !rels.isEmpty()) sb.append(" [关系线索: ").append(String.join(", ", rels)).append("]");
                sb.append("\n");
            }
        }

        sb.append("\n##### 3. SQL 执行准则 (强制执行)\n")
                .append("1. **只读性**: 仅允许 SELECT。严禁修改数据或结构。\n")
                .append("2. **字段探测认知**: 若返回的日期呈现为长数字，那是序列化格式。**严禁在 SQL 中对其做除以 1000 的运算**。应直接视其为日期字段并调用 ").append(dialectName).append(" 的日期函数。\n")
                .append("3. **保留字保护**: 为所有别名添加转义符。例如在 H2/Oracle/国产库中使用 `AS \"YEAR\"`，在 MySQL 中使用 `AS `year``。\n")
                .append("4. **方言兼容**: 使用 ").append(dialectName).append(" 推荐的语法（如分页、字符串拼接）。\n")
                .append("5. **自愈逻辑**: 若执行报错，请根据错误信息自检：1. 是否漏掉了引号；2. 函数名是否符合方言；3. 字段是否存在。不要重复提交错误 SQL。");

        return sb.toString();
    }

    @ToolMapping(name = "execute_sql", description = "执行单条 SELECT 语句。")
    public String executeSql(@Param("sql") String sql) {
        if (Assert.isBlank(sql)) return "Error: SQL is empty.";

        String cleanSql = sql.trim();
        if (cleanSql.endsWith(";")) cleanSql = cleanSql.substring(0, cleanSql.length() - 1);

        // 自动防御：为 AI 容易忽略的保留字别名加引号 (兼容主流库引号规则)
        cleanSql = autoFixAliases(cleanSql);

        String upperSql = cleanSql.toUpperCase();
        if (readOnly && !upperSql.startsWith("SELECT")) return "Error: Restricted to SELECT statements only.";

        // 分页逻辑适配
        if (!hasLimit(upperSql)) {
            cleanSql = applyUniversalPagination(cleanSql);
        }

        try {
            List<Map> rows = sqlUtils.sql(cleanSql).queryRowList(Map.class);
            if (rows == null || rows.isEmpty()) return "Query OK. No data found.";

            String json = ONode.serialize(rows);
            return json.length() > maxContextLength ? json.substring(0, maxContextLength) + "... [Truncated]" : json;
        } catch (SQLException e) {
            return "SQL Error: " + e.getMessage() + "\nHint: 检查别名引号、函数方言是否匹配 " + dialectName + "。";
        }
    }

    /**
     * 通用别名自动修复：识别常见保留字，确保它们被引号包裹。
     */
    private String autoFixAliases(String sql) {
        Matcher matcher = RESERVED_ALIAS_PATTERN.matcher(sql);
        StringBuffer sb = new StringBuffer();
        String quote = dialectName.toUpperCase().contains("MYSQL") ? "`" : "\"";
        while (matcher.find()) {
            matcher.appendReplacement(sb, " AS " + quote + matcher.group(1).toUpperCase() + quote + matcher.group(2));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private boolean hasLimit(String upperSql) {
        return upperSql.contains("LIMIT") || upperSql.contains("FETCH FIRST") ||
                upperSql.contains("TOP ") || upperSql.contains("ROWNUM");
    }

    /**
     * 通用分页适配逻辑
     */
    private String applyUniversalPagination(String sql) {
        String dn = dialectName.toUpperCase();

        // 1. MySQL, PostgreSQL, H2, SQLite, Kingbase(金仓), Highgo(瀚高)
        if (dn.contains("MYSQL") || dn.contains("POSTGRE") || dn.contains("H2") ||
                dn.contains("SQLITE") || dn.contains("KINGBASE") || dn.contains("HIGHGO")) {
            return sql + " LIMIT " + maxRows;
        }

        // 2. Oracle, Dameng(达梦)
        if (dn.contains("ORACLE") || dn.contains("DM DBMS") || dn.contains("DAMENG")) {
            // 简单判断是否已有 WHERE
            String connector = sql.toUpperCase().contains("WHERE") ? " AND " : " WHERE ";
            return sql + connector + "ROWNUM <= " + maxRows;
        }

        // 3. SQL Server (2012+)
        if (dn.contains("MICROSOFT") || dn.contains("SQL SERVER")) {
            if (!sql.toUpperCase().contains("ORDER BY")) {
                sql += " ORDER BY (SELECT NULL)";
            }
            return sql + " OFFSET 0 ROWS FETCH NEXT " + maxRows + " ROWS ONLY";
        }

        // 4. 标准 SQL 分页 (DB2 等)
        return sql + " FETCH FIRST " + maxRows + " ROWS ONLY";
    }

    // ... (extractSchemaInfo, getTableSchema, getTools 保持不变)

    @ToolMapping(name = "get_table_schema", description = "获取表的 DDL 信息")
    public String getTableSchema(@Param("tableName") String tableName) {
        if (!tableNames.contains(tableName)) return "Error: Table '" + tableName + "' not found.";
        return extractSchemaInfo(Collections.singletonList(tableName));
    }

    @Override
    public Collection<FunctionTool> getTools(Prompt prompt) {
        if (schemaMode == SchemaMode.FULL) {
            return tools.stream().filter(t -> "execute_sql".equals(t.name())).collect(Collectors.toList());
        }
        return super.getTools(prompt);
    }

    protected String extractSchemaInfo(List<String> tables) {
        StringBuilder sb = new StringBuilder();
        try (Connection conn = sqlUtils.getDataSource().getConnection()) {
            DatabaseMetaData dbMeta = conn.getMetaData();
            String catalog = conn.getCatalog();
            String schema = conn.getSchema();
            for (String tableName : tables) {
                sb.append("* **Table: ").append(tableName).append("**");
                String remarks = tableRemarksMap.get(tableName);
                if (Utils.isNotEmpty(remarks)) sb.append(" // ").append(remarks);
                sb.append("\n");
                Set<String> pks = new HashSet<>();
                try (ResultSet rs = dbMeta.getPrimaryKeys(catalog, schema, tableName)) {
                    while (rs.next()) pks.add(rs.getString("COLUMN_NAME"));
                }
                Map<String, String> fks = new HashMap<>();
                try (ResultSet rs = dbMeta.getImportedKeys(catalog, schema, tableName)) {
                    while (rs.next()) {
                        fks.put(rs.getString("FKCOLUMN_NAME"), rs.getString("PKTABLE_NAME") + "." + rs.getString("PKCOLUMN_NAME"));
                    }
                }
                try (ResultSet rs = dbMeta.getColumns(catalog, schema, tableName, null)) {
                    while (rs.next()) {
                        String col = rs.getString("COLUMN_NAME");
                        String type = rs.getString("TYPE_NAME");
                        String colRemarks = rs.getString("REMARKS");
                        sb.append("  - ").append(col).append(" (").append(type).append(")");
                        if (pks.contains(col)) sb.append(" [PK]");
                        if (fks.containsKey(col)) sb.append(" [FK -> ").append(fks.get(col)).append("]");
                        if (Utils.isNotEmpty(colRemarks)) sb.append(" // ").append(colRemarks);
                        sb.append("\n");
                    }
                }
            }
        } catch (Exception e) {
            return "Metadata Error: " + e.getMessage();
        }
        return sb.toString();
    }
}