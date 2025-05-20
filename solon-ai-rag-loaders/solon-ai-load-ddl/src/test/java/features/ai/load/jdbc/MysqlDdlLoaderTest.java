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
import java.util.List;

public class MysqlDdlLoaderTest {
    private static final Logger log = LoggerFactory.getLogger(MysqlDdlLoaderTest.class);

    private static DataSource ds;
    private static DdlLoadConfig ddlLoadConfig;;

    public MysqlDdlLoaderTest() {
        String sqlurl = "jdbc:mysql://127.0.01:3306/zt";
        String sqlusername = "root";
        String sqlpassword = "root";

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(sqlurl);
        hikariConfig.setUsername(sqlusername);
        hikariConfig.setPassword(sqlpassword);

        HikariDataSource hikariDataSource = new HikariDataSource(hikariConfig);

        DdlLoadConfig ddlLoadConfig0 = new DdlLoadConfig.Builder()
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
        loader.options(opt -> opt.loadOptions("zt", null));
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
        loader.options(opt -> opt.loadOptions("zt", "location"));
        List<Document> docs = loader.load();
        System.out.println(docs);
        System.out.println(docs.size());
        assert docs.size() > 0;
    }
}