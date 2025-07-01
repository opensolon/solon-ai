package features.ai.read.md;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.loader.MarkdownLoader;
import org.noear.solon.net.http.HttpUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class MarkdownLoaderTest {

    @Test
    public void testSingleUrl() throws Exception {
        String md = HttpUtils.http("https://solon.noear.org/article/about?format=md").get();

        MarkdownLoader markdownLoader = new MarkdownLoader(md.getBytes(StandardCharsets.UTF_8))
                .options(o -> o.horizontalLineAsNew(true)
                        .blockquoteAsNew(true)
                        .codeBlockAsNew(true));

        int i = 0;
        for (Document doc : markdownLoader.load()) {
            System.out.println((i++) + ":------------------------------------------------------------");
            System.out.println(doc);
        }
    }

    @Test
    public void testSingleUrl2() throws Exception {
        String md = "Solon 已经形成了一个比较开放的、比较丰富的生态。并不断完善和扩展中\n" +
                "\n" +
                " ![](/img/369a9093918747df8ab0a5ccc314306a.png)\n" +
                " \n" +
                " Solon 三大基础组成（核心组件）：\n" +
                " \n" +
                "| 基础组成                  | 说明 | \n" +
                "| ---------------- | -------- | \n" +
                "| 插件扩展机制             | 提供“编码风格”的扩展体系     | \n" +
                "| Ioc/Aop 容器             | 提供基于注入依赖的自动装配体系     | \n" +
                "| 通用上下文处理接口    | 提供开放式网络协议对接适配体系（俗称，三元合一）     | \n" +
                " \n" +
                " ";

        MarkdownLoader markdownLoader = new MarkdownLoader(md.getBytes(StandardCharsets.UTF_8))
                .options(o -> o.horizontalLineAsNew(true)
                        .blockquoteAsNew(true)
                        .codeBlockAsNew(true));

        int i = 0;
        for (Document doc : markdownLoader.load()) {
            System.out.println((i++) + ":------------------------------------------------------------");
            System.out.println(doc);

            assert doc.getContent().contains("![](");
        }
    }
}
