package features.ai.rag;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.loader.TextLoader;
import org.noear.solon.core.util.ResourceUtil;

import java.io.File;
import java.util.List;

/**
 *
 * @author noear 2025/8/14 created
 *
 */
public class TextLoaderTest {
    @Test
    public void case1() throws Throwable {
        TextLoader textLoader = new TextLoader(ResourceUtil.getResource("app.yml"));
        //textLoader.options(o -> o.charset("utf-8"));

        List<Document> documentList = textLoader.load();

        assert documentList != null;
        assert documentList.size() >= 1;

        System.out.println(documentList.toString());
    }
}
