package org.noear.solon.ai.rag.repository;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.embedding.EmbeddingModel;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.repository.opensearch.MetadataField;
import org.noear.solon.ai.rag.splitter.TokenSizeTextSplitter;
import org.noear.solon.ai.rag.util.QueryCondition;
import org.noear.solon.net.http.HttpUtils;
import org.noear.solon.test.SolonTest;
import org.opensearch.client.RequestOptions;
import org.opensearch.client.RestClient;
import org.opensearch.client.RestClientBuilder;
import org.opensearch.client.RestHighLevelClient;
import org.opensearch.client.indices.GetIndexRequest;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OpenSearchRepository 测试类
 *
 * @author 小奶奶花生米
 * @since 3.1
 */
@SolonTest
public class OpenSearchRepositoryTest {
    private OpenSearchRepository repository;
    private RestHighLevelClient client;
    private static final String TEST_INDEX = "test_docs";
    final String apiUrl = "http://192.168.1.16:11434/api/embed";
    final String provider = "ollama";
    final String model = "bge-m3";

    private String host = "localhost";
    private int port = 9200;
    private String scheme = "http";

    @BeforeEach
    public void setup() throws IOException, InterruptedException {
        // 创建客户端
        final CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        // 如果需要认证，请取消注释并设置用户名和密码
         credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials("admin", "xnnhsM_1234543"));

        RestClientBuilder builder = RestClient.builder(new HttpHost(host, port, scheme));
        if (credentialsProvider.getCredentials(AuthScope.ANY) != null) {
            builder.setHttpClientConfigCallback(new RestClientBuilder.HttpClientConfigCallback() {
                @Override
                public HttpAsyncClientBuilder customizeHttpClient(HttpAsyncClientBuilder httpClientBuilder) {
                    return httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
                }
            });
        }

        client = new RestHighLevelClient(builder);


        // 创建嵌入模型
        EmbeddingModel embeddingModel = EmbeddingModel.of(apiUrl).provider(provider).model(model).build();

        // 创建元数据字段定义
        List<MetadataField> metadataFields = new ArrayList<>();
        metadataFields.add(MetadataField.keyword("url"));
        metadataFields.add(MetadataField.keyword("category"));
        metadataFields.add(MetadataField.numeric("priority"));
        metadataFields.add(MetadataField.text("content_summary"));
        metadataFields.add(MetadataField.keyword("title"));
        metadataFields.add(MetadataField.date("created_at"));
        metadataFields.add(MetadataField.numeric("year"));
        metadataFields.add(MetadataField.numeric("price"));
        metadataFields.add(MetadataField.numeric("stock"));

        // 使用Builder模式创建Repository
        repository = OpenSearchRepository.builder(embeddingModel, client)
                .indexName(TEST_INDEX)
                .metadataFields(metadataFields)
                .build();

        // 初始化仓库
        repository.initRepository();

        // 初始化测试数据
        load(repository, "https://solon.noear.org/article/about?format=md");
        load(repository, "https://h5.noear.org/readme.htm");
        Thread.sleep(1000); // 等待索引刷新
    }

    @AfterEach
    public void tearDown() throws IOException {
        if (repository != null) {
            repository.dropRepository();
        }

        if (client != null) {
            client.close();
        }
    }

    @Test
    public void testSearch() throws IOException {
        try {

            // 测试基本搜索（默认使用向量检索，knn精确检索）
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

            for (Document doc : results) {
                System.out.println(doc.getId() + ":" + doc.getScore() + ":" + doc.getUrl() + "【" + doc.getContent() + "】");
            }

        } catch (Exception e) {
            fail("测试过程中发生异常: " + e.getMessage(), e);
        }
    }

    @Test
    public void testRemove() throws IOException {
        // 准备并存储测试文档
        Document doc = new Document("Document to be removed");
        doc.metadata("category", "removed");

        List<Document> documents = new ArrayList<>();
        documents.add(doc);
        repository.insert(documents);

        // 等待索引刷新
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            // 忽略中断异常
        }

        // 验证文档已存储
        assertTrue(repository.exists(doc.getId()), "文档应该已被存储");

        // 删除文档
        repository.delete(doc.getId());

        // 等待索引刷新
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            // 忽略中断异常
        }

        // 验证文档已被删除
        assertFalse(repository.exists(doc.getId()), "文档应该已被删除");
    }

    @Test
    public void testSearchWithLimit() throws IOException {
        // 测试限制返回数量
        QueryCondition condition = new QueryCondition("solon")
                .limit(2);  // 限制只返回2条结果
        List<Document> results = repository.search(condition);

        assertEquals(2, results.size(), "应该只返回2条文档");

        // 打印结果
        System.out.println("\n=== 限制结果数量测试 ===");
        for (Document doc : results) {
            System.out.println("ID: " + doc.getId() + ", Score: " + doc.getScore());
            System.out.println("URL: " + doc.getUrl());
            System.out.println("Content: " + doc.getContent().substring(0, Math.min(50, doc.getContent().length())) + "...");
            System.out.println("---");
        }
    }

    @Test
    public void testScoreOutput() throws IOException {
        // 执行搜索查询
        QueryCondition condition = new QueryCondition("solon");
        List<Document> results = repository.search(condition);

        // 验证结果不为空
        assertFalse(results.isEmpty(), "搜索结果不应为空");

        // 验证每个文档都有评分
        for (Document doc : results) {
            assertNotNull(doc.getScore(), "文档应该包含评分信息");
            assertTrue(doc.getScore() > 0, "文档评分应该是正数");
        }

        // 验证评分排序（如果有多个结果）
        if (results.size() > 1) {
            double firstScore = results.get(0).getScore();
            double secondScore = results.get(1).getScore();
            assertTrue(firstScore >= secondScore, "结果应该按评分降序排序");
        }

        // 打印结果
        System.out.println("\n=== 评分测试结果 ===");
        for (Document doc : results) {
            System.out.println("ID: " + doc.getId() + ", Score: " + doc.getScore());
            System.out.println("URL: " + doc.getUrl());
            System.out.println("Content: " + doc.getContent().substring(0, Math.min(50, doc.getContent().length())) + "...");
            System.out.println("---");
        }
    }

    @Test
    public void testExpressionFilter() throws IOException, InterruptedException {

        // 创建测试文档
        Document doc1 = new Document("Solon framework introduction");
        doc1.metadata("title", "solon");
        doc1.metadata("category", "framework");

        Document doc2 = new Document("Java configuration settings");
        doc2.metadata("title", "设置");
        doc2.metadata("category", "tutorial");

        Document doc3 = new Document("Spring framework overview");
        doc3.metadata("title", "spring");
        doc3.metadata("category", "framework");

        List<Document> documents = new ArrayList<>();
        documents.add(doc1);
        documents.add(doc2);
        documents.add(doc3);

        // 插入测试文档
        repository.insert(documents);

        // 等待索引更新
        Thread.sleep(1000);

        try {
            // 1. 测试 OR 表达式
            String orExpression = "title == 'solon' OR title == '设置'";
            QueryCondition orCondition = new QueryCondition("framework")
                    .filterExpression(orExpression)
                    .disableRefilter(true);

            List<Document> orResults = repository.search(orCondition);
            System.out.println("找到 " + orResults.size() + " 个文档，使用 OR 表达式: " + orExpression);

            // 验证结果 - 应该找到两个文档 (solon 和 设置)
            assertTrue(orResults.size() >= 1, "OR 表达式应该至少找到一个文档");

            // 2. 测试 AND 表达式
            String andExpression = "title == 'solon' AND category == 'framework'";
            QueryCondition andCondition = new QueryCondition("framework")
                    .filterExpression(andExpression)
                    .disableRefilter(true);

            List<Document> andResults = repository.search(andCondition);
            System.out.println("找到 " + andResults.size() + " 个文档，使用 AND 表达式: " + andExpression);

            // 验证结果 - 应该只找到一个文档 (solon && framework)
            assertTrue(andResults.size() >= 1, "AND 表达式应该至少找到一个文档");

            // 3. 测试简单过滤表达式
            String simpleExpression = "category == 'framework'";
            QueryCondition simpleCondition = new QueryCondition("framework")
                    .filterExpression(simpleExpression)
                    .disableRefilter(true);

            List<Document> simpleResults = repository.search(simpleCondition);
            System.out.println("找到 " + simpleResults.size() + " 个文档，使用简单表达式: " + simpleExpression);

            // 验证结果 - 应该找到两个 framework 类别的文档
            assertTrue(simpleResults.size() >= 1, "简单表达式应该至少找到一个文档");

            // 打印结果
            System.out.println("\n=== 表达式过滤测试结果 ===");
            System.out.println("OR 表达式结果数量: " + orResults.size());
            System.out.println("AND 表达式结果数量: " + andResults.size());
            System.out.println("简单表达式结果数量: " + simpleResults.size());

        } finally {
            // 清理测试文档
            repository.delete(doc1.getId(), doc2.getId(), doc3.getId());
        }
    }


    /**
     * 加载文档到存储库
     *
     * @param repository 存储库
     * @param url 文档URL
     * @throws IOException IO异常
     */
    private void load(OpenSearchRepository repository, String url) throws IOException {
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
