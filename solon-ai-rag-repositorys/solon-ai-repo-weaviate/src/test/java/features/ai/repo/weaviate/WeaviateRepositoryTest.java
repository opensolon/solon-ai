package features.ai.repo.weaviate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.noear.solon.ai.embedding.EmbeddingModel;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.repository.WeaviateRepository;
import org.noear.solon.ai.rag.repository.weaviate.MetadataField;
import org.noear.solon.ai.rag.repository.weaviate.FieldType;
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

/**
 * WeaviateRepository 集成测试
 *
 * 说明：
 * - 依赖本地或远程 Weaviate 实例；
 * - 默认连接 http://localhost:8080，gRPC 端口 50051；
 * - 默认使用 ollama 的 bge-m3 模型做外部向量。
 *
 * 建议在本地先通过 docker-compose 启动 weaviate 再运行本测试。
 *
 * @author 小奶奶花生米
 * @since 3.1
 */
@EnabledIfEnvironmentVariable(named = "WEAVIATE_TEST_ENABLED", matches = "true")
public class WeaviateRepositoryTest {

    private WeaviateRepository repository;

    private static final String SERVER_BASE_URL = "http://localhost:8080";

    private static final String COLLECTION_NAME = "solon_ai_test";

    private static final String EMBEDDING_API_URL = "http://localhost:11434/api/embed";
    private static final String EMBEDDING_PROVIDER = "ollama";
    private static final String EMBEDDING_MODEL = "bge-m3";

    @BeforeEach
    public void setup() {
        try {
            // 创建外部向量模型
            EmbeddingModel embeddingModel = EmbeddingModel.of(EMBEDDING_API_URL)
                    .provider(EMBEDDING_PROVIDER)
                    .model(EMBEDDING_MODEL)
                    .build();

            // 通过 WeaviateRepository 的 Builder 配置连接参数（REST 调用）
            repository = WeaviateRepository.builder(embeddingModel, SERVER_BASE_URL)
                    .collectionName(COLLECTION_NAME+"_"+System.currentTimeMillis())
                    .addMetadataField(new MetadataField("title", FieldType.STRING))
                    .addMetadataField(new MetadataField("category", FieldType.STRING))
                    .addMetadataField(new MetadataField("price", FieldType.INTEGER))
                    .addMetadataField(new MetadataField("stock", FieldType.INTEGER))
                    .build();

            // 预加载两篇文档，便于后续检索测试
            load(repository, "https://solon.noear.org/article/about?format=md");
            load(repository, "https://h5.noear.org/readme.htm");

            Thread.sleep(1000L);
        } catch (Exception e) {
            System.err.println("Failed to setup WeaviateRepositoryTest: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void assumeRepositoryReady() {
        org.junit.jupiter.api.Assumptions.assumeTrue(repository != null, "Weaviate repository is not initialized");
    }

    /**
     * 简单的端到端搜索、保存、删除测试
     */
    @Test
    public void testSearchAndCrud() throws IOException {
        assumeRepositoryReady();

        // 基本搜索
        List<Document> list = repository.search(new QueryCondition("solon"));
        assertNotNull(list);

        // 插入文档并检查存在性
        Document doc = new Document("Test content for weaviate");
        repository.save(Collections.singletonList(doc));
        String key = doc.getId();

        assertTrue(repository.existsById(key), "Document should exist after storing");

        // 删除并再次检查
        repository.deleteById(key);
        assertFalse(repository.existsById(key), "Document should not exist after removal");
    }

    /**
     * 删除测试
     */
    @Test
    public void testRemove() {
        assumeRepositoryReady();

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

    /**
     * 评分输出测试：保证 search 返回的文档都有 score 字段
     */
    @Test
    public void testScoreOutput() throws IOException {
        assumeRepositoryReady();

        try {
            QueryCondition condition = new QueryCondition("solon").disableRefilter(true);
            List<Document> results = repository.search(condition);

            assertFalse(results.isEmpty(), "搜索结果不应为空");

            for (Document doc : results) {
                assertTrue(doc.getScore() >= 0, "文档评分应该是非负数");
            }

            if (results.size() > 1) {
                double firstScore = results.get(0).getScore();
                double secondScore = results.get(1).getScore();
                assertTrue(firstScore >= secondScore, "结果应该按评分降序排序");
            }
        } catch (Exception e) {
            fail("测试过程中发生异常: " + e.getMessage());
        }
    }

    /**
     * 表达式过滤测试
     */
    @Test
    public void testExpressionFilter() throws IOException {
        assumeRepositoryReady();

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
     * 高级表达式过滤测试
     */
    @Test
    public void testAdvancedExpressionFilter() throws IOException {
        assumeRepositoryReady();

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

            // 验证结果 - 应该至少找到一个文档
            assertTrue(gtResults.size() > 0, "大于表达式应该找到文档");

            // 2. 测试数值比较 (小于等于)
            String lteExpression = "stock <= 25";
            QueryCondition lteCondition = new QueryCondition("document")
                    .filterExpression(lteExpression)
                    .disableRefilter(true);

            List<Document> lteResults = repository.search(lteCondition);
            System.out.println("找到 " + lteResults.size() + " 个文档，使用小于等于表达式: " + lteExpression);

            // 验证结果 - 应该至少找到一个文档
            assertTrue(lteResults.size() > 0, "小于等于表达式应该找到文档");

            // 3. 测试复合表达式 (价格区间和类别)
            String complexExpression = "(price >= 100 AND price <= 180) AND category == 'electronics'";
            QueryCondition complexCondition = new QueryCondition("document")
                    .filterExpression(complexExpression)
                    .disableRefilter(true);

            List<Document> complexResults = repository.search(complexCondition);
            System.out.println("找到 " + complexResults.size() + " 个文档，使用复合表达式: " + complexExpression);

            // 验证结果 - 应该至少找到一个文档
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

    private void load(WeaviateRepository repository, String url) throws IOException {
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

