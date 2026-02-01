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

import java.sql.Connection;
import java.sql.SQLException;

/**
 * SQLite 数据库方言实现
 * <p>针对 SQLite 缺失标准日期函数及弱类型特性进行优化</p>
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class SqliteDialect implements SqlDialect {
    @Override
    public String getName() {
        return "SQLite";
    }

    @Override
    public String quoteIdentifier(String name) {
        // SQLite 推荐使用双引号，也可以使用 []
        return "\"" + name + "\"";
    }

    @Override
    public String applyPagination(String sql, int maxRows) {
        return sql + " LIMIT " + maxRows;
    }

    @Override
    public String getCustomInstruction() {
        return "SQLite 特别指引：\n" +
                "1. 日期处理：不支持 YEAR()/MONTH() 函数，请使用 strftime('%Y', column) 或 date()。\n" +
                "2. 布尔值：使用 0 和 1 表示。\n" +
                "3. 别名：建议对所有字段别名加双引号，防止与内置函数名冲突。";
    }

    @Override
    public String getErrorHint(SQLException e) {
        return "SQLite 报错通常是因为使用了不支持的 SQL 函数（如日期函数）或语法。";
    }

    @Override
    public String findSchema(Connection conn) throws SQLException {
        // SQLite 通常没有 schema 概念，或者返回 "main"
        return null;
    }
}