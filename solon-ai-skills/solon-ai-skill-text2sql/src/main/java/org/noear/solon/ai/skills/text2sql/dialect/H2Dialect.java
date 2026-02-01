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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * H2 数据库方言实现
 * <p>针对 H2 的保留字强制大写转义及日期函数的特殊表现进行优化，并支持 MySQL 模式检测</p>
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class H2Dialect implements SqlDialect {
    public String getName() {
        return "H2 Database";
    }

    public String quoteIdentifier(String name) {
        return "\"" + name.toUpperCase() + "\"";
    }

    public String applyPagination(String sql, int maxRows) {
        return sql + " LIMIT " + maxRows;
    }

    public String getCustomInstruction() {
        return "H2 严格区分保留字。别名必须使用双引号(如 AS \"YEAR\")。长数字日期是序列化表现，严禁除以 1000，直接使用 YEAR() 等日期函数。";
    }

    public String getErrorHint(SQLException e) {
        return "H2 报错通常与未转义的保留字别名或非法的日期数学运算有关。";
    }

    public SqlDialect adaptDialect(Connection conn) {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT VALUE FROM INFORMATION_SCHEMA.SETTINGS WHERE NAME = 'MODE'")) {
            if (rs.next() && "MySQL".equalsIgnoreCase(rs.getString(1))) {
                // 如果是 MySQL 模式，可以动态替换 dialect 或修改其内部标志
                return new MySqlDialect();
            }
        } catch (Exception ignored) {
        }

        return this;
    }
}