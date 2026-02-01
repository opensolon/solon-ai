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
import java.util.stream.Collectors;

/**
 * 智能 SQL 转换技能
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class Text2SqlSkill extends AbsSkill {
    protected final static Logger LOG = LoggerFactory.getLogger(Text2SqlSkill.class);

    protected final SqlUtils sqlUtils;
    protected final List<String> tableNames;
    protected final Map<String, String> tableRemarksMap = new LinkedHashMap<>();
    /**
     * 优化点 1: 全局关系拓扑缓存。key:表名, value:外键关联线索描述
     */
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

        // 构造时优先加载全局元数据（备注与关系图谱）
        loadGlobalMetadata();

        this.schemaMode = tableNames.size() > 20 ? SchemaMode.DYNAMIC : SchemaMode.FULL;
        this.cachedSchemaInfo = (schemaMode == SchemaMode.FULL) ? extractSchemaInfo(tableNames) : null;
    }

    /**
     * 提取全局元数据逻辑。解决 DYNAMIC 模式下 AI “视野孤岛”问题
     */
    private void loadGlobalMetadata() {
        try (Connection conn = sqlUtils.getDataSource().getConnection()) {
            DatabaseMetaData dbMeta = conn.getMetaData();
            // 自动适配 H2/MySQL/Oracle 的大小写敏感问题
            String catalog = conn.getCatalog();
            // 很多数据库 schema 传 null 效果更好
            for (String tableName : tableNames) {
                // 获取注释
                try (ResultSet rs = dbMeta.getTables(catalog, null, tableName, new String[]{"TABLE", "VIEW"})) {
                    if (rs.next()) {
                        String remarks = rs.getString("REMARKS");
                        if (Utils.isNotEmpty(remarks)) tableRemarksMap.put(tableName, remarks);
                    }
                }
                // 获取外键关系 (这里是 DYNAMIC 模式 AI 决策的关键)
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
            // 优化点 4: DYNAMIC 模式下输出更丰富的“语义目录”与“关联地图”
            sb.append("##### 2. 数据库目录与关联图谱 (Table List & Relations)\n")
                    .append("当前库表较多，请根据表名和备注评估所需表。**编写 SQL 前必须调用 `get_table_schema` 探测所需表的结构**:\n\n");

            for (String tableName : tableNames) {
                String remarks = tableRemarksMap.getOrDefault(tableName, "");
                sb.append("- **").append(tableName).append("**").append(Utils.isEmpty(remarks) ? "" : ": " + remarks);

                List<String> rels = globalRelations.get(tableName);
                if (rels != null && !rels.isEmpty()) {
                    sb.append(" [关系线索: ").append(String.join(", ", rels)).append("]");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        sb.append("##### 3. SQL 执行准则 (严格遵守)\n");

        if (readOnly) {
            sb.append("1. **权限边界**: 你是一个**只读**分析专家。严禁执行写指令。遇到写请求必须礼貌拒绝。\n");
        } else {
            sb.append("1. **操作风险**: 你拥有写权限。执行大批量修改前应建议用户备份。\n");
        }

        sb.append("2. **方言一致性**: 必须使用 ").append(dialectName).append(" 的原生语法。\n")
                .append("3. **先探测后重度计算**: 对字段存储内容或格式有疑虑时，优先执行单行采样查询（使用当前方言对应的分页语法，如 LIMIT 1, TOP 1 或 ROWNUM=1）探测真实数据，严禁盲目尝试复杂转换。\n")
                .append("4. **关键字转义**: 识别保留字，作为别名或表名使用时必须加转义符（如双引号或反引号）。\n")
                .append("5. **自愈逻辑**: 遇到报错时，根据错误信息分析类型不匹配或方言不支持原因，调整逻辑后重试。\n")
                .append("6. **结果收敛**: 若确认无数据或方言不支持，请诚实告知用户。\n")
                .append("7. **强制限定词**: 多表关联查询时，所有字段必须带有表别名作为前缀，严禁使用裸字段名。\n")
                .append("8. **连接路径确认**: 关联三张以上表时，优先确认主外键连接链路（参考第 2 节关联图谱），避免产生笛卡尔积。\n")
                .append("9. **结果截断意识**: 除非明确要求全量数据，编写 SQL 时必须自行追加分页代码，默认展示 ").append(maxRows).append(" 条以内数据。");

        return sb.toString();
    }

    @Override
    public Collection<FunctionTool> getTools(Prompt prompt) {
        if (schemaMode == SchemaMode.FULL) {
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
        return extractSchemaInfo(Collections.singletonList(tableName));
    }

    @ToolMapping(name = "execute_sql", description = "执行单条只读 SELECT 语句并获取结果。")
    public String executeSql(@Param("sql") String sql) {
        if (Assert.isBlank(sql)) return "Error: SQL is empty.";

        String cleanSql = sql.trim();
        if (cleanSql.endsWith(";")) cleanSql = cleanSql.substring(0, cleanSql.length() - 1);

        String upperSql = cleanSql.toUpperCase();
        if (readOnly && !upperSql.startsWith("SELECT")) return "Error: Restricted to SELECT statements only.";
        if (upperSql.contains(";") && !cleanSql.endsWith(";")) return "Error: Multiple SQL statements are not allowed.";

        // 分页补全逻辑 (子查询包装法，确保方言兼容性)
        boolean hasLimit = upperSql.contains("LIMIT") || upperSql.contains("FETCH FIRST") ||
                upperSql.contains("TOP ") || upperSql.contains("ROWNUM");

        if (!hasLimit) {
            String dn = dialectName.toUpperCase();
            if (dn.contains("MYSQL") || dn.contains("POSTGRE") || dn.contains("H2") || dn.contains("SQLITE") || dn.contains("KINGBASE")) {
                cleanSql = "SELECT * FROM (" + cleanSql + ") _t LIMIT " + maxRows;
            } else if (dn.contains("ORACLE") || dn.contains("DAMENG")) {
                cleanSql = "SELECT * FROM (" + cleanSql + ") _t WHERE ROWNUM <= " + maxRows;
            } else if (dn.contains("SQL SERVER")) {
                cleanSql = "SELECT * FROM (" + cleanSql + ") _t ORDER BY (SELECT NULL) OFFSET 0 ROWS FETCH NEXT " + maxRows + " ROWS ONLY";
            } else {
                cleanSql = "SELECT * FROM (" + cleanSql + ") _t FETCH FIRST " + maxRows + " ROWS ONLY";
            }
        }

        try {
            List<Map> rows = sqlUtils.sql(cleanSql).queryRowList(Map.class);
            if (rows == null || rows.isEmpty()) return "Query OK. No data found.";

            // 结构化截断保护
            int sampleSize = Math.min(10, maxRows);
            if (rows.size() > sampleSize + 5) {
                List<Map> subRows = rows.stream().limit(sampleSize).collect(Collectors.toList());
                return ONode.serialize(subRows) + "\n\n[Note: 结果集较大已截断。显示前" + sampleSize + "条，总数: " + rows.size() + "]";
            }

            String fullJson = ONode.serialize(rows);
            return fullJson.length() > maxContextLength ? fullJson.substring(0, maxContextLength) + "... [Truncated]" : fullJson;
        } catch (SQLException e) {
            return "SQL Error: " + e.getMessage() + "\nHint: 请检查别名、JOIN 条件或 " + dialectName + " 的特有语法。多表关联请务必加别名前缀。";
        }
    }

    protected String extractSchemaInfo(List<String> tables) {
        StringBuilder sb = new StringBuilder();
        try (Connection conn = sqlUtils.getDataSource().getConnection()) {
            DatabaseMetaData dbMeta = conn.getMetaData();
            String catalog = conn.getCatalog();
            String schema = conn.getSchema();

            for (String tableName : tables) {
                // 1. 表名与备注
                sb.append("* **Table: ").append(tableName).append("**");
                String remarks = tableRemarksMap.get(tableName);
                if (Utils.isNotEmpty(remarks)) sb.append(" // ").append(remarks);
                sb.append("\n");

                // 2. 主键
                Set<String> pks = new HashSet<>();
                try (ResultSet rs = dbMeta.getPrimaryKeys(catalog, schema, tableName)) {
                    while (rs.next()) pks.add(rs.getString("COLUMN_NAME"));
                }
                // 3. 外键 (仅提取当前表的导入键)
                Map<String, String> fks = new HashMap<>();
                try (ResultSet rs = dbMeta.getImportedKeys(catalog, schema, tableName)) {
                    while (rs.next()) {
                        fks.put(rs.getString("FKCOLUMN_NAME"),
                                rs.getString("PKTABLE_NAME") + "." + rs.getString("PKCOLUMN_NAME"));
                    }
                }

                // 4. 列详情
                try (ResultSet rs = dbMeta.getColumns(catalog, schema, tableName, null)) {
                    while (rs.next()) {
                        String col = rs.getString("COLUMN_NAME");
                        String type = rs.getString("TYPE_NAME");
                        String colRemarks = Utils.valueOr(rs.getString("REMARKS"), "");

                        sb.append("  - ").append(col).append(" (").append(type).append(")");
                        if (pks.contains(col)) sb.append(" [PK]");
                        if (fks.containsKey(col)) sb.append(" [FK -> ").append(fks.get(col)).append("]");
                        if (Assert.isNotEmpty(colRemarks)) sb.append(" // ").append(colRemarks);
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