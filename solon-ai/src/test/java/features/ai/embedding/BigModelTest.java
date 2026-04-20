package features.ai.embedding;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.embedding.EmbeddingModel;
import org.noear.solon.ai.embedding.EmbeddingResponse;
import org.noear.solon.test.SolonTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * @author noear 2025/1/28 created
 */
@SolonTest
public class BigModelTest {
    private static final Logger log = LoggerFactory.getLogger(BigModelTest.class);
    private static final String apiUrl = "https://open.bigmodel.cn/api/paas/v4/embeddings";
    private static final String apiKey = "52755d7995a8413783bb70ff6d44f42f.zCAKSzqlo9hmJS7s";
    private static final String provider = "bigmodel";
    private static final String model = "embedding-3";

    @Test
    public void case1() throws IOException {
        EmbeddingModel embeddingModel = EmbeddingModel.of(apiUrl)
                .apiKey(apiKey)
                .provider(provider) //需要指定供应商，用于识别接口风格（也称为方言）
                .model(model)
                .modelOptions(o -> o.dimensions(1024))
                .build();

        //一次性返回
        EmbeddingResponse resp = embeddingModel
                .input("比较原始的风格", "能表达内在的大概过程", "太阳升起来了")
                .call();

        //打印消息
        log.warn("{}", resp.getData());
    }
}