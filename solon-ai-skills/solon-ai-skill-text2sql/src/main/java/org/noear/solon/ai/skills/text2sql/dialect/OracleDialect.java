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
package org.noear.solon.ai.skills.text2sql.dialect;

import org.noear.solon.ai.skills.text2sql.SqlDialect;
import org.noear.solon.lang.Preview;

import java.sql.SQLException;

/**
 * Oracle 系列方言实现
 * <p>适配 Oracle、达梦(Dameng) 等 ROWNUM 分页及强制双引号标识符习惯</p>
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class OracleDialect implements SqlDialect {
    public String getName() { return "Oracle/Dameng"; }
    public String quoteIdentifier(String name) { return "\"" + name.toUpperCase() + "\""; }
    public String applyPagination(String sql, int maxRows) {
        return "SELECT * FROM (" + sql + ") WHERE ROWNUM <= " + maxRows;
    }
    public String getCustomInstruction() { return "Oracle/达梦对别名建议使用双引号。必须通过 ROWNUM 限制行数。"; }
    public String getErrorHint(SQLException e) { return "注意 Oracle 中 GROUP BY 必须包含 SELECT 中所有非聚合列。"; }
    @Override
    public boolean hasLimit(String upperSql) {
        return upperSql.contains("ROWNUM") || upperSql.contains("FETCH FIRST");
    }
}