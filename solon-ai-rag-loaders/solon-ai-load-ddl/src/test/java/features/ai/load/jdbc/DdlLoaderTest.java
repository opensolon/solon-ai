package features.ai.load.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.loader.DdlLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.util.List;

public class DdlLoaderTest {
    private static final Logger log = LoggerFactory.getLogger(DdlLoaderTest.class);

    private static DataSource ds;

    public DdlLoaderTest() {
        String url = "jdbc:mysql://127.0.0.1:3306/test";
        String username = "root";
        String password = "root";

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);

        ds = new HikariDataSource(config);
    }

    @Test
    public void test1() throws Exception {
        //全库全表
        DdlLoader loader = new DdlLoader(ds);
        List<Document> docs = loader.load();
        System.out.println(docs.size());
        assert docs.size() > 0;
    }

    @Test
    public void test2() throws Exception {
        //单库全表
        DdlLoader loader = new DdlLoader(ds);
        loader.options(opt -> opt.loadOptions("zt", null));
        List<Document> docs = loader.load();
        System.out.println(docs.size());
        assert docs.size() > 0;
    }

    @Test
    public void test3() throws Exception {
        //单库单表
        DdlLoader loader = new DdlLoader(ds);
        loader.options(opt -> opt.loadOptions("zt", "location"));
        List<Document> docs = loader.load();
        System.out.println(docs);
        System.out.println(docs.size());
        assert docs.size() > 0;
    }
}