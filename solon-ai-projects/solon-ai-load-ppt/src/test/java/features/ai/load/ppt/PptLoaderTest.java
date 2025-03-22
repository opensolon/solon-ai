package features.ai.load.ppt;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.loader.PptLoader;
import org.noear.solon.core.util.ResourceUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class PptLoaderTest {
    private static final Logger log = LoggerFactory.getLogger(PptLoaderTest.class);

    @Test
    public void test1() throws Exception {
        PptLoader loader = new PptLoader(ResourceUtil.getResource("demo.pptx"))
                .options(opt -> opt.loadMode(PptLoader.LoadMode.PARAGRAPH));
        List<Document> docs = loader.load();
        System.out.println(docs);
        assert docs.size()  == 2;
    }

    @Test
    public void test2() throws Exception {
        PptLoader loader = new PptLoader(ResourceUtil.getResource("demo.ppt"))
                .options(opt -> opt.loadMode(PptLoader.LoadMode.PARAGRAPH));
        List<Document> docs = loader.load();
        System.out.println(docs);
        assert docs.size()  == 2;
    }
}
