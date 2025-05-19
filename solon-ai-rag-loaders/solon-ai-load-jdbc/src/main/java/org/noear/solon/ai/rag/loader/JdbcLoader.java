/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.rag.loader;

import java.io.*;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

import org.noear.snack.core.utils.StringUtil;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.lang.Preview;

import javax.sql.DataSource;

/**
 * JDBC表结构 加载器
 * <p>
 * 读取表结构DDL，并转换为文本
 * </p>
 *
 * @author 刘颜
 * @since 3.3
 */
@Preview("3.3")
public class JdbcLoader extends AbstractOptionsDocumentLoader<JdbcLoader.Options, JdbcLoader> {
    private DataSource dataSource;
    private JdbcLoadConfig jdbcLoadConfig;

    public JdbcLoader(DataSource dataSource) {
        this.dataSource = dataSource;
        //默认支持mysql
        this.jdbcLoadConfig = new JdbcLoadConfig.Builder()
                .shcemaTableQuerySql(" select TABLE_SCHEMA, TABLE_NAME from information_schema.TABLES where TABLE_SCHEMA not in ('mysql', 'sys', 'information_schema') ")
                .shcemaTableQueryAndSchemaSqlTemplate(" and TABLE_SCHEMA = '%s' ")
                .shcemaTableQueryAndTableSqlTemplate(" and TABLE_SCHEMA = '%s' and TABLE_NAME = '%s' ")
                .shcemaTableQueryResultSchemeNameColumn("TABLE_SCHEMA")
                .shcemaTableQueryResultTableNameColumn("TABLE_NAME")
                .ddlQuerySqlTemplate(" SHOW CREATE TABLE `%s`.`%s` ")
                .ddlQueryResultDdlNameColumn("Create Table").build();
        options = new Options();
    }

    public JdbcLoader(DataSource dataSource, JdbcLoadConfig jdbcLoadConfig) {
        this.dataSource = dataSource;
        this.jdbcLoadConfig = jdbcLoadConfig;
        options = new Options();
    }

    @Override
    public List<Document> load() throws IOException {
        List<Document> documents = new ArrayList<>();

        String targetSchema = options.targetSchema;
        String targetTable = options.targetTable;

        try (Connection connection = dataSource.getConnection()) {
            String sql = this.jdbcLoadConfig.getShcemaTableQuerySql();
            if (StringUtil.isEmpty(targetSchema)) {
                //全库全表
            } else if (StringUtil.isEmpty(targetTable)) {
                //单库全表
                sql = sql.concat(String.format(this.jdbcLoadConfig.getShcemaTableQueryAndSchemaSqlTemplate(), targetSchema));
            } else {
                //单库单表
                sql = sql.concat(String.format(this.jdbcLoadConfig.getShcemaTableQueryAndTableSqlTemplate(), targetSchema, targetTable));
            }
            try (PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
                ResultSet rowSet = preparedStatement.executeQuery();
                while (rowSet.next()) {
                    //每张表单独转化
                    String tableSchema = rowSet.getString(this.jdbcLoadConfig.getShcemaTableQueryResultSchemeNameColumn());
                    String tableName = rowSet.getString(this.jdbcLoadConfig.getShcemaTableQueryResultTableNameColumn());
                    String showddl = String.format(this.jdbcLoadConfig.getDdlQuerySqlTemplate(), tableSchema, tableName);
                    try (PreparedStatement showddlPrepareStatement = connection.prepareStatement(showddl)) {
                        ResultSet showddlResultSet = showddlPrepareStatement.executeQuery();
                        if (showddlResultSet.next()) {
                            String tableddl = showddlResultSet.getString(this.jdbcLoadConfig.getDdlQueryResultDdlNameColumn());
                            String fullDDL = "CREATE TABLE `" + tableSchema + "`.`" + tableName + "` " + tableddl.substring(tableddl.indexOf("("));
                            Document doc = new Document(fullDDL).metadata(this.additionalMetadata);
                            documents.add(doc);
                        }
                    }
                }
            }
        } catch (RuntimeException | SQLException e) {
            throw new RuntimeException(e);
        }

        return documents;
    }

    /**
     * 选项
     */
    public static class Options {
        private String targetSchema;
        private String targetTable;

        /**
         * targetSchema为null，不管targetTable是否为null，则是全库全表加载
         * targetSchema不为null，targetTable为null则是单库全表加载
         * targetSchema不为null，targetTable不为null则是单库单表加载
         * 否则默认是全库全表加载
         */
        public Options loadOptions(String targetSchema, String targetTable) {
            this.targetSchema = targetSchema;
            this.targetTable = targetTable;
            return this;
        }
    }
}