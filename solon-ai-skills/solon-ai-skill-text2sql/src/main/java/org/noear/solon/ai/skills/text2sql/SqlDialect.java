package org.noear.solon.ai.skills.text2sql;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;

public interface SqlDialect {
    String getName();

    String quoteIdentifier(String name);

    String applyPagination(String sql, int maxRows);

    String getCustomInstruction();

    String getErrorHint(SQLException e);

    default String findSchema(Connection conn) throws SQLException {
        return conn.getSchema();
    }

    default boolean hasLimit(String upperSql) {
        return upperSql.contains("LIMIT") || upperSql.contains("FETCH") ||
                upperSql.contains("TOP ") || upperSql.contains("ROWNUM");
    }

    default String getRemark(String tableName, ResultSet rs) throws SQLException {
        return rs.getString("REMARKS");
    }

    default String getColumnName(String tableName, ResultSet rs) throws SQLException {
        return rs.getString("COLUMN_NAME");
    }

    default String getColumnType(String tableName, ResultSet rs) throws SQLException{
        return rs.getString("TYPE_NAME");
    }

    default int getColumnSize(String tableName, ResultSet rs) throws SQLException{
        return rs.getInt("COLUMN_SIZE");
    }

    default boolean getColumnNullable(String tableName, ResultSet rs) throws SQLException{
        return "NO".equals(rs.getString("IS_NULLABLE")) == false;
    }

    default String getRelation(String tableName, ResultSet rs) throws SQLException {
        String pkTable = rs.getString("PKTABLE_NAME");
        String pkCol = rs.getString("PKCOLUMN_NAME");
        String fkCol = rs.getString("FKCOLUMN_NAME");
        String rel = tableName + "." + fkCol + " -> " + pkTable + "." + pkCol;
        return rel;
    }

    default SqlDialect adaptDialect(Connection conn){
        return this;
    }
}