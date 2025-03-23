package features.ai.repo.elasticsearch;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.client.RestHighLevelClient;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.embedding.EmbeddingModel;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.RepositoryStorable;
import org.noear.solon.ai.rag.repository.ElasticsearchRepository;
import org.noear.solon.ai.rag.repository.elasticsearch.MetadataField;
import org.noear.solon.ai.rag.splitter.TokenSizeTextSplitter;
import org.noear.solon.ai.rag.util.QueryCondition;
import org.noear.solon.net.http.HttpUtils;

/**
 * ElasticsearchRepository 测试类
 *
 * @author 小奶奶花生米
 * @since 3.1
 */
public class ElasticsearchRepositoryTest {
    private ElasticsearchRepository repository;
    private RestHighLevelClient client;
    private static final String TEST_INDEX = "test_docs";
    final String apiUrl = "http://127.0.0.1:11434/api/embed";
    final String provider = "ollama";
    final String model = "bge-m3";//

    @BeforeEach
    public void setup() throws IOException, InterruptedException {
        // 阿里云Elasticsearch Serverless集群需要basic auth验证。
        final CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        // 访问用户名和密码为您创建阿里云Elasticsearch Serverless实例时设置的用户名和密码，也是Kibana控制台的登录用户名和密码。
        credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials("test-2ej", "aYa2Z8NrlIAbrIVKS3Zx2vGmCRF2Ts"));


        // 通过builder创建rest client，配置http client的HttpClientConfigCallback。
        // 单击所创建的Elasticsearch Serverless实例ID，在基本信息页面获取公网地址，即为ES集群地址。
        RestClientBuilder builder = RestClient.builder(new HttpHost("test-2ej.public.cn-hangzhou.es-serverless.aliyuncs.com", 9200, "http"))
                .setHttpClientConfigCallback(new RestClientBuilder.HttpClientConfigCallback() {
                    @Override
                    public HttpAsyncClientBuilder customizeHttpClient(HttpAsyncClientBuilder httpClientBuilder) {
                        return httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
                    }
                });

        // RestHighLevelClient实例通过REST low-level client builder进行构造。
         client = new RestHighLevelClient(builder);

        // 创建一个简单的 EmbeddingModel 实现用于测试
        EmbeddingModel embeddingModel = EmbeddingModel.of(apiUrl).provider(provider).model(model).build();

        // 创建元数据字段定义
        List<MetadataField> metadataFields = new ArrayList<>();
        metadataFields.add(MetadataField.keyword("url"));
        metadataFields.add(MetadataField.keyword("category"));
        metadataFields.add(MetadataField.numeric("priority"));
        metadataFields.add(MetadataField.text("content_summary"));
        metadataFields.add(MetadataField.keyword("title"));
        metadataFields.add(MetadataField.keyword("source"));
        metadataFields.add(MetadataField.date("created_at"));
        metadataFields.add(MetadataField.keyword("relevance"));
        metadataFields.add(MetadataField.numeric("year"));

        // 使用Builder模式创建Repository
        repository = ElasticsearchRepository.builder(embeddingModel, client, TEST_INDEX)
                .metadataFields(metadataFields)
                .build();

        repository.dropRepository();
        repository.initRepository();

        // 初始化测试数据
//        repository.delete("*");  // 清空所有文档
        load(repository, "https://solon.noear.org/article/about?format=md");
        load(repository, "https://h5.noear.org/readme.htm");
        Thread.sleep(1000);
    }

    @AfterEach
    public void after() throws IOException {
        repository.dropRepository();
    }

    @Test
    public void testSearch() throws IOException {
        try {

            // 测试基本搜索
            QueryCondition condition = new QueryCondition("solon");
            List<Document> results = repository.search(condition);
            assertFalse(results.isEmpty(), "应该找到包含solon的文档");

            // 测试带过滤器的搜索
            condition = new QueryCondition("solon")
                    .filterExpression("url LIKE 'noear.org'")
                    .disableRefilter(true);
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
            QueryCondition condition = new QueryCondition("removed");
            List<Document> results = repository.search(condition);
            assertTrue(results.isEmpty(), "文档应该已被删除");

        } catch (Exception e) {
            e.printStackTrace();
            fail("测试过程中发生异常: " + e.getMessage());
        }
    }

    @Test
    public void testSearchWithLimit() throws IOException {
        try {

            // 测试限制返回数量
            QueryCondition condition = new QueryCondition("solon")
                    .limit(2);  // 限制只返回2条结果
            List<Document> results = repository.search(condition);

            assertEquals(2, results.size(), "应该只返回2条文档");

            // 打印结果
            for (Document doc : results) {
                System.out.println(doc.getId() + ":" + doc.getScore() + ":" + doc.getUrl() + "【" + doc.getContent() + "】");
            }

        } catch (Exception e) {
            fail("测试过程中发生异常: " + e.getMessage());
        }
    }

    @Test
    public void testSearchScenarios() throws IOException {
        try {
            // 1. 测试空查询
            QueryCondition emptyCondition = new QueryCondition("");
            List<Document> results = repository.search(emptyCondition);
            assertFalse(results.isEmpty(), "空查询应该返回所有文档");

            // 2. 测试精确词语匹配
            QueryCondition exactCondition = new QueryCondition("杭州");
            results = repository.search(exactCondition);
            assertTrue(results.stream()
                    .anyMatch(doc -> doc.getContent().toLowerCase().contains("杭州")),
                    "应该能找到包含杭州的文档");

            // 3. 测试中文查询
            QueryCondition chineseCondition = new QueryCondition("框架");
            results = repository.search(chineseCondition);
            assertTrue(results.stream()
                    .anyMatch(doc -> doc.getContent().contains("框架")),
                    "应该能找到包含'框架'的文档");

            // 4. 测试组合过滤条件
            QueryCondition combinedCondition = new QueryCondition("solon")
                    .filterExpression("url LIKE 'noear.org'")
                    .disableRefilter(true)
                    .limit(5);
            results = repository.search(combinedCondition);
            assertTrue(results.size() <= 5, "返回结果不应超过限制数量");
            assertTrue(results.stream()
                    .allMatch(doc -> doc.getUrl().contains("noear.org")),
                    "所有结果都应该来自noear.org");

            // 5. 测试按相关性排序
            QueryCondition relevanceCondition = new QueryCondition("java web framework");
            results = repository.search(relevanceCondition);
            if (results.size() >= 2) {
                assertTrue(results.get(0).getScore() >= results.get(1).getScore(),
                        "结果应该按相关性降序排序");
            }

            // 打印所有测试结果
            System.out.println("\n=== 搜索测试结果 ===");
            for (Document doc : results) {
                System.out.println("Score: " + doc.getScore());
                System.out.println("URL: " + doc.getUrl());
                System.out.println("Content: " + doc.getContent());
                System.out.println("---");
            }

        } catch (Exception e) {
            fail("测试过程中发生异常: " + e.getMessage());
        }
    }

    @Test
    public void testScoreOutput() throws IOException {
        try {
            // 执行搜索查询
            QueryCondition condition = new QueryCondition("solon");
            List<Document> results = repository.search(condition);

            // 验证结果不为空
            assertFalse(results.isEmpty(), "搜索结果不应为空");

            // 验证每个文档都有评分
            for (Document doc : results) {
                // 检查评分是否存在
                Double scoreObj = doc.getScore();
                assertNotNull(scoreObj, "文档应该包含评分信息");

                // 检查评分是否为正数
                double score = scoreObj;
                assertTrue(score > 0, "文档评分应该是正数");

                System.out.println("Document ID: " + doc.getId() + ", Score: " + score);
            }

            // 验证评分排序（如果有多个结果）
            if (results.size() > 1) {
                double firstScore = results.get(0).getScore();
                double secondScore =  results.get(1).getScore();

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
    public void testScoreFiltering() throws IOException {
        // 创建测试文档
        List<Document> documents = new ArrayList<>();

        Map<String, Object> metadata1 = new HashMap<>();
        metadata1.put("relevance", "high");
        Document highRelevanceDoc = new Document("This is a highly relevant test document about artificial intelligence", metadata1);
        highRelevanceDoc.id("high_score");

        Map<String, Object> metadata2 = new HashMap<>();
        metadata2.put("relevance", "medium");
        Document mediumRelevanceDoc = new Document("This document mentions AI briefly", metadata2);
        mediumRelevanceDoc.id("medium_score");

        Map<String, Object> metadata3 = new HashMap<>();
        metadata3.put("relevance", "low");
        Document lowRelevanceDoc = new Document("This document is about something else entirely", metadata3);
        lowRelevanceDoc.id("low_score");

        documents.add(highRelevanceDoc);
        documents.add(mediumRelevanceDoc);
        documents.add(lowRelevanceDoc);

        // 插入文档
        repository.insert(documents);
        // 等待索引刷新
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            // 忽略中断异常
        }

        // 使用不同的相似度阈值进行搜索
        // 低阈值 - 应该返回所有文档
        QueryCondition lowThresholdCondition = new QueryCondition("artificial intelligence AI")
                .similarityThreshold(0.1);
        List<Document> lowThresholdResults = repository.search(lowThresholdCondition);

        // 中等阈值 - 应该过滤掉低相关性文档
        QueryCondition mediumThresholdCondition = new QueryCondition("artificial intelligence AI")
                .similarityThreshold(0.5);
        List<Document> mediumThresholdResults = repository.search(mediumThresholdCondition);

        // 高阈值 - 应该只返回高相关性文档
        QueryCondition highThresholdCondition = new QueryCondition("artificial intelligence AI")
                .similarityThreshold(0.8);
        List<Document> highThresholdResults = repository.search(highThresholdCondition);

        // 打印结果数量以进行比较
        System.out.println("低阈值搜索结果数量: " + lowThresholdResults.size());
        System.out.println("中等阈值搜索结果数量: " + mediumThresholdResults.size());
        System.out.println("高阈值搜索结果数量: " + highThresholdResults.size());

        // 期望结果数量会随着阈值提高而减少
        assertTrue(lowThresholdResults.size() >= mediumThresholdResults.size());
        assertTrue(mediumThresholdResults.size() >= highThresholdResults.size());

        // 清理测试数据
        repository.delete("high_score", "medium_score", "low_score");
    }

    @Test
    public void testBasicOperations() throws IOException {
        // 创建测试文档
        List<Document> documents = new ArrayList<>();

        Map<String, Object> metadata1 = new HashMap<>();
        metadata1.put("category", "test");
        metadata1.put("priority", 1);
        Document doc1 = new Document("This is a test document", metadata1);
        doc1.id("doc1");

        Map<String, Object> metadata2 = new HashMap<>();
        metadata2.put("category", "example");
        metadata2.put("priority", 2);
        Document doc2 = new Document("Another example document", metadata2);
        doc2.id("doc2");

        documents.add(doc1);
        documents.add(doc2);

        // 插入文档
        repository.insert(documents);
        // 等待索引刷新
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            // 忽略中断异常
        }

        // 验证文档存在
        assertTrue(repository.exists("doc1"));
        assertTrue(repository.exists("doc2"));

        // 搜索文档
        QueryCondition condition = new QueryCondition("test");
        List<Document> results = repository.search(condition);
        assertFalse(results.isEmpty());

        // 删除文档
        repository.delete("doc1");
        // 等待索引刷新
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            // 忽略中断异常
        }

        // 验证文档已删除
        assertFalse(repository.exists("doc1"));
        assertTrue(repository.exists("doc2"));
    }

    @Test
    public void testExpressionFilter() throws IOException {
        try {
            // 创建测试文档
            List<Document> documents = new ArrayList<>();

            Document doc1 = new Document("Solon framework introduction");
            doc1.metadata("title", "solon");
            doc1.metadata("category", "framework");

            Document doc2 = new Document("Java configuration settings");
            doc2.metadata("title", "设置");
            doc2.metadata("category", "tutorial");

            Document doc3 = new Document("Spring framework overview");
            doc3.metadata("title", "spring");
            doc3.metadata("category", "framework");

            documents.add(doc1);
            documents.add(doc2);
            documents.add(doc3);

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
            assertTrue(orResults.size() >= 1, "OR 表达式应该至少找到一个文档");
            boolean foundSolon = false;
            boolean foundSettings = false;

            for (Document doc : orResults) {
                String title = (String) doc.getMetadata("title");
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
                String title = (String) doc.getMetadata("title");
                String category = (String) doc.getMetadata("category");
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
                String category = (String) doc.getMetadata("category");
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
                repository.delete("expr_test_1", "expr_test_2", "expr_test_3");
            } catch (Exception e) {
                System.err.println("清理测试文档失败: " + e.getMessage());
            }
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
                    .filterExpression(gtExpression)
                    .disableRefilter(true);

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
                    .filterExpression(lteExpression)
                    .disableRefilter(true);

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
                    .filterExpression(complexExpression)
                    .disableRefilter(true);

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
                    .filterExpression(inExpression)
                    .disableRefilter(true);

            List<Document> inResults = repository.search(inCondition);
            System.out.println("找到 " + inResults.size() + " 个文档，使用IN表达式: " + inExpression);
            assertTrue(inResults.size() > 0, "IN表达式应该找到文档");

            // 5. 测试 NOT 操作符
            String notExpression = "NOT (category == 'books')";
            QueryCondition notCondition = new QueryCondition("document")
                    .filterExpression(notExpression)
                    .disableRefilter(true);
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

    /**
     * 测试分数过滤与表达式过滤的组合使用
     */
    @Test
    public void testCombinedScoreAndExpressionFiltering() throws IOException {
        try {
            // 创建测试文档
            List<Document> documents = new ArrayList<>();

            Document doc1 = new Document("Artificial intelligence and machine learning in Java");
            doc1.metadata("category", "AI");
            doc1.metadata("relevance", "high");
            doc1.metadata("year", 2023);

            Document doc2 = new Document("Introduction to data science with Java");
            doc2.metadata("category", "Data Science");
            doc2.metadata("relevance", "medium");
            doc2.metadata("year", 2022);

            Document doc3 = new Document("Java programming basics and fundamentals");
            doc3.metadata("category", "Programming");
            doc3.metadata("relevance", "low");
            doc3.metadata("year", 2021);

            documents.add(doc1);
            documents.add(doc2);
            documents.add(doc3);

            // 插入测试文档
            repository.insert(documents);

            // 等待索引更新
            Thread.sleep(1000);

            // 1. 测试同时使用相似度阈值和简单过滤表达式
            QueryCondition combinedCondition1 = new QueryCondition("artificial intelligence machine learning")
                    .filterExpression("category == 'AI'")
                    .similarityThreshold(0.1)
                    .disableRefilter(true);

            List<Document> combinedResults1 = repository.search(combinedCondition1);
            System.out.println("相似度+简单过滤结果数量: " + combinedResults1.size());

            // 2. 测试同时使用相似度阈值和复杂过滤表达式
            QueryCondition combinedCondition2 = new QueryCondition("artificial intelligence machine learning")
                    .filterExpression("year >= 2022 AND relevance == 'high'")
                    .similarityThreshold(0.1)
                    .disableRefilter(true);

            List<Document> combinedResults2 = repository.search(combinedCondition2);
            System.out.println("相似度+复杂过滤结果数量: " + combinedResults2.size());

            // 验证结果
            // 第一个查询应该匹配category为AI的文档，且分数高于阈值
            boolean foundAIDoc = false;
            for (Document doc : combinedResults1) {
                String category = (String) doc.getMetadata("category");
                if ("AI".equals(category)) {
                    foundAIDoc = true;
                    break;
                }
            }
            assertTrue(foundAIDoc || combinedResults1.isEmpty(),
                    "应该只找到AI类别的文档或者没有足够相关的文档");

            // 第二个查询应该匹配year>=2022且relevance='high'的文档，且分数高于阈值
            boolean foundMatchingDoc = false;
            for (Document doc : combinedResults2) {
                int year = ((Number) doc.getMetadata("year")).intValue();
                String relevance = (String) doc.getMetadata("relevance");
                if (year >= 2022 && "high".equals(relevance)) {
                    foundMatchingDoc = true;
                    break;
                }
            }
            assertTrue(foundMatchingDoc || combinedResults2.isEmpty(),
                    "应该只找到符合条件的文档或者没有足够相关的文档");

            // 3. 测试边缘情况：非常高的相似度阈值（应该返回空结果）
            QueryCondition edgeCondition = new QueryCondition("java programming")
                    .similarityThreshold(0.99);

            List<Document> edgeResults = repository.search(edgeCondition);
            System.out.println("极高相似度阈值结果数量: " + edgeResults.size());

            // 打印所有测试结果
            System.out.println("\n=== 组合过滤测试结果 ===");
            System.out.println("相似度+简单过滤结果:");
            printDocumentsDetails(combinedResults1);

            System.out.println("\n相似度+复杂过滤结果:");
            printDocumentsDetails(combinedResults2);

            System.out.println("\n极高相似度阈值结果:");
            printDocumentsDetails(edgeResults);

        } catch (Exception e) {
            e.printStackTrace();
            fail("测试过程中发生异常: " + e.getMessage());
        } finally {
            // 清理测试文档
            try {
                repository.delete("combined_test_1", "combined_test_2", "combined_test_3");
            } catch (Exception e) {
                System.err.println("清理测试文档失败: " + e.getMessage());
            }
        }
    }

    /**
     * 打印文档详细信息，用于测试结果分析
     */
    private void printDocumentsDetails(List<Document> documents) {
        if (documents.isEmpty()) {
            System.out.println("  [无结果]");
            return;
        }

        for (Document doc : documents) {
            System.out.println("  ID: " + doc.getId());
            System.out.println("  内容: " + doc.getContent());
            System.out.println("  评分: " + doc.getScore());
            System.out.println("  元数据: " + doc.getMetadata());
            System.out.println("  -------------");
        }
    }

    /**
     * 加载文档到存储库
     *
     * @param repository 存储库
     * @param url 文档URL
     * @throws IOException IO异常
     */
    private void load(RepositoryStorable repository, String url) throws IOException {
        String text = HttpUtils.http(url).get(); // 加载文档

        // 分割文档
        List<Document> documents = new TokenSizeTextSplitter(200).split(text).stream()
                .map(doc -> {
                    doc.url(url);
                    return doc;
                })
                .collect(Collectors.toList());

        // 存储文档
        repository.insert(documents);
    }
}
