package features.ai.repo.chroma;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.embedding.EmbeddingModel;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.repository.ChromaRepository;
import org.noear.solon.ai.rag.splitter.TokenSizeTextSplitter;
import org.noear.solon.ai.rag.util.QueryCondition;
import org.noear.solon.net.http.HttpUtils;

/**
 * ChromaRepository 测试类
 *
 * @author 小奶奶花生米
 * @since 3.1
 */
public class ChromaRepositoryTest {
    private ChromaRepository repository;
    private static final String SERVER_URL = "http://localhost:8000";
    private static final String COLLECTION_NAME = "test_collection";
    final String apiUrl = "http://192.168.1.16:11434/api/embed";
    final String provider = "ollama";
    final String model = "bge-m3";

    @BeforeEach
    public void setup() {
        // 创建一个简单的 EmbeddingModel 实现用于测试
        EmbeddingModel embeddingModel = EmbeddingModel.of(apiUrl).provider(provider).model(model).build();

        try {
            // 使用随机集合名称，避免冲突
            String uniqueCollectionName = COLLECTION_NAME + "_" + System.currentTimeMillis();
            System.out.println("Using unique collection name: " + uniqueCollectionName);
            
            repository = new ChromaRepository(embeddingModel, SERVER_URL, uniqueCollectionName);
            
            // 检查服务是否健康
            if (!repository.isHealthy()) {
                System.err.println("Chroma server is not healthy, skipping tests");
                return;
            }

            // 初始化测试数据
            load(repository, "https://solon.noear.org/article/about?format=md");
            load(repository, "https://h5.noear.org/more.htm");
            load(repository, "https://h5.noear.org/readme.htm");
            Thread.sleep(1000);
        } catch (Exception e) {
            System.err.println("Failed to setup test: " + e.getMessage());
            e.printStackTrace();
            // 不抛出异常，让测试继续运行，但会跳过依赖repository的测试
        }
    }

    // 在每个测试方法开始时检查repository是否可用
    private void checkRepository() {
        if (repository == null || !repository.isHealthy()) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Chroma server is not available");
        }
    }

    @Test
    public void testSearch() throws IOException {
        checkRepository();

        try {
            // 测试基本搜索
            QueryCondition condition = new QueryCondition("solon");
            List<Document> results = repository.search(condition);
            assertFalse(results.isEmpty(), "应该找到包含solon的文档");

            // 测试带过滤器的搜索
            condition = new QueryCondition("solon")
                    .filterExpression("url LIKE 'noear.org'");
            results = repository.search(condition);
            assertFalse(results.isEmpty(), "应该找到noear.org域名下的文档");
            assertTrue(results.get(0).getUrl().contains("noear.org"), "文档URL应该包含noear.org");

            // 打印结果
            for (Document doc : results) {
                System.out.println(doc.getId() + ":" + doc.getScore() + ":" + doc.getUrl() + "【" + doc.getContent() + "】");
            }

        } catch (Exception e) {
            fail("测试过程中发生异常: " + e.getMessage());
        }
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
            e.printStackTrace();
            fail("测试过程中发生异常: " + e.getMessage());
        }
    }

    @Test
    public void testScoreOutput() throws IOException {
        checkRepository();

        try {
            // 执行搜索查询
            QueryCondition condition = new QueryCondition("solon");
            List<Document> results = repository.search(condition);

            // 验证结果不为空
            assertFalse(results.isEmpty(), "搜索结果不应为空");

            // 验证每个文档都有评分
            for (Document doc : results) {
                // 检查评分是否存在
                Object scoreObj = doc.getMetadata().get("score");
                assertNotNull(scoreObj, "文档应该包含评分信息");

                // 检查评分是否为数值类型
                assertTrue(scoreObj instanceof Number, "评分应该是数值类型");

                // 检查评分是否为正数
                double score = ((Number) scoreObj).doubleValue();
                assertTrue(score >= 0, "文档评分应该是非负数");

                System.out.println("Document ID: " + doc.getId() + ", Score: " + score);
            }

            // 验证评分排序（如果有多个结果）
            if (results.size() > 1) {
                double firstScore = ((Number) results.get(0).getMetadata().get("score")).doubleValue();
                double secondScore = ((Number) results.get(1).getMetadata().get("score")).doubleValue();

                // 检查第一个结果的评分是否大于或等于第二个结果
                assertTrue(firstScore >= secondScore, "结果应该按评分降序排序");
            }

            // 打印所有结果的评分
            System.out.println("\n=== 评分测试结果 ===");
            for (Document doc : results) {
                double score = ((Number) doc.getMetadata().get("score")).doubleValue();
                System.out.println("ID: " + doc.getId());
                System.out.println("Score: " + score);
                System.out.println("Content: " + doc.getContent().substring(0, Math.min(50, doc.getContent().length())) + "...");
                System.out.println("---");
            }

        } catch (Exception e) {
            fail("测试过程中发生异常: " + e.getMessage());
        }
    }

    private void load(ChromaRepository repository, String url) throws IOException {
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
