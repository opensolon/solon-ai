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

import org.noear.solon.lang.Preview;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * SQL 方言适配接口
 * <p>用于处理不同数据库在标识符转义、分页语法、元数据读取以及给 AI 的特定指令差异</p>
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public interface SqlDialect {
    /**
     * 获取方言名称
     */
    String getName();

    /**
     * 包装标识符（如加反引号或双引号），防止关键字冲突
     */
    String quoteIdentifier(String name);

    /**
     * 为 SQL 增加分页限制
     */
    String applyPagination(String sql, int maxRows);

    /**
     * 获取给 AI 的方言特定提示（例如：日期函数的使用习惯）
     */
    String getCustomInstruction();

    /**
     * 根据异常信息提供纠错建议
     */
    String getErrorHint(SQLException e);

    /**
     * 获取当前连接的 Schema
     */
    default String findSchema(Connection conn) throws SQLException {
        return conn.getSchema();
    }

    /**
     * 判断 SQL 是否已包含限制语句
     */
    default boolean hasLimit(String upperSql) {
        return upperSql.contains("LIMIT") || upperSql.contains("FETCH") ||
                upperSql.contains("TOP ") || upperSql.contains("ROWNUM");
    }

    /**
     * 读取表或列的注释备注
     */
    default String getRemark(String tableName, ResultSet rs) throws SQLException {
        return rs.getString("REMARKS");
    }

    /**
     * 读取列名
     */
    default String getColumnName(String tableName, ResultSet rs) throws SQLException {
        return rs.getString("COLUMN_NAME");
    }

    /**
     * 读取列类型名称
     */
    default String getColumnType(String tableName, ResultSet rs) throws SQLException {
        return rs.getString("TYPE_NAME");
    }

    /**
     * 读取列长度
     */
    default int getColumnSize(String tableName, ResultSet rs) throws SQLException {
        return rs.getInt("COLUMN_SIZE");
    }

    /**
     * 判断列是否允许为空
     */
    default boolean getColumnNullable(String tableName, ResultSet rs) throws SQLException {
        return "NO".equals(rs.getString("IS_NULLABLE")) == false;
    }

    /**
     * 获取外键关系描述
     */
    default String getRelation(String tableName, ResultSet rs) throws SQLException {
        String pkTable = rs.getString("PKTABLE_NAME");
        String pkCol = rs.getString("PKCOLUMN_NAME");
        String fkCol = rs.getString("FKCOLUMN_NAME");
        String rel = tableName + "." + fkCol + " -> " + pkTable + "." + pkCol;
        return rel;
    }

    /**
     * 根据连接信息动态适配方言（如 H2 的兼容模式判断）
     */
    default SqlDialect adaptDialect(Connection conn) {
        return this;
    }
}