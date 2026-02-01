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
 * 标准 SQL 方言实现
 * <p>作为默认缺省方言，遵循 ANSI SQL 标准</p>
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class GenericDialect implements SqlDialect {
    public String getName() { return "Generic SQL"; }
    public String quoteIdentifier(String name) { return "\"" + name + "\""; }
    public String applyPagination(String sql, int maxRows) { return sql + " FETCH FIRST " + maxRows + " ROWS ONLY"; }
    public String getCustomInstruction() { return "遵循标准 SQL 语法。"; }
    public String getErrorHint(SQLException e) { return "请检查方言兼容性。"; }
}