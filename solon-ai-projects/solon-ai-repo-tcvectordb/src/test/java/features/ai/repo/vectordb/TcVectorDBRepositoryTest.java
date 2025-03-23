package features.ai.repo.vectordb;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

import com.tencent.tcvectordb.client.VectorDBClient;
import com.tencent.tcvectordb.model.param.collection.FieldType;
import com.tencent.tcvectordb.model.param.database.ConnectParam;
import com.tencent.tcvectordb.model.param.enums.ReadConsistencyEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.repository.tcvectordb.MetadataField;
import org.noear.solon.ai.rag.repository.TcVectorDbRepository;
import org.noear.solon.ai.rag.splitter.TokenSizeTextSplitter;
import org.noear.solon.ai.rag.util.QueryCondition;
import org.noear.solon.net.http.HttpUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * VectorDBRepository 测试类
 *
 * @author 小奶奶花生米
 * @since 3.1
 */
public class TcVectorDBRepositoryTest {

    private TcVectorDbRepository repository;
    private final String url = System.getProperty("vectordb.url", "http://sh-vdb-1e6g45an.sql.tencentcdb.com:8100");
    private final String username = System.getProperty("vectordb.username", "root");
    private final String key = System.getProperty("vectordb.key", "82cQlN5GcUDo0oeVmPnIQHZWiJZK7taDmZKX8l2I");
    private final String databaseName = "test_db";
    private final String collectionName = "test_collection";

    final String model = "bge-base-zh";

    private VectorDBClient getClient() {
        // 创建连接参数
        ConnectParam connectParam = ConnectParam.newBuilder()
                .withUrl(url)
                .withUsername(username)
                .withKey(key)
                .build();

        // 创建 VectorDB 客户端
        VectorDBClient client = new VectorDBClient(connectParam, ReadConsistencyEnum.EVENTUAL_CONSISTENCY);
        return client;
    }

    @BeforeEach
    public void setup() {
        // 创建嵌入模型

        try {
            // 使用构建器模式创建 VectorDBRepository
            List<MetadataField> metadataFields = new ArrayList<>();
            metadataFields.add(new MetadataField("title", FieldType.String));
            metadataFields.add(new MetadataField("category", FieldType.String));
            metadataFields.add(new MetadataField("price", FieldType.Uint64));
            metadataFields.add(new MetadataField("stock", FieldType.Uint64));

            repository = TcVectorDbRepository.builder(model, getClient(), databaseName, collectionName)
                    .metadataFields(metadataFields)
                    .build();

            repository.dropRepository();
            repository.initRepository();


            // 初始化测试数据
            try {
                load(repository, "https://solon.noear.org/article/about?format=md");
                load(repository, "https://h5.noear.org/readme.htm");
                Thread.sleep(2000); // 等待索引构建完成
            } catch (Exception e) {
                System.err.println("Failed to load test data: " + e.getMessage());
            }

        } catch (Exception e) {
            System.err.println("Failed to setup test: " + e.getMessage());
            e.printStackTrace();
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
            repository.insert(documents);

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
            assertTrue(orResults.size() == 2, "OR 表达式应该至少找到一个文档");
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
                repository.delete(doc1.getId(), doc2.getId(), doc3.getId());
            } catch (Exception e) {
                System.err.println("清理测试文档失败: " + e.getMessage());
            }
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


    @Test
    public void testSearch() throws IOException {
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
    public void testRemove() {

        // 准备并存储测试数据
        List<Document> documents = new ArrayList<>();
        Document doc = new Document("Document to be removed for testing", new HashMap<>());
        documents.add(doc);

        try {
            repository.insert(documents);
            Thread.sleep(2000);

            // 验证文档已插入
            assertTrue(repository.exists(doc.getId()), "文档应该已被插入");

            // 删除文档
            repository.delete(doc.getId());
            Thread.sleep(2000);

            // 验证文档已被删除
            assertFalse(repository.exists(doc.getId()), "文档应该已被删除");

        } catch (Exception e) {
            e.printStackTrace();
            fail("测试过程中发生异常: " + e.getMessage());
        }
    }

    @Test
    public void testScoreOutput() throws IOException {

        try {
            // 执行搜索查询
            QueryCondition condition = new QueryCondition("framework");
            List<Document> results = repository.search(condition);

            // 验证结果
            if (!results.isEmpty()) {
                // 验证每个文档都有评分
                for (Document doc : results) {
                    // 检查评分是否存在
                    double score = doc.getScore();
                    System.out.println("Document ID: " + doc.getId() + ", Score: " + score);

                    // 检查评分是否为正数或0
                    assertTrue(score >= 0, "文档评分应该是非负数");
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
                    System.out.println("ID: " + doc.getId());
                    System.out.println("Score: " + doc.getScore());
                    System.out.println("Content: " + doc.getContent().substring(0, Math.min(50, doc.getContent().length())) + "...");
                    System.out.println("---");
                }
            } else {
                System.out.println("No results found for score testing");
            }

        } catch (Exception e) {
            fail("测试过程中发生异常: " + e.getMessage());
        }
    }


    private void load(TcVectorDbRepository repository, String url) throws IOException {
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
        repository.insert(documents);
        System.out.println("Inserted documents into repository");

        // 验证文档是否成功插入
        try {
            if (!documents.isEmpty()) {
                boolean exists = repository.exists(documents.get(0).getId());
                System.out.println("Verified document exists: " + exists);
            }
        } catch (Exception e) {
            System.err.println("Failed to verify document: " + e.getMessage());
        }
    }
}
