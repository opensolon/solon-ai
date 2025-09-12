package demo.ai.repo.milvus;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.embedding.EmbeddingModel;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.loader.MarkdownLoader;
import org.noear.solon.ai.rag.repository.MilvusRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;

public class AsyncInsertDemoTest {

    private static final Logger log = LoggerFactory.getLogger(AsyncInsertDemoTest.class);
    private static String milvusUri = "http://192.168.1.149:19530";

    private ConnectConfig connectConfig = ConnectConfig.builder()
            .uri(milvusUri)
            .build();
    private MilvusClientV2 client = new MilvusClientV2(connectConfig);
    private MilvusRepository repository;

    public EmbeddingModel getEmbeddingModel() {
        final String apiUrl = "http://192.168.1.149:10100/v1/embeddings";
        final String provider = "openai";
        final String model = "bge-m3";//

        return EmbeddingModel.of(apiUrl).provider(provider).model(model).build();
    }

    @BeforeEach
    public void setup() {
        repository = MilvusRepository.builder(this.getEmbeddingModel(), client)
                .build(); //3.初始化知识库

    }

    @AfterEach
    public void cleanup() {
        repository.dropRepository();
    }

    @Test
    public void demo() throws IOException, ExecutionException, InterruptedException {
        MarkdownLoader markdownLoader = new MarkdownLoader(new File("src/test/java/test.md"));

        List<Document> load = markdownLoader.load();
        BiConsumer<Integer, Integer> progressCallback = (idx, total) -> {
            log.info("处理进度：{}/{}", idx, total);
        };
        CompletableFuture<Void> future = repository.asyncSave(load, progressCallback);

        log.info("开始插入向量数据库");
        //阻塞住线程
        future.get();
        future.thenRun(() -> log.info("插入已经完成"));
    }
}
