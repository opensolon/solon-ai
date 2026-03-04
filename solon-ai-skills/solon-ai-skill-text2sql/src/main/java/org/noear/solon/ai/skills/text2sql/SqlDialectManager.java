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

import org.noear.solon.ai.skills.text2sql.dialect.*;
import org.noear.solon.lang.Preview;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SQL 方言管理器
 * <p>负责统一管理、注册和检索不同数据库的方言适配器 (SqlDialect)</p>
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class SqlDialectManager {
    /**
     * 已注册的方言映射表（使用 LinkedHashMap 保证匹配顺序）
     */
    private static Map<String, SqlDialect> dialectMap = new LinkedHashMap<>();
    /**
     * 缺省方言实例（当无法识别数据库产品时使用）
     */
    private static GenericDialect _DEFAULT = new GenericDialect();

    static {
        // 初始化预设方言
        register(new GenericDialect());
        register(new H2Dialect());
        register(new MySqlDialect());
        register(new OracleDialect());
        register(new PostgreDialect());
        register(new SqliteDialect());
    }


    /**
     * 注册新方言
     * <p>允许用户扩展自定义方言或覆盖现有方言</p>
     *
     * @param dialect 方言实现类
     */
    public static void register(SqlDialect dialect) {
        if (dialect != null) {
            dialectMap.put(dialect.getName(), dialect);
        }
    }

    /**
     * 根据数据库产品名称筛选匹配的方言
     *
     * @param productName 数据库产品名 (来自 Connection.getMetaData().getDatabaseProductName())
     * @return 匹配的方言实例，若未匹配则返回 GenericDialect
     */
    public static SqlDialect select(String productName) {
        if (productName == null) {
            return _DEFAULT;
        }

        String product = productName.toUpperCase();

        for (SqlDialect dialect : dialectMap.values()) {
            if (dialect.matched(product)) {
                return dialect;
            }
        }

        return _DEFAULT;
    }
}