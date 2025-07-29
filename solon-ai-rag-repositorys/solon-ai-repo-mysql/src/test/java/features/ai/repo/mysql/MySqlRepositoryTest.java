package features.ai.repo.mysql;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.embedding.EmbeddingModel;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.DocumentLoader;
import org.noear.solon.ai.rag.RepositoryStorable;
import org.noear.solon.ai.rag.loader.HtmlSimpleLoader;
import org.noear.solon.ai.rag.loader.MarkdownLoader;
import org.noear.solon.ai.rag.repository.MySqlRepository;
import org.noear.solon.ai.rag.repository.mysql.MetadataField;
import org.noear.solon.ai.rag.splitter.RegexTextSplitter;
import org.noear.solon.ai.rag.splitter.SplitterPipeline;
import org.noear.solon.ai.rag.splitter.TokenSizeTextSplitter;
import org.noear.solon.ai.rag.util.QueryCondition;
import org.noear.solon.net.http.HttpUtils;
import org.noear.solon.test.SolonTest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MySqlRepository 测试类
 *
 * @author noear
 * @since 3.4.2
 */
@SolonTest
public class MySqlRepositoryTest {
    private MySqlRepository repository;
    private EmbeddingModel embeddingModel;
    final String apiUrl = "http://192.168.1.16:11434/api/embed";
    final String provider = "ollama";
    final String model = "bge-m3";

    @BeforeEach
    public void setup() throws Exception {
        embeddingModel = EmbeddingModel.of(apiUrl).provider(provider).model(model).build();

        // 从配置中获取数据库连接信息
        String jdbcUrl = "jdbc:mysql://localhost:3306/solon_ai_test";
        String username = "root";
        String password = "test_123!";

        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(jdbcUrl);
        dataSource.setUsername(username);
        dataSource.setPassword(password);

        // 创建元数据索引字段列表
        List<MetadataField> metadataFields = new ArrayList<>();
        metadataFields.add(MetadataField.text("title"));
        metadataFields.add(MetadataField.text("category"));
        metadataFields.add(MetadataField.numeric("price"));
        metadataFields.add(MetadataField.numeric("stock"));

        // 创建测试用的 Repository
        repository = MySqlRepository.builder(embeddingModel, dataSource)
                .tableName("test_documents")
                .metadataFields(metadataFields)
                .build();

        // 清理并重新初始化
        repository.dropRepository();
        repository.initRepository();
        load(repository, "https://solon.noear.org/article/about?format=md");
        load(repository, "https://h5.noear.org/readme.htm");
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

    @AfterEach
    public void cleanup() {
        if (repository != null) {
            try {
                repository.dropRepository();
            } catch (Exception e) {
                // 忽略清理错误
            }
        }
    }

    @Test
    public void testSearch() throws Exception {

        List<Document> list = repository.search("solon");
        assert list.size() >= 3;//可能3个（效果更好）或4个

        list = repository.search("dubbo");
        assert list.isEmpty();

        Document doc = new Document("Test content");
        repository.insert(Collections.singletonList(doc));
        String key = doc.getId();

        Thread.sleep(1000);
        assertTrue(repository.exists(key), "Document should exist after storing");

        Thread.sleep(1000);
        repository.delete(doc.getId());
        assertFalse(repository.exists(key), "Document should not exist after removal");
    }

    @Test
    public void testRemove() {
        // 准备并存储测试数据
        List<Document> documents = new ArrayList<>();
        Document doc = new Document("Document to be removed", new HashMap<>());
        documents.add(doc);

        try {
            repository.insert(documents);
            Thread.sleep(1000);
            // 删除文档
            repository.delete(doc.getId());

            Thread.sleep(1000);
            // 验证文档已被删除
            assertFalse(repository.exists(doc.getId()), "文档应该已被删除");

        } catch (Exception e) {
            fail("测试过程中发生异常: " + e.getMessage());
        }
    }

    @Test
    public void testScoreOutput() throws IOException {

        try {
            // 执行搜索查询
            QueryCondition condition = new QueryCondition("solon").disableRefilter(true);
            List<Document> results = repository.search(condition);

            // 验证结果不为空
            assertFalse(results.isEmpty(), "搜索结果不应为空");

            // 验证每个文档都有评分
            for (Document doc : results) {
                assertTrue(doc.getScore() >= 0, "文档评分应该是非负数");

                System.out.println("Document ID: " + doc.getId() + ", Score: " + doc.getScore());
            }

            // 验证评分排序（如果有多个结果）
            if (results.size() > 1) {
                double firstScore = results.get(0).getScore();
                double secondScore = results.get(1).getScore();

                // 检查第一个结果的评分是否大于或等于第二个结果
                assertTrue(firstScore >= secondScore, "结果应该按评分降序排序");
            }

            // 打印所有结果的评分
            System.out.println("\n=== 评分测试结果 ===");
            for (Document doc : results) {
                double score = doc.getScore();
                System.out.println("ID: " + doc.getId());
                System.out.println("Score: " + score);
                System.out.println("Content: " + doc.getContent().substring(0, Math.min(50, doc.getContent().length())) + "...");
                System.out.println("---");
            }

        } catch (Exception e) {
            fail("测试过程中发生异常: " + e.getMessage());
        }
    }

    @Test
    public void testExpression() throws Exception {
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
}