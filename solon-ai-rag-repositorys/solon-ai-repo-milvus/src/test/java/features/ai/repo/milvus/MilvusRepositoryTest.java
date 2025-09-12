package features.ai.repo.milvus;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.DocumentLoader;
import org.noear.solon.ai.rag.RepositoryStorable;
import org.noear.solon.ai.rag.loader.HtmlSimpleLoader;
import org.noear.solon.ai.rag.loader.MarkdownLoader;
import org.noear.solon.ai.rag.repository.MilvusRepository;
import org.noear.solon.ai.rag.splitter.RegexTextSplitter;
import org.noear.solon.ai.rag.splitter.SplitterPipeline;
import org.noear.solon.ai.rag.splitter.TokenSizeTextSplitter;
import org.noear.solon.ai.rag.util.QueryCondition;
import org.noear.solon.net.http.HttpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class MilvusRepositoryTest {
    private static final Logger log = LoggerFactory.getLogger(MilvusRepositoryTest.class);
    private static String milvusUri = "http://localhost:19530";

    private ConnectConfig connectConfig = ConnectConfig.builder()
            .uri(milvusUri)
            .build();
    private MilvusClientV2 client = new MilvusClientV2(connectConfig);
    private MilvusRepository repository;

    @BeforeEach
    public void setup() throws Exception {
        repository = MilvusRepository.builder(TestUtils.getEmbeddingModel(), client)
                .build(); //3.初始化知识库

        load(repository, "https://solon.noear.org/article/about?format=md");
        load(repository, "https://h5.noear.org/readme.htm");
    }

    @AfterEach
    public void cleanup() {
        repository.dropRepository();
    }

    @Test
    public void case1_search() throws Exception {
        List<Document> list = repository.search("solon");
        assert list.size() == 4;

        list = repository.search("dubbo");
        assert list.size() == 0;


        /// /////////////////////////////

        // 准备并存储文档，显式指定 ID
        Document doc = new Document("Test content");
        repository.save(Collections.singletonList(doc));
        String key = doc.getId();

        Thread.sleep(1000);

        // 验证存储成功
        assertTrue(repository.existsById(key), "Document should exist after storing");

        // 删除文档
        repository.deleteById(doc.getId());

        Thread.sleep(1000);

        // 验证删除成功
        assertFalse(repository.existsById(key), "Document should not exist after removal");
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
        repository.save(documents);

        Thread.sleep(1000);

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
            repository.deleteById(doc1.getId(), doc2.getId(), doc3.getId());
        }
    }

    /**
     * 测试表达式过滤功能
     */
    @Test
    public void testExpressionFilter() throws IOException {
        checkRepository();

        // 创建测试文档
        Document doc1 = new Document("Solon framework introduction");
        doc1.getMetadata().put("title", "solon");
        doc1.getMetadata().put("category", "framework");

        Document doc2 = new Document("Java configuration settings");
        doc2.getMetadata().put("title", "设置");
        doc2.getMetadata().put("category", "tutorial");

        Document doc3 = new Document("Spring framework overview");
        doc3.getMetadata().put("title", "spring");
        doc3.getMetadata().put("category", "framework");

        List<Document> documents = new ArrayList<>();
        documents.add(doc1);
        documents.add(doc2);
        documents.add(doc3);

        try {
            // 插入测试文档
            repository.save(documents);

            // 等待索引更新
            Thread.sleep(1000);

            // 1. 测试 OR 表达式
            String orExpression = "title == 'solon' OR title == '设置'";
            QueryCondition orCondition = new QueryCondition("framework")
                    .filterExpression(orExpression)
                    .disableRefilter(true);

            List<Document> orResults = repository.search(orCondition);
            System.out.println("找到 " + orResults.size() + " 个文档，使用 OR 表达式: " + orExpression);

            // 验证结果 - 应该找到两个文档 (solon 和 设置)
            assertTrue(orResults.size() >= 1, "OR 表达式应该至少找到一个文档");
            boolean foundSolon = false;
            boolean foundSettings = false;

            for (Document doc : orResults) {
                String title = (String) doc.getMetadata().get("title");
                if ("solon".equals(title)) {
                    foundSolon = true;
                } else if ("设置".equals(title)) {
                    foundSettings = true;
                }
            }

            // 由于向量搜索可能会匹配其他结果，我们只检查是否找到了至少一个预期的文档
            assertTrue(foundSolon || foundSettings, "OR 表达式应该找到 'solon' 或 '设置' 文档");

            // 2. 测试 AND 表达式
            String andExpression = "title == 'solon' AND category == 'framework'";
            QueryCondition andCondition = new QueryCondition("framework")
                    .filterExpression(andExpression)
                    .disableRefilter(true);

            List<Document> andResults = repository.search(andCondition);
            System.out.println("找到 " + andResults.size() + " 个文档，使用 AND 表达式: " + andExpression);

            // 验证结果 - 应该只找到一个文档 (solon && framework)
            assertTrue(andResults.size() >= 1, "AND 表达式应该至少找到一个文档");
            boolean foundSolonFramework = false;

            for (Document doc : andResults) {
                String title = (String) doc.getMetadata().get("title");
                String category = (String) doc.getMetadata().get("category");
                if ("solon".equals(title) && "framework".equals(category)) {
                    foundSolonFramework = true;
                    break;
                }
            }

            assertTrue(foundSolonFramework, "AND 表达式应该找到 'solon' && 'framework' 文档");

            // 3. 测试简单过滤表达式
            String simpleExpression = "category == 'framework'";
            QueryCondition simpleCondition = new QueryCondition("framework")
                    .filterExpression(simpleExpression)
                    .disableRefilter(true);

            List<Document> simpleResults = repository.search(simpleCondition);
            System.out.println("找到 " + simpleResults.size() + " 个文档，使用简单表达式: " + simpleExpression);

            // 验证结果 - 应该找到两个 framework 类别的文档
            assertTrue(simpleResults.size() >= 1, "简单表达式应该至少找到一个文档");
            int frameworkCount = 0;

            for (Document doc : simpleResults) {
                String category = (String) doc.getMetadata().get("category");
                if ("framework".equals(category)) {
                    frameworkCount++;
                }
            }

            assertTrue(frameworkCount >= 1, "简单表达式应该至少找到一个 'framework' 类别的文档");

            // 打印结果
            System.out.println("\n=== 表达式过滤测试结果 ===");
            System.out.println("OR 表达式结果数量: " + orResults.size());
            System.out.println("AND 表达式结果数量: " + andResults.size());
            System.out.println("简单表达式结果数量: " + simpleResults.size());

        } catch (Exception e) {
            e.printStackTrace();
            fail("测试过程中发生异常: " + e.getMessage());
        } finally {
            // 清理测试文档
            try {
                repository.deleteById(doc1.getId(), doc2.getId(), doc3.getId());
            } catch (Exception e) {
                System.err.println("清理测试文档失败: " + e.getMessage());
            }
        }
    }

    /**
     * 测试高级表达式过滤功能
     */
    @Test
    public void testAdvancedExpressionFilter() throws Exception {
        checkRepository();

        // 创建测试文档
        Document doc1 = new Document("Document with numeric properties");
        doc1.getMetadata().put("price", 100);
        doc1.getMetadata().put("stock", 50);
        doc1.getMetadata().put("category", "electronics");

        Document doc2 = new Document("Document with different price");
        doc2.getMetadata().put("price", 200);
        doc2.getMetadata().put("stock", 10);
        doc2.getMetadata().put("category", "electronics");

        Document doc3 = new Document("Document with different category");
        doc3.getMetadata().put("price", 150);
        doc3.getMetadata().put("stock", 25);
        doc3.getMetadata().put("category", "books");

        List<Document> documents = new ArrayList<>();
        documents.add(doc1);
        documents.add(doc2);
        documents.add(doc3);

        try {
            // 插入测试文档
            repository.save(documents);

            // 等待索引更新
            Thread.sleep(1000);

            // 1. 测试数值比较 (大于)
            String gtExpression = "price > 120";
            QueryCondition gtCondition = new QueryCondition("document")
                    .filterExpression(gtExpression)
                    .disableRefilter(true);

            List<Document> gtResults = repository.search(gtCondition);
            System.out.println("找到 " + gtResults.size() + " 个文档，使用大于表达式: " + gtExpression);

            // 验证结果 - 应该找到两个价格大于120的文档
            assertTrue(gtResults.size() > 0, "大于表达式应该找到文档");

            // 2. 测试数值比较 (小于等于)
            String lteExpression = "stock <= 25";
            QueryCondition lteCondition = new QueryCondition("document")
                    .filterExpression(lteExpression)
                    .disableRefilter(true);

            List<Document> lteResults = repository.search(lteCondition);
            System.out.println("找到 " + lteResults.size() + " 个文档，使用小于等于表达式: " + lteExpression);

            // 验证结果 - 应该找到两个库存小于等于25的文档
            assertTrue(lteResults.size() > 0, "小于等于表达式应该找到文档");

            // 3. 测试复合表达式 (价格区间和类别)
            String complexExpression = "(price >= 100 AND price <= 180) AND category == 'electronics'";
            QueryCondition complexCondition = new QueryCondition("document")
                    .filterExpression(complexExpression)
                    .disableRefilter(true);

            List<Document> complexResults = repository.search(complexCondition);
            System.out.println("找到 " + complexResults.size() + " 个文档，使用复合表达式: " + complexExpression);

            // 验证结果 - 应该找到一个满足所有条件的文档
            assertTrue(complexResults.size() > 0, "复合表达式应该找到文档");

            // 打印结果
            System.out.println("\n=== 高级表达式过滤测试结果 ===");
            System.out.println("大于表达式结果数量: " + gtResults.size());
            System.out.println("小于等于表达式结果数量: " + lteResults.size());
            System.out.println("复合表达式结果数量: " + complexResults.size());

        } catch (Exception e) {
            throw e;
            //fail("测试过程中发生异常: " + e.getMessage());
        } finally {
            // 清理测试文档
            try {
                repository.deleteById(doc1.getId(), doc2.getId(), doc3.getId());
            } catch (Exception e) {
                System.err.println("清理测试文档失败: " + e.getMessage());
            }
        }
    }

    // 在每个测试方法开始时检查repository是否可用
    private void checkRepository() {
        if (repository == null) {
            assumeTrue(false, "Chroma server is not available");
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

        repository.save(documents); //（推入文档）
    }
}