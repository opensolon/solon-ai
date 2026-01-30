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
        return "数据库专家：深度理解多表结构、关联关系及各类国产方言。";
    }

    @Override
    public boolean isSupported(Prompt prompt) {
        return true;
    }

    @Override
    public String getInstruction(Prompt prompt) {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        return "##### 1. 环境与上下文\n" +
                "- **当前库类型**: " + dialectName + "\n" +
                "- **系统时间**: " + now + "\n\n" +
                "##### 2. 数据库结构说明 (Schema)\n" + cachedSchemaInfo + "\n" +
                "##### 3. SQL 执行准则\n" +
                "1. **方言一致性**: 必须使用 " + dialectName + " 的原生语法（函数、分页、转义符）。\n" +
                "2. **先探测后重度计算**: 若对字段格式或方言函数有疑虑，优先执行 `SELECT col FROM table LIMIT 1` 探测真实数据，严禁盲目尝试复杂转换。\n" +
                "3. **关键字转义**: 识别 " + dialectName + " 的保留字（如 YEAR, ORDER, USER），若作为别名或表名使用，必须加方言对应的转义符（如双引号或反引号）。\n" +
                "4. **自愈逻辑**: 遇到报错时，根据错误信息分析是否为方言函数不支持或类型不匹配，调整逻辑后重试。\n" +
                "5. **结果收敛**: 若多次尝试后确认无数据或方言不支持，请诚实告知用户，严禁编造数据。";
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
        // 自动移除末尾分号，增强容错
        if (cleanSql.endsWith(";")) {
            cleanSql = cleanSql.substring(0, cleanSql.length() - 1);
        }

        String upperSql = cleanSql.toUpperCase();
        if (!upperSql.startsWith("SELECT")) return "Error: Only SELECT is permitted.";
        if (upperSql.contains(";") || upperSql.contains("--") || upperSql.contains("/*")) {
            return "Error: Restricted characters detected.";
        }

        // 分页补全逻辑 (适配常用主流及国产数据库)
        if (!upperSql.contains("LIMIT") && !upperSql.contains("FETCH FIRST") && !upperSql.contains("TOP ")) {
            String dn = dialectName.toUpperCase();
            if (dn.contains("MYSQL") || dn.contains("POSTGRE") || dn.contains("KINGBASE") || dn.contains("H2") || dn.contains("SQLITE")) {
                cleanSql += " LIMIT " + maxRows;
            } else if (dn.contains("DAMENG") || dn.contains("ORACLE") || dn.contains("DB2")) {
                cleanSql += " FETCH FIRST " + maxRows + " ROWS ONLY";
            } else if (dn.contains("SQL SERVER")) {
                if(!upperSql.startsWith("SELECT TOP")) {
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
                try (ResultSet rs = dbMeta.getTables(catalog, schema, tableName, new String[]{"TABLE"})) {
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