package features.ai.load.mysql;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.loader.MysqlLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class MysqlLoaderTest {
    private static final Logger log = LoggerFactory.getLogger(MysqlLoaderTest.class);

    private static String url = "jdbc:mysql://127.0.0.1:3306/test";
    private static String username = "root";
    private static String password = "root";

    @Test
    public void test1() throws Exception {
        //全库全表
        MysqlLoader loader = new MysqlLoader(url, username, password);
        List<Document> docs = loader.load();
        System.out.println(docs);
        assert docs.size() > 0;
    }

    @Test
    public void test2() throws Exception {
        //单库全表
        MysqlLoader loader = new MysqlLoader(url, username, password);
        loader.options(opt -> opt.loadOptions("zt", null));
        List<Document> docs = loader.load();
        System.out.println(docs);
        assert docs.size() > 0;
    }

    @Test
    public void test3() throws Exception {
        //单库单表
        MysqlLoader loader = new MysqlLoader(url, username, password);
        loader.options(opt -> opt.loadOptions("zt", "location"));
        List<Document> docs = loader.load();
        System.out.println(docs);
        assert docs.size() > 0;
    }
}