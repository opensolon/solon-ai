package features.ai.load.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.loader.DdlLoadConfig;
import org.noear.solon.ai.rag.loader.DdlLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.File;
import java.io.IOException;
import java.util.List;

public class SqliteDdlLoaderTest {
    private static final Logger log = LoggerFactory.getLogger(SqliteDdlLoaderTest.class);

    private static DataSource ds;
    private static DdlLoadConfig ddlLoadConfig;
    ;

    public SqliteDdlLoaderTest() throws IOException {
        String sqlurl = "jdbc:sqlite:D:/xxxx/solon-ai/solon-ai-rag-loaders/solon-ai-load-ddl/src/test/resources/sqlite.db";
        String sqlusername = "";
        String sqlpassword = "";

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(sqlurl);
        hikariConfig.setUsername(sqlusername);
        hikariConfig.setPassword(sqlpassword);

        HikariDataSource hikariDataSource = new HikariDataSource(hikariConfig);

        DdlLoadConfig ddlLoadConfig0 = new DdlLoadConfig.Builder()
                .shcemaTableQuerySql("SELECT type, name FROM sqlite_master WHERE type = 'table' AND name NOT IN ('sqlite_master', 'sqlite_sequence', 'sqlite_stat1', 'sqlite_temp_master')")
                .shcemaTableQueryAndSchemaSqlTemplate("and 1=1")
                .shcemaTableQueryAndTableSqlTemplate("and name = '#{tableName}'")
                .shcemaTableQueryResultSchemeNameColumn("type")
                .shcemaTableQueryResultTableNameColumn("name")
                .ddlQuerySqlTemplate("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = '#{tableName}'")
                .ddlQueryResultDdlNameColumn("sql")
                .fullDdlCreateTableSqlPrefix("CREATE TABLE")
                .fullDdlCreateTableSqlNeedSchema(false)
                .fullDdlCreateTableSqlDot("")
                .fullDdlCreateTableSqlLeftDelimitedIdentifier("`")
                .fullDdlCreateTableSqlRightDelimitedIdentifier("`")
                .fullDdlCreateTableSqlTruncationSymbol("(")
                .build();

        //这里切换jdbc
        ds = hikariDataSource;
        ddlLoadConfig = ddlLoadConfig0;
    }

    @Test
    public void test1() throws Exception {
        //全库全表
        DdlLoader loader = new DdlLoader(ds, ddlLoadConfig);
        List<Document> docs = loader.load();
        System.out.println(docs.size());
        System.out.println(docs.get(0));
        assert docs.size() > 0;
    }

    @Test
    public void test2() throws Exception {
        //单库全表
        DdlLoader loader = new DdlLoader(ds, ddlLoadConfig);
        loader.options(opt -> opt.loadOptions("sqlite", null));
        List<Document> docs = loader.load();
        System.out.println(docs.size());
        System.out.println(docs.get(0));
        System.out.println(docs.get(docs.size() - 1));
        assert docs.size() > 0;
    }

    @Test
    public void test3() throws Exception {
        //单库单表
        DdlLoader loader = new DdlLoader(ds, ddlLoadConfig);
        loader.options(opt -> opt.loadOptions("sqlite", "demo"));
        List<Document> docs = loader.load();
        System.out.println(docs);
        System.out.println(docs.size());
        assert docs.size() > 0;
    }
}