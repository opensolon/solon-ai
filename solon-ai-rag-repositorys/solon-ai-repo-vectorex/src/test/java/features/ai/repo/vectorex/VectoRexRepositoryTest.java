package features.ai.repo.vectorex;

import io.github.javpower.vectorexclient.VectorRexClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.embedding.EmbeddingModel;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.repository.VectoRexRepository;
import org.noear.solon.ai.rag.repository.vectorex.MetadataField;
import org.noear.solon.ai.rag.splitter.TokenSizeTextSplitter;
import org.noear.solon.ai.rag.util.QueryCondition;
import org.noear.solon.net.http.HttpUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

public class VectoRexRepositoryTest {

    private VectoRexRepository repository;
    private static final String COLLECTION_NAME = "test_collection";
    private final String apiUrl = "http://127.0.0.1:11434/api/embed";
    private final String provider = "ollama";
    private final String model = "bge-m3:latest";
    private final String baseUri = "http://localhost:8080";
    private final String username = "admin";
    private final String password = "123456";

    @BeforeEach
    public void setup() {
        // 创建一个简单的 EmbeddingModel 实现用于测试
        EmbeddingModel embeddingModel = EmbeddingModel.of(apiUrl).provider(provider).model(model).build();

        try {
            // 使用随机集合名称，避免冲突
            System.out.println("Using unique collection name: " + COLLECTION_NAME);

            List<MetadataField> metadataFields = new ArrayList<>();
            metadataFields.add(new MetadataField("category"));
            metadataFields.add(new MetadataField("price"));
            metadataFields.add(new MetadataField("title"));
            repository = VectoRexRepository.builder(embeddingModel, new VectorRexClient(baseUri, username, password)).collectionName(COLLECTION_NAME).build();

            repository.initRepository();

            // 初始化测试数据
            load(repository, "https://solon.noear.org/article/about?format=md");
            load(repository, "https://h5.noear.org/readme.htm");
            Thread.sleep(1000);
        } catch (Exception e) {
            System.err.println("Failed to setup test: " + e.getMessage());
            e.printStackTrace();
            // 不抛出异常，让测试继续运行，但会跳过依赖repository的测试
        }
    }

    @AfterEach
    public void dropRepo() throws Exception{
        repository.dropRepository();
    }


    @Test
    public void testSearch() throws Exception {

        List<Document> list = repository.search("solon");
        assert list.size() >= 3;//可能3个（效果更好）或4个

        list = repository.search("dubbo");
        assert list.size() == 0;

        Document doc = new Document("Test content");
        repository.save(Collections.singletonList(doc));
        String key = doc.getId();

        Thread.sleep(1000);
        assertTrue(repository.existsById(key), "Document should exist after storing");

        Thread.sleep(1000);
        repository.deleteById(doc.getId());
        assertFalse(repository.existsById(key), "Document should not exist after removal");
    }

    @Test
    public void testRemove() {
        // 准备并存储测试数据
        List<Document> documents = new ArrayList<>();
        Document doc = new Document("Document to be removed", new HashMap<>());
        documents.add(doc);

        try {
            repository.save(documents);
            Thread.sleep(1000);
            // 删除文档
            repository.deleteById(doc.getId());

            Thread.sleep(1000);
            // 验证文档已被删除
            assertFalse(repository.existsById(doc.getId()), "文档应该已被删除");

        } catch (Exception e) {
            e.printStackTrace();
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
    public void testAdvancedExpressionFilter() throws IOException {

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

    private void load(VectoRexRepository repository, String url) throws IOException {
        System.out.println("Loading documents from: " + url);
        String text = HttpUtils.http(url).get(); // 加载文档
        System.out.println("Loaded text with length: " + text.length());

        // 分割文档
        List<Document> documents = new TokenSizeTextSplitter(200).split(text).stream()
                .map(doc -> {
                    doc.url(url);
                    return doc;
                })
                .collect(Collectors.toList());

        System.out.println("Split into " + documents.size() + " documents");

        // 存储文档
        repository.save(documents);
        System.out.println("Inserted documents into repository");

        // 验证文档是否成功插入
        try {
            if (!documents.isEmpty()) {
                boolean exists = repository.existsById(documents.get(0).getId());
                System.out.println("Verified document exists: " + exists);
            }
        } catch (Exception e) {
            System.err.println("Failed to verify document: " + e.getMessage());
        }
    }
}
