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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 智能 SQL 转换技能
 *
 * @author noear
 * @since 3.9.1
 */
public class Text2SqlSkill extends AbsSkill {
    protected final static Logger LOG = LoggerFactory.getLogger(Text2SqlSkill.class);

    protected final SqlUtils sqlUtils;
    protected final List<String> tableNames;
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
        this.cachedSchemaInfo = extractSchemaInfo(tableNames);
        this.schemaMode = tableNames.size() > 20 ? SchemaMode.DYNAMIC : SchemaMode.FULL;
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
    public String name() {
        return "sql_expert";
    }

    @Override
    public String description() {
        return "数据库专家：深度理解多表结构、关联关系及各类国产方言。";
    }

    @Override
    public boolean isSupported(Prompt prompt) {
        return true;
    }

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
            sb.append("##### 2. 数据库目录 (Table List)\n")
                    .append("当前库表较多，初始仅列出目录。**编写 SQL 前必须调用 `get_table_schema` 探测所需表的结构**:\n")
                    .append(String.join(", ", tableNames));
        }

        sb.append("##### 3. SQL 执行准则 (严格遵守)\n");

        if (readOnly) {
            sb.append("1. **权限边界**: 你是一个**只读**分析专家。严禁执行 `DELETE`, `UPDATE`, `INSERT` 等写指令。遇到此类请求必须礼貌拒绝。\n");
        } else {
            sb.append("1. **操作风险**: 你拥有**数据修改权限**。在执行 `UPDATE` 或 `DELETE` 前，必须确保 WHERE 条件极其精确。执行大批量修改前应建议用户备份。\n");
        }

        sb.append("2. **方言一致性**: 必须使用 ").append(dialectName).append(" 的原生语法（函数、分页、转义符）。\n")
                .append("3. **先探测后重度计算**: 对字段格式有疑虑时，优先执行 `SELECT col FROM table LIMIT 1` 探测真实数据，严禁盲目尝试复杂转换。\n")
                .append("4. **关键字转义**: 识别保留字（如 YEAR, ORDER, USER），作为别名或表名使用时必须加转义符（如双引号或反引号）。\n")
                .append("5. **自愈逻辑**: 遇到报错时，根据错误信息分析类型不匹配或方言不支持原因，调整逻辑后重试一次。\n")
                .append("6. **结果收敛**: 若确认无数据或方言不支持，请诚实告知用户，严禁编造数据。");

        return sb.toString();
    }

    @Override
    public Collection<FunctionTool> getTools(Prompt prompt) {
        if (schemaMode == SchemaMode.FULL) {
            //全量，不需要 get_table_schema
            return tools.stream()
                    .filter(tool -> "execute_sql".equals(tool.name()))
                    .collect(Collectors.toList());
        }

        return super.getTools(prompt);
    }

    @ToolMapping(name = "get_table_schema", description = "获取特定表的详细 DDL 和列信息")
    public String getTableSchema(@Param("tableName") String tableName) {
        if (!tableNames.contains(tableName)) {
            return "Error: Table '" + tableName + "' not found.";
        }
        // 动态提取单表的结构
        return extractSchemaInfo(Collections.singletonList(tableName));
    }

    @ToolMapping(name = "execute_sql", description = "执行单条只读 SELECT 语句并获取 JSON 结果。")
    public String executeSql(@Param("sql") String sql) {
        if (Assert.isBlank(sql)) {
            return "Error: SQL is empty.";
        }

        if (LOG.isTraceEnabled()) {
            LOG.trace("Executing SQL: {}", sql);
        }

        String cleanSql = sql.trim();
        // 自动移除末尾分号，增强容错
        if (cleanSql.endsWith(";")) {
            cleanSql = cleanSql.substring(0, cleanSql.length() - 1);
        }

        String upperSql = cleanSql.toUpperCase();
        // 根据配置决定是否拦截
        if (readOnly && !upperSql.startsWith("SELECT")) {
            return "Error: This tool is restricted to SELECT statements only.";
        }

        // 严禁多条执行（防止注入或脚本）
        if (upperSql.contains(";") && !cleanSql.endsWith(";")) {
            return "Error: Multiple SQL statements are not allowed.";
        }

        // 分页补全逻辑 (适配常用主流及国产数据库)
        if (!upperSql.contains("LIMIT") && !upperSql.contains("FETCH FIRST") && !upperSql.contains("TOP ")) {
            String dn = dialectName.toUpperCase();
            if (dn.contains("MYSQL") || dn.contains("POSTGRE") || dn.contains("KINGBASE") || dn.contains("H2") || dn.contains("SQLITE")) {
                cleanSql += " LIMIT " + maxRows;
            } else if (dn.contains("DAMENG") || dn.contains("ORACLE") || dn.contains("DB2")) {
                cleanSql += " FETCH FIRST " + maxRows + " ROWS ONLY";
            } else if (dn.contains("SQL SERVER")) {
                if (!upperSql.startsWith("SELECT TOP")) {
                    cleanSql = cleanSql.replaceFirst("(?i)SELECT", "SELECT TOP " + maxRows);
                }
            }
        }

        try {
            List<Map> rows = sqlUtils.sql(cleanSql).queryRowList(Map.class);
            if (rows == null || rows.isEmpty()) return "Query OK. No data.";
            String json = ONode.serialize(rows);
            // 结果截断保护，防止 context 溢出
            return json.length() > maxContextLength ? json.substring(0, maxContextLength) + "... [Truncated]" : json;
        } catch (SQLException e) {
            return "SQL Error: " + e.getMessage();
        }
    }

    protected String extractSchemaInfo(List<String> tables) {
        StringBuilder sb = new StringBuilder();

        try (Connection conn = sqlUtils.getDataSource().getConnection()) {
            DatabaseMetaData dbMeta = conn.getMetaData();
            this.dialectName = dbMeta.getDatabaseProductName();
            String catalog = conn.getCatalog();
            String schema = conn.getSchema();

            for (String tableName : tables) {
                // 1. 表名作为加粗列表项，移除冗余的 "Table:" 标签和分割线
                sb.append("* **Table: ").append(tableName).append("**");
                try (ResultSet rs = dbMeta.getTables(catalog, schema, tableName, new String[]{"TABLE", "VIEW"})) {
                    if (rs.next()) {
                        String remarks = rs.getString("REMARKS");
                        if (Assert.isNotEmpty(remarks)) sb.append(" // ").append(remarks);
                    }
                }
                sb.append("\n");

                // 2. 提取主键与外键关联 (FK 明确指向表.列)
                Set<String> pks = new HashSet<>();
                try (ResultSet rs = dbMeta.getPrimaryKeys(catalog, schema, tableName)) {
                    while (rs.next()) pks.add(rs.getString("COLUMN_NAME"));
                }
                Map<String, String> fks = new HashMap<>();
                try (ResultSet rs = dbMeta.getImportedKeys(catalog, schema, tableName)) {
                    while (rs.next()) {
                        fks.put(rs.getString("FKCOLUMN_NAME"),
                                rs.getString("PKTABLE_NAME") + "." + rs.getString("PKCOLUMN_NAME"));
                    }
                }

                // 3. 生成列清单：使用双空格缩进，增强隶属感
                try (ResultSet rs = dbMeta.getColumns(catalog, schema, tableName, null)) {
                    while (rs.next()) {
                        String col = rs.getString("COLUMN_NAME");
                        String type = rs.getString("TYPE_NAME");
                        String remarks = Utils.valueOr(rs.getString("REMARKS"), "");

                        sb.append("  - ").append(col).append(" (").append(type).append(")");
                        if (pks.contains(col)) sb.append(" [PK]");
                        if (fks.containsKey(col)) sb.append(" [FK -> ").append(fks.get(col)).append("]");
                        if (Assert.isNotEmpty(remarks)) sb.append(" // ").append(remarks);
                        sb.append("\n");
                    }
                }
            }
        } catch (Exception e) {
            LOG.error("Extract schema error", e);
            return "Metadata Error: " + e.getMessage();
        }
        return sb.toString();
    }
}