package org.noear.solon.ai.rag.loader;

public class JdbcLoadConfig {
    private String shcemaTableQuerySql;
    private String shcemaTableQueryAndSchemaSqlTemplate;
    private String shcemaTableQueryAndTableSqlTemplate;
    private String shcemaTableQueryResultSchemeNameColumn;
    private String shcemaTableQueryResultTableNameColumn;

    private String ddlQuerySqlTemplate;
    private String ddlQueryResultDdlNameColumn;


    public JdbcLoadConfig(String shcemaTableQuerySql, String shcemaTableQueryAndSchemaSqlTemplate, String shcemaTableQueryAndTableSqlTemplate, String shcemaTableQueryResultSchemeNameColumn, String shcemaTableQueryResultTableNameColumn, String ddlQuerySqlTemplate, String ddlQueryResultDdlNameColumn) {
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

        public JdbcLoadConfig build() {
            return new JdbcLoadConfig(this.shcemaTableQuerySql, this.shcemaTableQueryAndSchemaSqlTemplate, this.shcemaTableQueryAndTableSqlTemplate, this.shcemaTableQueryResultSchemeNameColumn, this.shcemaTableQueryResultTableNameColumn, this.ddlQuerySqlTemplate, this.ddlQueryResultDdlNameColumn);
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
