package features.ai.repo.redis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.redisx.RedisClient;
import org.noear.solon.Solon;
import org.noear.solon.ai.embedding.EmbeddingModel;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.DocumentLoader;
import org.noear.solon.ai.rag.RepositoryStorable;
import org.noear.solon.ai.rag.loader.HtmlSimpleLoader;
import org.noear.solon.ai.rag.loader.MarkdownLoader;
import org.noear.solon.ai.rag.repository.redis.MetadataField;
import org.noear.solon.ai.rag.repository.RedisRepository;
import org.noear.solon.ai.rag.splitter.RegexTextSplitter;
import org.noear.solon.ai.rag.splitter.SplitterPipeline;
import org.noear.solon.ai.rag.splitter.TokenSizeTextSplitter;
import org.noear.solon.ai.rag.util.QueryCondition;
import org.noear.solon.net.http.HttpUtils;
import org.noear.solon.test.SolonTest;

import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.search.Schema;

/**
 * @author noear 2025/2/26 created
 */
@SolonTest
public class RedisRepositoryTest {
    private RedisRepository repository;
    private UnifiedJedis client;


    @BeforeEach
    public void setup() throws IOException {
        // 创建测试用的 MockEmbeddingModel
        EmbeddingModel embeddingModel = TestUtils.getEmbeddingModel();

        // 创建 Redis 客户端
        RedisClient redisClient = Solon.cfg().getBean("solon.ai.repo.redis", RedisClient.class);
        if (redisClient == null) {
            throw new IllegalStateException("Redis client configuration not found!");
        }
        client = redisClient.jedis();

        // 创建元数据索引字段列表
        List<MetadataField> metadataFields = new ArrayList<>();
        metadataFields.add(new MetadataField("title", Schema.FieldType.TAG)); // 使用TAG类型以支持精确匹配
        metadataFields.add(new MetadataField("category", Schema.FieldType.TAG));

        // 创建测试用的 Repository - 使用 Builder 模式创建
        repository = RedisRepository.builder(embeddingModel, client)
                .indexName("test_idx")
                .keyPrefix("test_doc:")
                .metadataIndexFields(metadataFields)
                .build();

        repository.dropRepository();
        repository.initRepository();

        load(repository, "https://solon.noear.org/article/about?format=md");
        load(repository, "https://h5.noear.org/more.htm");
        load(repository, "https://h5.noear.org/readme.htm");
    }

    @AfterEach
    public void cleanup() {
        //repository.dropRepository();
    }

    @Test
    public void case1_search() throws Exception {
        List<Document> list = repository.search("solon");
        assert list.size() == 4;

        List<Document> list2 = repository.search("temporal");
        assert list2.isEmpty();

        /// /////////////////////////////

        // 准备并存储文档，显式指定 ID
        Document doc = new Document("Test content");
        repository.insert(Collections.singletonList(doc));
        String key = doc.getId();

        // 验证存储成功
        assertTrue(repository.exists(key), "Document should exist after storing");

        // 删除文档
        repository.delete(doc.getId());

        // 验证删除成功
        assertFalse(repository.exists(key), "Document should not exist after removal");
    }

    @Test
    public void case2_expression() throws Exception {
        // 新增带有元数据的文档
        Document doc1 = new Document("Document about Solon framework");
        doc1.getMetadata().put("title", "solon");
        doc1.getMetadata().put("category", "framework");

        Document doc2 = new Document("Document about Java settings");
        doc2.getMetadata().put("title", "设置");
        doc2.getMetadata().put("category", "tutorial");

        Document doc3 = new Document("Document about Spring framework");
        doc3.getMetadata().put("title", "spring");
        doc3.getMetadata().put("category", "framework");

        List<Document> documents = new ArrayList<>();
        documents.add(doc1);
        documents.add(doc2);
        documents.add(doc3);
        repository.insert(documents);

        try {
            // 1. 使用OR表达式过滤进行搜索
            String orExpression = "title == 'solon' OR title == '设置'";
            List<Document> orResults = repository.search(new QueryCondition("framework").filterExpression(orExpression));

            System.out.println("Found " + orResults.size() + " documents with OR filter expression: " + orExpression);

            // 验证结果包含2个文档
            assert orResults.size() == 3;

            // 2. 使用AND表达式过滤
            String andExpression = "title == 'solon' AND category == 'framework'";
            List<Document> andResults = repository.search(new QueryCondition("framework").filterExpression(andExpression));

            System.out.println("Found " + andResults.size() + " documents with AND filter expression: " + andExpression);

            // 验证结果只包含1个文档
            assertEquals(1, andResults.size());

            // 3. 使用category过滤
            String categoryExpression = "category == 'framework'";
            List<Document> categoryResults = repository.search(new QueryCondition("framework").filterExpression(categoryExpression));

            System.out.println("Found " + categoryResults.size() + " documents with category filter: " + categoryExpression);

            // 验证结果包含2个framework类别的文档
            assertEquals(2, categoryResults.size());
        } finally {
            // 清理测试数据
            repository.delete(doc1.getId(), doc2.getId(), doc3.getId());
        }
    }

    private void load(RepositoryStorable repository, String url) throws IOException {
        String text = HttpUtils.http(url).get();

        DocumentLoader loader = null;
        if (text.contains("</html>")) {
            loader = new HtmlSimpleLoader(text.getBytes(StandardCharsets.UTF_8));
        } else {
            loader = new MarkdownLoader(text.getBytes(StandardCharsets.UTF_8));
        }

        List<Document> documents = new SplitterPipeline() //2.分割文档（确保不超过 max-token-size）
                .next(new RegexTextSplitter())
                .next(new TokenSizeTextSplitter(500))
                .split(loader.load());

        repository.insert(documents); //（推入文档）
    }
}
