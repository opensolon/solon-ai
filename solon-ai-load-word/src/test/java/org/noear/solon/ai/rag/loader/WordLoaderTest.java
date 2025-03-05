package org.noear.solon.ai.rag.loader;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.core.util.ResourceUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WordLoaderTest {
    private static final Logger log = LoggerFactory.getLogger(WordLoaderTest.class);

    @Test
    public void test1() throws Exception {
        WordLoader loader = new WordLoader(ResourceUtil.getResource("demo.docx"))
        .options(opt -> opt.loadMode(WordLoader.LoadMode.PAGE));
        List<Document> docs = loader.load();
        System.out.println(docs);
    }
}
