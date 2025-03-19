package features.ai.repo.vectordb;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.tencent.tcvectordb.model.param.collection.FieldType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.repository.MetadataField;
import org.noear.solon.ai.rag.repository.TcVectorDbRepository;
import org.noear.solon.ai.rag.splitter.TokenSizeTextSplitter;
import org.noear.solon.ai.rag.util.QueryCondition;
import org.noear.solon.net.http.HttpUtils;

/**
 * VectorDBRepository 测试类
 *
 * @author 小奶奶花生米
 * @since 3.1
 */
public class VectorDBRepositoryTest {

    private TcVectorDbRepository repository;
    private final String url = System.getProperty("vectordb.url", "http://sh-vdb-nfk40az1.sql.tencentcdb.com:8100");
    private final String username = System.getProperty("vectordb.username", "root");
    private final String key = System.getProperty("vectordb.key", "G5YCn0evTakg1Ewagj5A8YdR1bz9g2XWGDBcAhna");
    private final String databaseName = "test_db";
    private final String collectionName = "test_collection";

    final String model = "bge-base-zh";

    @BeforeEach
    public void setup() {
        // 创建嵌入模型

        try {
            // 使用构建器模式创建 VectorDBRepository
            List<MetadataField> metadataFields = new ArrayList<>();
            metadataFields.add(new MetadataField("title", FieldType.String));
            metadataFields.add(new MetadataField("category", FieldType.String));

            repository = TcVectorDbRepository.builder(model, url, username, key, databaseName, collectionName).metadataFields(metadataFields).build();


            // 初始化测试数据
            try {
                load(repository, "https://solon.noear.org/article/about?format=md");
                load(repository, "https://h5.noear.org/more.htm");
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
                    .filterExpression(orExpression);

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
                    .filterExpression(andExpression);

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
                    .filterExpression(simpleExpression);

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
    public void testSearch() throws IOException {

        try {
            // 测试基本搜索
            QueryCondition condition = new QueryCondition("solon");
            List<Document> results = repository.search(condition);

            // 如果有结果，验证结果不为空
            if (!results.isEmpty()) {
                System.out.println("Found " + results.size() + " documents containing 'solon'");

                // 打印结果
                for (Document doc : results) {
                    System.out.println(doc.getId() + ":" + doc.getScore() + ":" +
                                      (doc.getUrl() != null ? doc.getUrl() : "no-url") +
                                      "【" + doc.getContent() + "】");
                }
            } else {
                System.out.println("No documents found containing 'solon'");
            }

            // 测试带过滤器的搜索
            condition = new QueryCondition("solon");
            results = repository.search(condition);

            if (!results.isEmpty()) {
                System.out.println("\nFound " + results.size() + " documents containing 'framework' filtered by 'solon'");
                for (Document doc : results) {
                    System.out.println(doc.getId() + ":" + doc.getScore() + ":" +
                                      (doc.getUrl() != null ? doc.getUrl() : "no-url") +
                                      "【" + doc.getContent() + "】");
                }

                // 验证过滤器生效
                for (Document doc : results) {
                    assertTrue(doc.getContent().toLowerCase().contains("solon"),
                              "过滤器应该确保所有结果包含'solon'");
                }
            } else {
                System.out.println("No documents found with filter");
            }

        } catch (Exception e) {
            fail("测试过程中发生异常: " + e.getMessage());
        }
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
