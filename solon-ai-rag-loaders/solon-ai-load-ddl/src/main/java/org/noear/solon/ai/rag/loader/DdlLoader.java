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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.noear.snack.core.utils.StringUtil;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.expression.snel.SnEL;
import org.noear.solon.lang.Preview;

import javax.sql.DataSource;

/**
 * DDL 加载器
 * <p>
 * 读取表结构DDL，并转换为文本
 * </p>
 *
 * @author 刘颜
 * @since 3.3
 */
@Preview("3.3")
public class DdlLoader extends AbstractOptionsDocumentLoader<DdlLoader.Options, DdlLoader> {
    private final DataSource dataSource;
    private final DdlLoadConfig jdbcLoadConfig;

    public DdlLoader(DataSource dataSource) {
        this.dataSource = dataSource;
        //默认支持mysql
        this.jdbcLoadConfig = new DdlLoadConfig.Builder()
                .shcemaTableQuerySql("select TABLE_SCHEMA, TABLE_NAME from information_schema.TABLES where TABLE_SCHEMA not in ('mysql', 'sys', 'information_schema')")
                .shcemaTableQueryAndSchemaSqlTemplate("and TABLE_SCHEMA = '#{tableSchema}'")
                .shcemaTableQueryAndTableSqlTemplate("and TABLE_SCHEMA = '#{tableSchema}' and TABLE_NAME = '#{tableName}'")
                .shcemaTableQueryResultSchemeNameColumn("TABLE_SCHEMA")
                .shcemaTableQueryResultTableNameColumn("TABLE_NAME")
                .ddlQuerySqlTemplate("SHOW CREATE TABLE `#{tableSchema}`.`#{tableName}`")
                .ddlQueryResultDdlNameColumn("Create Table")
                .fullDdlCreateTableSqlPrefix("CREATE TABLE")
                .fullDdlCreateTableSqlNeedSchema(true)
                .fullDdlCreateTableSqlDot(".")
                .fullDdlCreateTableSqlLeftDelimitedIdentifier("`")
                .fullDdlCreateTableSqlRightDelimitedIdentifier("`")
                .fullDdlCreateTableSqlTruncationSymbol("(")
                .build();
        this.options = new Options();
    }

    public DdlLoader(DataSource dataSource, DdlLoadConfig jdbcLoadConfig) {
        this.dataSource = dataSource;
        this.jdbcLoadConfig = jdbcLoadConfig;
        this.options = new Options();
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
                Map<String, String> schemaTableQueryTempleateConfigMap = new HashMap<>(1);
                schemaTableQueryTempleateConfigMap.put("tableSchema", targetSchema);
                sql = SnEL.evalTmpl(sql + " " + this.jdbcLoadConfig.getShcemaTableQueryAndSchemaSqlTemplate(), schemaTableQueryTempleateConfigMap);
            } else {
                //单库单表
                Map<String, String> schemaTableQueryTempleateConfigMap = new HashMap<>(2);
                schemaTableQueryTempleateConfigMap.put("tableSchema", targetSchema);
                schemaTableQueryTempleateConfigMap.put("tableName", targetTable);
                sql = SnEL.evalTmpl(sql + " " + this.jdbcLoadConfig.getShcemaTableQueryAndTableSqlTemplate(), schemaTableQueryTempleateConfigMap);
            }

            try (PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
                ResultSet rowSet = preparedStatement.executeQuery();

                while (rowSet.next()) {
                    //每张表单独转化
                    String tableSchema = rowSet.getString(this.jdbcLoadConfig.getShcemaTableQueryResultSchemeNameColumn());
                    String tableName = rowSet.getString(this.jdbcLoadConfig.getShcemaTableQueryResultTableNameColumn());

                    Map<String, String> ddlQueryTempleateConfigMap = new HashMap<>(2);
                    ddlQueryTempleateConfigMap.put("tableSchema", tableSchema);
                    ddlQueryTempleateConfigMap.put("tableName", tableName);
                    String showddl = SnEL.evalTmpl(this.jdbcLoadConfig.getDdlQuerySqlTemplate(), ddlQueryTempleateConfigMap);

                    try (PreparedStatement showddlPrepareStatement = connection.prepareStatement(showddl)) {
                        ResultSet showddlResultSet = showddlPrepareStatement.executeQuery();

                        if (showddlResultSet.next()) {
                            String tableddl = showddlResultSet.getString(this.jdbcLoadConfig.getDdlQueryResultDdlNameColumn());
                            String fullDDL = getFullDdl(tableddl, tableSchema, tableName);

                            Document doc = new Document(fullDDL)
                                    .metadata("table", tableName)
                                    .metadata(this.additionalMetadata);
                            documents.add(doc);
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        return documents;
    }

    private String getFullDdl(String tableddl, String tableSchema, String tableName) {
        Map<String, String> fullDdlTempleateConfigMap = new HashMap<>(7);
        fullDdlTempleateConfigMap.put("tableSchema", tableSchema);
        fullDdlTempleateConfigMap.put("tableName", tableName);
        fullDdlTempleateConfigMap.put("fullDdlCreateTableSqlPrefix", this.jdbcLoadConfig.getFullDdlCreateTableSqlPrefix());
        fullDdlTempleateConfigMap.put("fullDdlCreateTableSqlDot", this.jdbcLoadConfig.getFullDdlCreateTableSqlDot());
        fullDdlTempleateConfigMap.put("fullDdlCreateTableSqlLeftDelimitedIdentifier", this.jdbcLoadConfig.getFullDdlCreateTableSqlLeftDelimitedIdentifier());
        fullDdlTempleateConfigMap.put("fullDdlCreateTableSqlRightDelimitedIdentifier", this.jdbcLoadConfig.getFullDdlCreateTableSqlRightDelimitedIdentifier());
        fullDdlTempleateConfigMap.put("fullDdlCreateTableSqlTruncation", tableddl.substring(tableddl.indexOf(this.jdbcLoadConfig.getFullDdlCreateTableSqlTruncationSymbol())));
        if (this.jdbcLoadConfig.isFullDdlCreateTableSqlNeedSchema()) {
            return SnEL.evalTmpl("#{fullDdlCreateTableSqlPrefix} #{fullDdlCreateTableSqlLeftDelimitedIdentifier}#{tableSchema}#{fullDdlCreateTableSqlRightDelimitedIdentifier}#{fullDdlCreateTableSqlDot}#{fullDdlCreateTableSqlLeftDelimitedIdentifier}#{tableName}#{fullDdlCreateTableSqlRightDelimitedIdentifier} #{fullDdlCreateTableSqlTruncation}", fullDdlTempleateConfigMap);
        } else {
            return SnEL.evalTmpl("#{fullDdlCreateTableSqlPrefix} #{fullDdlCreateTableSqlLeftDelimitedIdentifier}#{tableName}#{fullDdlCreateTableSqlRightDelimitedIdentifier} #{fullDdlCreateTableSqlTruncation}", fullDdlTempleateConfigMap);
        }
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