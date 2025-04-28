package features.ai.repo.redis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;

import org.junit.jupiter.api.AfterEach;
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

import static org.junit.jupiter.api.Assertions.*;

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
        metadataFields.add(MetadataField.tag("title")); // 使用TAG类型以支持精确匹配
        metadataFields.add(MetadataField.tag("category"));
        metadataFields.add(MetadataField.numeric("price"));
        metadataFields.add(MetadataField.numeric("stock"));

        // 创建测试用的 Repository - 使用 Builder 模式创建
        repository = RedisRepository.builder(embeddingModel, client)
                .indexName("test_idx")
                .keyPrefix("test_doc:")
                .metadataIndexFields(metadataFields)
                .build();

        repository.dropRepository();
        repository.initRepository();

        load(repository, "https://solon.noear.org/article/about?format=md");
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
            List<Document> orResults = repository.search(new QueryCondition("framework").filterExpression(orExpression).disableRefilter(true));

            System.out.println("Found " + orResults.size() + " documents with OR filter expression: " + orExpression);

            // 验证结果包含2个文档
            assert orResults.size() == 2;

            // 2. 使用AND表达式过滤
            String andExpression = "title == 'solon' AND category == 'framework'";
            List<Document> andResults = repository.search(new QueryCondition("framework").filterExpression(andExpression).disableRefilter(true));

            System.out.println("Found " + andResults.size() + " documents with AND filter expression: " + andExpression);

            // 验证结果只包含1个文档
            assertEquals(1, andResults.size());

            // 3. 使用category过滤
            String categoryExpression = "category == 'framework'";
            List<Document> categoryResults = repository.search(new QueryCondition("framework").filterExpression(categoryExpression).disableRefilter(true));

            System.out.println("Found " + categoryResults.size() + " documents with category filter: " + categoryExpression);

            // 验证结果包含2个framework类别的文档
            assertEquals(2, categoryResults.size());
        } finally {
            // 清理测试数据
            repository.delete(doc1.getId(), doc2.getId(), doc3.getId());
        }
    }

    @Test
    public void testAdvancedExpressionFilter() throws IOException {
        try {
            // 创建测试文档
            List<Document> documents = new ArrayList<>();

            Document doc1 = new Document("Document with numeric properties");
            doc1.metadata("price", 100);
            doc1.metadata("stock", 50);
            doc1.metadata("category", "electronics");

            Document doc2 = new Document("Document with different price");
            doc2.metadata("price", 200);
            doc2.metadata("stock", 10);
            doc2.metadata("category", "electronics");

            Document doc3 = new Document("Document with different category");
            doc3.metadata("price", 150);
            doc3.metadata("stock", 25);
            doc3.metadata("category", "books");

            documents.add(doc1);
            documents.add(doc2);
            documents.add(doc3);

            // 插入测试文档
            repository.insert(documents);

            // 等待索引更新
            Thread.sleep(1000);

            // 1. 测试数值比较 (大于)
            String gtExpression = "price > 120";
            QueryCondition gtCondition = new QueryCondition("document")
                    .filterExpression(gtExpression);

            List<Document> gtResults = repository.search(gtCondition);
            System.out.println("找到 " + gtResults.size() + " 个文档，使用大于表达式: " + gtExpression);

            // 验证结果 - 应该找到两个价格大于120的文档
            assertTrue(gtResults.size() > 0, "大于表达式应该找到文档");
            int countGt120 = 0;
            for (Document doc : gtResults) {
                int price = ((Number) doc.getMetadata("price")).intValue();
                if (price > 120) {
                    countGt120++;
                }
            }
            assertTrue(countGt120 > 0, "应该找到价格大于120的文档");

            // 2. 测试数值比较 (小于等于)
            String lteExpression = "stock <= 25";
            QueryCondition lteCondition = new QueryCondition("document")
                    .filterExpression(lteExpression);

            List<Document> lteResults = repository.search(lteCondition);
            System.out.println("找到 " + lteResults.size() + " 个文档，使用小于等于表达式: " + lteExpression);

            // 验证结果 - 应该找到两个库存小于等于25的文档
            assertTrue(lteResults.size() > 0, "小于等于表达式应该找到文档");
            int countLte25 = 0;
            for (Document doc : lteResults) {
                int stock = ((Number) doc.getMetadata("stock")).intValue();
                if (stock <= 25) {
                    countLte25++;
                }
            }
            assertTrue(countLte25 > 0, "应该找到库存小于等于25的文档");

            // 3. 测试复合表达式 (价格区间和类别)
            String complexExpression = "(price >= 100 AND price <= 180) AND category == 'electronics'";
            QueryCondition complexCondition = new QueryCondition("document")
                    .filterExpression(complexExpression);

            List<Document> complexResults = repository.search(complexCondition);
            System.out.println("找到 " + complexResults.size() + " 个文档，使用复合表达式: " + complexExpression);

            // 验证结果 - 应该找到一个满足所有条件的文档
            assertTrue(complexResults.size() > 0, "复合表达式应该找到文档");
            boolean foundMatch = false;
            for (Document doc : complexResults) {
                int price = ((Number) doc.getMetadata("price")).intValue();
                String category = (String) doc.getMetadata("category");
                if (price >= 100 && price <= 180 && "electronics".equals(category)) {
                    foundMatch = true;
                    break;
                }
            }
            assertTrue(foundMatch, "应该找到符合复合条件的文档");

            // 4. 测试 IN 操作符
            String inExpression = "category IN ['electronics', 'books']";
            QueryCondition inCondition = new QueryCondition("document")
                    .filterExpression(inExpression);

            List<Document> inResults = repository.search(inCondition);
            System.out.println("找到 " + inResults.size() + " 个文档，使用IN表达式: " + inExpression);
            assertTrue(inResults.size() > 0, "IN表达式应该找到文档");

            // 5. 测试 NOT 操作符
            String notExpression = "NOT (category == 'books')";
            QueryCondition notCondition = new QueryCondition("document")
                    .filterExpression(notExpression);
            List<Document> notResults = repository.search(notCondition);
            System.out.println("找到 " + notResults.size() + " 个文档，使用NOT表达式: " + notExpression);
            assertTrue(notResults.size() > 0, "NOT表达式应该找到文档");
            boolean foundNonBooks = false;
            for (Document doc : notResults) {
                String category = (String) doc.getMetadata("category");
                if (!"books".equals(category)) {
                    foundNonBooks = true;
                    break;
                }
            }
            assertTrue(foundNonBooks, "应该找到非books类别的文档");

            // 打印结果
            System.out.println("\n=== 高级表达式过滤测试结果 ===");
            System.out.println("大于表达式结果数量: " + gtResults.size());
            System.out.println("小于等于表达式结果数量: " + lteResults.size());
            System.out.println("复合表达式结果数量: " + complexResults.size());
            System.out.println("IN表达式结果数量: " + inResults.size());
            System.out.println("NOT表达式结果数量: " + notResults.size());

        } catch (Exception e) {
            e.printStackTrace();
            fail("测试过程中发生异常: " + e.getMessage());
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
