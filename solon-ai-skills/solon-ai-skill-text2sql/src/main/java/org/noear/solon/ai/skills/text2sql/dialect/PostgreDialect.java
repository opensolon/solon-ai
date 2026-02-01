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
 * PostgreSQL 系列方言实现
 * <p>适配 PG、人大金仓(Kingbase)、瀚高(Highgo) 等区分大小写的数据库系统</p>
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class PostgreDialect implements SqlDialect {
    public String getName() { return "PostgreSQL/Kingbase/Highgo"; }
    public String quoteIdentifier(String name) { return "\"" + name + "\""; }
    public String applyPagination(String sql, int maxRows) { return sql + " LIMIT " + maxRows; }
    public String getCustomInstruction() { return "PG/金仓/瀚高库区分大小写，别名请加双引号。支持 LIMIT 分页。"; }
    public String getErrorHint(SQLException e) { return "请确认字段名大小写是否匹配。"; }
}