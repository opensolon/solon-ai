/*
 * Copyright 2017-2026 noear.org and authors
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
import org.noear.solon.ai.skills.text2sql.dialect.*;
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
 * 智能 Text-to-SQL 转换技能
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class Text2SqlSkill extends AbsSkill {
    protected final static Logger LOG = LoggerFactory.getLogger(Text2SqlSkill.class);

    // 正则用于捕获 SQL 中的 AS 别名，以便自动进行方言转义修复
    private static final Pattern AS_PATTERN = Pattern.compile("(?i)\\s+AS\\s+([a-zA-Z_][a-zA-Z0-9_]*)(\\s+|,|$)");

    protected final SqlUtils sqlUtils;
    protected final List<String> tableNames;
    protected final Map<String, String> tableRemarksMap = new LinkedHashMap<>();
    protected Map<String, List<String>> globalRelations;

    protected SqlDialect dialect;
    protected String cachedSchemaInfo;
    protected int maxRows = 50;
    protected int maxContextLength = 8000;
    protected SchemaMode schemaMode = SchemaMode.FULL;
    protected boolean readOnly = true;

    public Text2SqlSkill(DataSource dataSource, String... tables) {
        this(SqlUtils.of(dataSource), tables);
    }

    public Text2SqlSkill(SqlUtils sqlUtils, String... tables) {
        super();
        this.sqlUtils = sqlUtils;
        this.tableNames = Arrays.asList(tables);
    }

    private void init() {
        if (globalRelations != null) {
            return;
        }

        Utils.locker().lock();

        try {
            if (globalRelations != null) {
                return;
            }

            globalRelations = new HashMap<>();

            // 1. 初始化方言适配器与元数据
            initDialectAndMetadata();

            // 2. 根据表数量决定 Schema 模式
            this.schemaMode = tableNames.size() > 20 ? SchemaMode.DYNAMIC : SchemaMode.FULL;
            this.cachedSchemaInfo = (schemaMode == SchemaMode.FULL) ? extractSchemaInfo(tableNames) : null;
        } finally {
            Utils.locker().unlock();
        }
    }

    /**
     * 初始化：识别数据库方言并预加载表元数据
     */
    private void initDialectAndMetadata() {
        try (Connection conn = sqlUtils.getDataSource().getConnection()) {
            DatabaseMetaData dbMeta = conn.getMetaData();

            String productName = dbMeta.getDatabaseProductName();

            // 自动匹配方言
            if (dialect == null) {
                dialect = DialectFactory.create(productName);
            }

            dialect = dialect.adaptDialect(conn);

            String catalog = conn.getCatalog();
            String schema = dialect.findSchema(conn);

            for (String tableName : tableNames) {
                // 加载表备注
                try (ResultSet rs = dbMeta.getTables(catalog, schema, tableName, new String[]{"TABLE", "VIEW"})) {
                    if (rs.next()) {
                        String remarks = dialect.getRemark(tableName, rs);
                        if (Utils.isNotEmpty(remarks)) tableRemarksMap.put(tableName, remarks);
                    }
                }
                // 加载外键关联
                try (ResultSet rs = dbMeta.getImportedKeys(catalog, schema, tableName)) {
                    while (rs.next()) {
                        String rel = dialect.getRelation(tableName, rs);
                        if (Utils.isNotEmpty(rel)) {
                            globalRelations.computeIfAbsent(tableName, k -> new ArrayList<>()).add(rel);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.error("Text2SqlSkill meta load error", e);

            if (dialect == null) {
                dialect = new GenericDialect();
            }
        }
    }

    public Text2SqlSkill maxRows(int maxRows) { this.maxRows = maxRows; return this; }
    public Text2SqlSkill maxContextLength(int length) { this.maxContextLength = length; return this; }
    public Text2SqlSkill schemaMode(SchemaMode schemaMode) { this.schemaMode = schemaMode; return this; }
    public Text2SqlSkill readOnly(boolean readOnly) { this.readOnly = readOnly; return this; }

    public Text2SqlSkill dialect(SqlDialect dialect) {
        this.dialect = dialect;
        return this;
    }

    @Override
    public String name() { return "sql_expert"; }

    @Override
    public String description() { return "数据库专家：具备深厚的 " + dialect.getName() + " 方言知识，擅长多表分析。"; }

    @Override
    public void onAttach(Prompt prompt) {
        init();
    }

    @Override
    public String getInstruction(Prompt prompt) {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        StringBuilder sb = new StringBuilder();

        sb.append("##### 1. 环境上下文\n")
                .append("- **数据库方言**: ").append(dialect.getName()).append("\n")
                .append("- **系统时间**: ").append(now).append("\n\n");

        if (schemaMode == SchemaMode.FULL) {
            sb.append("##### 2. 数据库结构 (Schema)\n").append(cachedSchemaInfo);
        } else {
            sb.append("##### 2. 数据库目录与关系地图\n");
            for (String tableName : tableNames) {
                String remarks = tableRemarksMap.getOrDefault(tableName, "");
                sb.append("- **").append(tableName).append("**").append(Utils.isEmpty(remarks) ? "" : ": " + remarks);
                List<String> rels = globalRelations.get(tableName);
                if (rels != null && !rels.isEmpty()) sb.append(" [关系线索: ").append(String.join(", ", rels)).append("]");
                sb.append("\n");
            }
            sb.append("\n**注意**: 编写 SQL 前必须调用 `get_table_schema` 探测具体字段。\n");
        }

        sb.append("\n##### 3. 执行准则\n")
                .append("1. **权限**: 只读模式，严禁写操作。\n")
                .append("2. **方言特供指引**: ").append(dialect.getCustomInstruction()).append("\n")
                .append("3. **结果截断**: 默认必须分页，仅展示前 ").append(maxRows).append(" 条。\n")
                .append("4. **纠错逻辑**: 若执行报错，请对比方言要求检查引号、函数名或保留字。");

        return sb.toString();
    }

    @ToolMapping(name = "execute_sql", description = "执行单条 SELECT 查询语句。")
    public String executeSql(@Param("sql") String sql) {
        if (Assert.isBlank(sql)) return "Error: SQL is empty.";

        String cleanSql = sql.trim();
        if (cleanSql.endsWith(";")) cleanSql = cleanSql.substring(0, cleanSql.length() - 1);

        // 自动防御：为 AI 容易遗漏引号的别名加引号
        cleanSql = repairAliases(cleanSql);

        if (readOnly && !cleanSql.toUpperCase().startsWith("SELECT")) return "Error: Restricted to SELECT only.";

        // 分页补全
        if (!dialect.hasLimit(cleanSql.toUpperCase())) {
            cleanSql = dialect.applyPagination(cleanSql, maxRows);
        }

        try {
            List<Map> rows = sqlUtils.sql(cleanSql).queryRowList(Map.class);
            if (rows == null || rows.isEmpty()) return "Query OK. No data found.";

            String json = ONode.serialize(rows);
            return json.length() > maxContextLength ? json.substring(0, maxContextLength) + "... [Truncated]" : json;
        } catch (SQLException e) {
            return "SQL Error: " + e.getMessage() + "\nHint: " + dialect.getErrorHint(e);
        }
    }

    private String repairAliases(String sql) {
        Matcher matcher = AS_PATTERN.matcher(sql);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String alias = matcher.group(1);
            matcher.appendReplacement(sb, " AS " + dialect.quoteIdentifier(alias) + matcher.group(2));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    @ToolMapping(name = "get_table_schema", description = "获取特定表的详细列信息和 DDL")
    public String getTableSchema(@Param("tableName") String tableName) {
        if (!tableNames.contains(tableName)) return "Error: Table not found.";
        return extractSchemaInfo(Collections.singletonList(tableName));
    }

    protected String extractSchemaInfo(List<String> tables) {
        StringBuilder sb = new StringBuilder();
        try (Connection conn = sqlUtils.getDataSource().getConnection()) {
            DatabaseMetaData dbMeta = conn.getMetaData();
            String catalog = conn.getCatalog();
            String schema = dialect.findSchema(conn);

            for (String tableName : tables) {
                sb.append("* **Table: ").append(tableName).append("**");
                String remarks = tableRemarksMap.get(tableName);
                if (Utils.isNotEmpty(remarks)) sb.append(" // ").append(remarks);
                sb.append("\n");

                Set<String> pks = new HashSet<>();
                try (ResultSet rs = dbMeta.getPrimaryKeys(catalog, schema, tableName)) {
                    while (rs.next()) {
                        String col = dialect.getColumnName(tableName, rs);
                        pks.add(col);
                    }
                }

                try (ResultSet rs = dbMeta.getColumns(catalog, schema, tableName, null)) {
                    while (rs.next()) {
                        String colName = dialect.getColumnName(tableName, rs);
                        String colType = dialect.getColumnType(tableName, rs);
                        int colSize = dialect.getColumnSize(tableName, rs);
                        boolean colNullable = dialect.getColumnNullable(tableName, rs);

                        String typeDesc = colType;
                        if (colSize > 0 && colSize < 16777215) {
                            typeDesc += "(" + colSize + ")";
                        }
                        if(!colNullable){
                            typeDesc += " NOT NULL";
                        }

                        sb.append("  - ").append(colName).append(" (").append(typeDesc).append(")");
                        if (pks.contains(colName)) sb.append(" [PK]");
                        String colRemark = dialect.getRemark(tableName, rs);
                        if (Utils.isNotEmpty(colRemark)) sb.append(" // ").append(colRemark);
                        sb.append("\n");
                    }
                }
            }
        } catch (Exception e) {
            return "Metadata Error: " + e.getMessage();
        }
        return sb.toString();
    }

    @Override
    public Collection<FunctionTool> getTools(Prompt prompt) {
        if (schemaMode == SchemaMode.FULL) {
            return tools.stream().filter(t -> "execute_sql".equals(t.name())).collect(Collectors.toList());
        }
        return super.getTools(prompt);
    }

    // ==========================================
    // 方言抽象体系 (内部实现)
    // ==========================================

    static class DialectFactory {
        static SqlDialect create(String productName) {
            String name = productName.toUpperCase();
            if (name.contains("H2")) return new H2Dialect();
            if (name.contains("MYSQL")) return new MySqlDialect();
            if (name.contains("ORACLE") || name.contains("DM") || name.contains("DAMENG")) return new OracleDialect();
            if (name.contains("POSTGRE") || name.contains("KINGBASE") || name.contains("HIGHGO")) return new PostgreDialect();
            return new GenericDialect();
        }
    }
}