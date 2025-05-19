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

/**
 * DDL 加载配置
 *
 * @author 刘颜
 * @since 3.3
 */
public class DdlLoadConfig {
    private String shcemaTableQuerySql;
    private String shcemaTableQueryAndSchemaSqlTemplate;
    private String shcemaTableQueryAndTableSqlTemplate;
    private String shcemaTableQueryResultSchemeNameColumn;
    private String shcemaTableQueryResultTableNameColumn;

    private String ddlQuerySqlTemplate;
    private String ddlQueryResultDdlNameColumn;

    public DdlLoadConfig(String shcemaTableQuerySql, String shcemaTableQueryAndSchemaSqlTemplate, String shcemaTableQueryAndTableSqlTemplate, String shcemaTableQueryResultSchemeNameColumn, String shcemaTableQueryResultTableNameColumn, String ddlQuerySqlTemplate, String ddlQueryResultDdlNameColumn) {
        this.shcemaTableQuerySql = shcemaTableQuerySql;
        this.shcemaTableQueryAndSchemaSqlTemplate = shcemaTableQueryAndSchemaSqlTemplate;
        this.shcemaTableQueryAndTableSqlTemplate = shcemaTableQueryAndTableSqlTemplate;
        this.shcemaTableQueryResultSchemeNameColumn = shcemaTableQueryResultSchemeNameColumn;
        this.shcemaTableQueryResultTableNameColumn = shcemaTableQueryResultTableNameColumn;
        this.ddlQuerySqlTemplate = ddlQuerySqlTemplate;
        this.ddlQueryResultDdlNameColumn = ddlQueryResultDdlNameColumn;
    }

    public static class Builder {
        private String shcemaTableQuerySql;
        private String shcemaTableQueryAndSchemaSqlTemplate;
        private String shcemaTableQueryAndTableSqlTemplate;
        private String shcemaTableQueryResultSchemeNameColumn;
        private String shcemaTableQueryResultTableNameColumn;
        private String ddlQuerySqlTemplate;
        private String ddlQueryResultDdlNameColumn;

        public Builder shcemaTableQuerySql(String shcemaTableQuerySql) {
            this.shcemaTableQuerySql = shcemaTableQuerySql;
            return this;
        }

        public Builder shcemaTableQueryAndSchemaSqlTemplate(String shcemaTableQueryAndSchemaSqlTemplate) {
            this.shcemaTableQueryAndSchemaSqlTemplate = shcemaTableQueryAndSchemaSqlTemplate;
            return this;
        }

        public Builder shcemaTableQueryAndTableSqlTemplate(String shcemaTableQueryAndTableSqlTemplate) {
            this.shcemaTableQueryAndTableSqlTemplate = shcemaTableQueryAndTableSqlTemplate;
            return this;
        }

        public Builder shcemaTableQueryResultSchemeNameColumn(String shcemaTableQueryResultSchemeNameColumn) {
            this.shcemaTableQueryResultSchemeNameColumn = shcemaTableQueryResultSchemeNameColumn;
            return this;
        }

        public Builder shcemaTableQueryResultTableNameColumn(String shcemaTableQueryResultTableNameColumn) {
            this.shcemaTableQueryResultTableNameColumn = shcemaTableQueryResultTableNameColumn;
            return this;
        }

        public Builder ddlQuerySqlTemplate(String ddlQuerySqlTemplate) {
            this.ddlQuerySqlTemplate = ddlQuerySqlTemplate;
            return this;
        }

        public Builder ddlQueryResultDdlNameColumn(String ddlQueryResultDdlNameColumn) {
            this.ddlQueryResultDdlNameColumn = ddlQueryResultDdlNameColumn;
            return this;
        }

        public DdlLoadConfig build() {
            return new DdlLoadConfig(this.shcemaTableQuerySql, this.shcemaTableQueryAndSchemaSqlTemplate, this.shcemaTableQueryAndTableSqlTemplate, this.shcemaTableQueryResultSchemeNameColumn, this.shcemaTableQueryResultTableNameColumn, this.ddlQuerySqlTemplate, this.ddlQueryResultDdlNameColumn);
        }
    }

    public String getShcemaTableQuerySql() {
        return shcemaTableQuerySql;
    }

    public String getShcemaTableQueryAndSchemaSqlTemplate() {
        return shcemaTableQueryAndSchemaSqlTemplate;
    }

    public String getShcemaTableQueryAndTableSqlTemplate() {
        return shcemaTableQueryAndTableSqlTemplate;
    }

    public String getShcemaTableQueryResultSchemeNameColumn() {
        return shcemaTableQueryResultSchemeNameColumn;
    }

    public String getShcemaTableQueryResultTableNameColumn() {
        return shcemaTableQueryResultTableNameColumn;
    }

    public String getDdlQuerySqlTemplate() {
        return ddlQuerySqlTemplate;
    }

    public String getDdlQueryResultDdlNameColumn() {
        return ddlQueryResultDdlNameColumn;
    }
}