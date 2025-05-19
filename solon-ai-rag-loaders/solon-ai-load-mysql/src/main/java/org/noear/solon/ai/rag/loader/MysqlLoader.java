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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.noear.snack.core.utils.StringUtil;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.lang.Preview;

/**
 * Mysql 加载器
 * <p>
 * 读取Mysql的表结构DDL，并转换为文本
 * </p>
 *
 * @author 刘颜
 * @since 3.3
 */
@Preview("3.3")
public class MysqlLoader extends AbstractOptionsDocumentLoader<MysqlLoader.Options, MysqlLoader> {
    private String url;
    private String username;
    private String password;

    public MysqlLoader(String url, String username, String password) {
        this.url = url;
        this.username = username;
        this.password = password;
        options = new Options();
    }

    @Override
    public List<Document> load() throws IOException {
        List<Document> documents = new ArrayList<>();

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(this.url);
        config.setUsername(this.username);
        config.setPassword(this.password);

        String targetSchema = options.targetSchema;
        String targetTable = options.targetTable;

        try (HikariDataSource hikariDataSource = new HikariDataSource(config)) {
            try (Connection connection = hikariDataSource.getConnection()) {
                String sql = "select TABLE_SCHEMA, TABLE_NAME from information_schema.TABLES where TABLE_SCHEMA not in ('mysql', 'sys', 'information_schema')";
                if (StringUtil.isEmpty(targetSchema)) {
                    //全库全表
                } else if (StringUtil.isEmpty(targetTable)) {
                    sql = String.format(sql.concat(" and TABLE_SCHEMA = '%s' "), targetSchema);
                } else {
                    sql = String.format(sql.concat(" and TABLE_SCHEMA = '%s' and TABLE_NAME = '%s'"), targetSchema, targetTable);
                }
                try (PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
                    ResultSet rowSet = preparedStatement.executeQuery();
                    while (rowSet.next()) {
                        //每张表单独转化
                        String tableSchema = rowSet.getString("TABLE_SCHEMA");
                        String tableName = rowSet.getString("TABLE_NAME");
                        String showddl = String.format("SHOW CREATE TABLE `%s`.`%s`", tableSchema, tableName);
                        try (PreparedStatement showddlPrepareStatement = connection.prepareStatement(showddl)) {
                            ResultSet showddlResultSet = showddlPrepareStatement.executeQuery();
                            if (showddlResultSet.next()) {
                                String tableddl = showddlResultSet.getString("Create Table");
                                String fullDDL = "CREATE TABLE `" + tableSchema + "`.`" + tableName + "` " + tableddl.substring(tableddl.indexOf("("));
                                Document doc = new Document(fullDDL).metadata(this.additionalMetadata);
                                documents.add(doc);
                            }
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
         * MYSQL，targetSchema为null，不管targetTable是否为null，则是全库全表加载
         * MYSQL，targetSchema不为null，targetTable为null则是单库全表加载
         * MYSQL，targetSchema不为null，targetTable不为null则是单库单表加载
         * 否则默认是全库全表(user表)加载
         */
        public Options loadOptions(String targetSchema, String targetTable) {
            this.targetSchema = targetSchema;
            this.targetTable = targetTable;
            return this;
        }
    }
}