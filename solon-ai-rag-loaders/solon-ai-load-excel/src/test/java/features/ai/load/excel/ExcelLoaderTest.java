package features.ai.load.excel;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.loader.ExcelLoader;
import org.noear.solon.core.util.ResourceUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class ExcelLoaderTest {
    private static final Logger log = LoggerFactory.getLogger(ExcelLoaderTest.class);

    @Test
    public void test1() throws Exception {
        ExcelLoader loader = new ExcelLoader(ResourceUtil.getResource("demo.xlsx"));
        List<Document> docs = loader.load();
        System.out.println(docs);
    }

    @Test
    public void test2() throws Exception {
        ExcelLoader loader = new ExcelLoader(ResourceUtil.getResource("demo.xls"));
        List<Document> docs = loader.load();
        System.out.println(docs);
    }
}
