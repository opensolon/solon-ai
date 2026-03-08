package features.ai;

import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.search.TavilyWebSearchRepository;
import org.noear.solon.ai.rag.search.tavily.ClientBuilder;
import org.noear.solon.ai.rag.search.tavily.TavilyClient;
import org.noear.solon.ai.rag.search.tavily.condition.CrawlCondition;
import org.noear.solon.ai.rag.search.tavily.condition.ExtractCondition;
import org.noear.solon.ai.rag.search.tavily.condition.MapCondition;
import org.noear.solon.ai.rag.search.tavily.condition.SearchCondition;
import org.noear.solon.ai.rag.search.tavily.document.*;
import org.noear.solon.ai.rag.search.tavily.repository.TavilySimpleSearchRepository;
import org.noear.solon.ai.rag.util.QueryCondition;
import org.noear.solon.test.SolonTest;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tavily API 集成测试
 *
 * <p>设置环境变量 TAVILY_API_KEY 后运行测试！！</p>
 *
 * @author shaoerkuai
 */
@SolonTest
public class TavilySearchTest {
    static final String API_KEY = System.getenv("TAVILY_API_KEY");

    private TavilyClient createClient() {
        return ClientBuilder.of(API_KEY).build();
    }

    private boolean skipIfNoKey() {
        if (API_KEY == null || API_KEY.isEmpty()) {
            System.out.println("Skip: TAVILY_API_KEY not set");
            return true;
        }
        return false;
    }

    @Test
    public void testSearchBasic() throws Exception {
        if (skipIfNoKey()) return;

        TavilySimpleSearchRepository repo = TavilySimpleSearchRepository.of(API_KEY).build();

        SearchCondition condition = new SearchCondition("Solon Java Framework")
                .maxResults(3);

        TavilySearchResponse response = repo.search(condition);

        assertNotNull(response);
        assertNotNull(response.getQuery());
        assertNotNull(response.getResults());
        assertFalse(response.getResults().isEmpty());
        assertTrue(response.getResponseTime() > 0);

        for (TavilySearchDocument doc : response.getResults()) {
            System.out.println("Title: " + doc.getTitle());
            System.out.println("URL: " + doc.getUrl());
            System.out.println("Score: " + doc.getScore());
            System.out.println("Content: " + doc.getContent().substring(0, Math.min(100, doc.getContent().length())));
            System.out.println("---");
        }
    }

    @Test
    public void testSearchAdvanced() throws Exception {
        if (skipIfNoKey()) return;

        TavilyClient client = createClient();

        SearchCondition condition = new SearchCondition("latest AI news")
                .searchDepth("advanced")
                .maxResults(5)
                .topic("news")
                .timeRange("week")
                .includeAnswer("basic")
                .includeImages(true)
                .includeFavicon(true);

        TavilySearchResponse response = client.search(condition);

        assertNotNull(response);
        System.out.println("Answer: " + response.getAnswer());
        System.out.println("Images count: " + (response.getImages() != null ? response.getImages().size() : 0));
        System.out.println("Results count: " + response.getResults().size());
        System.out.println("Response time: " + response.getResponseTime() + "s");
    }

    @Test
    public void testSearchWithDomainFilter() throws Exception {
        if (skipIfNoKey()) return;

        TavilyClient client = createClient();

        SearchCondition condition = new SearchCondition("Solon Java framework")
                .maxResults(3)
                .includeDomains(Arrays.asList("github.com", "stackoverflow.com"));

        TavilySearchResponse response = client.search(condition);

        assertNotNull(response);
        for (TavilySearchDocument doc : response.getResults()) {
            System.out.println("URL: " + doc.getUrl());
        }
    }

    @Test
    public void testSearchToDocuments() throws Exception {
        if (skipIfNoKey()) return;

        TavilyClient client = createClient();

        SearchCondition condition = new SearchCondition("Solon framework")
                .maxResults(3);

        TavilySearchResponse response = client.search(condition);
        List<Document> docs = response.toDocuments();

        assertNotNull(docs);
        assertFalse(docs.isEmpty());

        for (Document doc : docs) {
            assertNotNull(doc.getTitle());
            assertNotNull(doc.getUrl());
            assertNotNull(doc.getContent());
            System.out.println("Document: " + doc.getTitle() + " [score=" + doc.getScore() + "]");
        }
    }

    @Test
    public void testSearchViaRepository() throws Exception {
        if (skipIfNoKey()) return;

        TavilyWebSearchRepository repo = TavilyWebSearchRepository.of(API_KEY).build();

        QueryCondition condition = new QueryCondition("Java microservices").limit(3);
        List<Document> docs = repo.search(condition);

        assertNotNull(docs);
        assertFalse(docs.isEmpty());

        for (Document doc : docs) {
            System.out.println("Title: " + doc.getTitle());
            System.out.println("URL: " + doc.getUrl());
            System.out.println("---");
        }
    }

    @Test
    public void testExtract() throws Exception {
        if (skipIfNoKey()) return;

        TavilySimpleSearchRepository repo = TavilySimpleSearchRepository.of(API_KEY).build();

        ExtractCondition condition = new ExtractCondition("https://solon.noear.org")
                .format("markdown");

        TavilyExtractResponse response = repo.extract(condition);

        assertNotNull(response);
        assertNotNull(response.getResults());
        assertTrue(response.getResponseTime() > 0);

        for (TavilyExtractDocument doc : response.getResults()) {
            System.out.println("URL: " + doc.getUrl());
            System.out.println("Content length: " + (doc.getRawContent() != null ? doc.getRawContent().length() : 0));
            System.out.println("---");
        }

        // 测试转换为 Document
        List<Document> docs = response.toDocuments();
        assertFalse(docs.isEmpty());
    }

    @Test
    public void testExtractMultipleUrls() throws Exception {
        if (skipIfNoKey()) return;

        TavilyClient client = createClient();

        ExtractCondition condition = new ExtractCondition(
                "https://solon.noear.org",
                "https://github.com/noear/solon")
                .format("text");

        TavilyExtractResponse response = client.extract(condition);

        assertNotNull(response);
        System.out.println("Results: " + response.getResults().size());
        System.out.println("Failed: " + (response.getFailedResults() != null ? response.getFailedResults().size() : 0));
    }

    @Test
    public void testExtractWithQuery() throws Exception {
        if (skipIfNoKey()) return;

        TavilyClient client = createClient();

        ExtractCondition condition = new ExtractCondition("https://solon.noear.org")
                .query("what is Solon framework")
                .chunksPerSource(3);

        TavilyExtractResponse response = client.extract(condition);

        assertNotNull(response);
        assertFalse(response.getResults().isEmpty());
    }


    @Test
    public void testCrawl() throws Exception {
        if (skipIfNoKey()) return;

        TavilySimpleSearchRepository repo = TavilySimpleSearchRepository.of(API_KEY).build();

        CrawlCondition condition = new CrawlCondition("https://solon.noear.org/article/1242")
                .maxDepth(1)
                .limit(3)
                .format("markdown");

        TavilyCrawlResponse response = repo.crawl(condition);

        assertNotNull(response);
        assertNotNull(response.getBaseUrl());
        assertNotNull(response.getResults());
        assertTrue(response.getResponseTime() > 0);

        for (TavilyCrawlDocument doc : response.getResults()) {
            System.out.println("URL: " + doc.getUrl());
            System.out.println("Content length: " + (doc.getRawContent() != null ? doc.getRawContent().length() : 0));
            System.out.println("---");
        }

        // 测试转换为 Document
        List<Document> docs = response.toDocuments();
        assertFalse(docs.isEmpty());
    }

    @Test
    public void testCrawlWithPathFilter() throws Exception {
        if (skipIfNoKey()) return;

        TavilyClient client = createClient();

        CrawlCondition condition = new CrawlCondition("https://solon.noear.org")
                .maxDepth(1)
                .limit(5)
                .selectPaths(Arrays.asList("/doc/.*"))
                .allowExternal(false);

        TavilyCrawlResponse response = client.crawl(condition);

        assertNotNull(response);
        System.out.println("Base URL: " + response.getBaseUrl());
        System.out.println("Results count: " + response.getResults().size());
    }

    @Test
    public void testMap() throws Exception {
        if (skipIfNoKey()) return;

        TavilySimpleSearchRepository repo = TavilySimpleSearchRepository.of(API_KEY).build();

        MapCondition condition = new MapCondition("https://solon.noear.org")
                .maxDepth(1)
                .limit(10);

        TavilyMapResponse response = repo.map(condition);

        assertNotNull(response);
        assertNotNull(response.getBaseUrl());
        assertNotNull(response.getResults());
        assertTrue(response.getResponseTime() > 0);

        System.out.println("Base URL: " + response.getBaseUrl());
        System.out.println("Discovered " + response.getResults().size() + " URLs:");
        for (String url : response.getResults()) {
            System.out.println("  " + url);
        }
    }

    // ==================== Builder 测试 ====================

    @Test
    public void testClientBuilderRequired() {
        assertThrows(IllegalArgumentException.class, () -> {
            ClientBuilder.of(null);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            ClientBuilder.of("");
        });
    }

    @Test
    public void testClientBuilderDefaults() {
        // 仅验证构建不抛异常
        TavilyClient client = ClientBuilder.of("test-key").build();
        assertNotNull(client);
    }

    @Test
    public void testRepositoryBuilder() {
        TavilySimpleSearchRepository repo = TavilySimpleSearchRepository.of("test-key")
                .apiBase("https://custom.api.com")
                .build();
        assertNotNull(repo);
        assertNotNull(repo.getClient());
    }
}
