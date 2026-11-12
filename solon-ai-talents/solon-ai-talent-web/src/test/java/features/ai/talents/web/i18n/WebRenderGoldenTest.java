package features.ai.talents.web.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.talent.AbsTalent;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.i18n.AiI18n;
import org.noear.solon.ai.i18n.testkit.GoldenFiles;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.client.McpClientProperties;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.ai.talents.crawler.WebCrawlerDriverTalent;
import org.noear.solon.ai.talents.search.WebSearchDriverTalent;
import org.noear.solon.ai.talents.web.CodeSearchTalent;
import org.noear.solon.ai.talents.web.WebfetchTalent;
import org.noear.solon.ai.talents.web.WebsearchTalent;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * web 才能对模型可见文本的渲染快照（描述、工具 schema、返回值与错误文案）
 *
 * <p>中文快照由改造前的代码生成，国际化后必须逐字不变；英文快照经人工审校后生成。</p>
 */
public class WebRenderGoldenTest {
    private McpClientProvider codeSearchClient;

    @AfterEach
    public void restore() throws Exception {
        if (codeSearchClient != null) {
            setCodeSearchClient(codeSearchClient);
        }
    }

    @Test
    public void testZh() throws Throwable {
        GoldenFiles.verify(renderOf(Prompt.of("hi"), null, "_zh"));
    }

    @Test
    public void testZhExplicit() throws Throwable {
        GoldenFiles.verify(renderOf(prompt(Locale.CHINA), "zh-CN", "_zh"));
    }

    @Test
    public void testEn() throws Throwable {
        GoldenFiles.verify(renderOf(prompt(Locale.ENGLISH), "en", "_en"));
    }

    static Prompt prompt(Locale locale) {
        Prompt prompt = Prompt.of("hi");
        prompt.locale(locale);
        return prompt;
    }

    /**
     * @param tag 工具上下文注入的 __locale（null 表示未注入，走不带 __locale 的旧方法，用默认语言）
     */
    Map<String, String> renderOf(Prompt prompt, String tag, String suffix) throws Throwable {
        Map<String, String> out = new LinkedHashMap<>();

        CodeSearchTalent codeSearch = new CodeSearchTalent().retryConfig(1);
        WebsearchTalent websearch = new WebsearchTalent().retryConfig(1);
        WebfetchTalent webfetch = new WebfetchTalent().retryConfig(1);

        WebSearchDriverTalent searchEmpty = new WebSearchDriverTalent(searchDriver(null), "k");
        WebSearchDriverTalent searchFail = new WebSearchDriverTalent(searchDriver("boom"), "k");
        WebCrawlerDriverTalent crawlFail = new WebCrawlerDriverTalent((url, key) -> {
            throw new IllegalStateException("boom");
        }, "k");

        put(out, "web_codesearch", suffix, codeSearch, prompt);
        put(out, "web_websearch", suffix, websearch, prompt);
        put(out, "web_webfetch", suffix, webfetch, prompt);
        put(out, "web_search_driver", suffix, searchEmpty, prompt);
        put(out, "web_crawler_driver", suffix, crawlFail, prompt);

        //CodeSearch：超时 / 出错（有文本、无文本）/ 无结果
        out.put("web_codesearch_timeout" + suffix, errorOf(codeSearch, mcp(null, new RuntimeException("Read timeout")), tag));
        out.put("web_codesearch_error" + suffix, errorOf(codeSearch, mcp(ToolResult.error("bad query"), null), tag));
        out.put("web_codesearch_error_empty" + suffix, errorOf(codeSearch, mcp(ToolResult.error(""), null), tag));
        setCodeSearchClient(mcp(new ToolResult(""), null));
        out.put("web_codesearch_empty" + suffix, tag == null ? codeSearch.codesearch("q", null, null) : codeSearch.codesearch("q", null, tag));

        //驱动型：无结果 / 服务异常 / URL 无效 / 抓取失败
        out.put("web_search_driver_empty" + suffix, tag == null ? searchEmpty.web_search("q", null) : searchEmpty.web_search("q", tag));
        out.put("web_search_driver_fail" + suffix, tag == null ? searchFail.web_search("q", null) : searchFail.web_search("q", tag));
        out.put("web_crawler_invalid" + suffix, tag == null ? crawlFail.crawl_url("ftp://x", null) : crawlFail.crawl_url("ftp://x", tag));
        out.put("web_crawler_fail" + suffix, tag == null ? crawlFail.crawl_url("https://x", null) : crawlFail.crawl_url("https://x", tag));
        return out;
    }

    private static void put(Map<String, String> out, String name, String suffix, AbsTalent talent, Prompt prompt) {
        out.put(name + "_description" + suffix, String.valueOf(talent.description(null)));
        out.put(name + "_instruction" + suffix, String.valueOf(talent.getInstruction(prompt)));
        out.put(name + "_tools", GoldenFiles.dumpTools(talent.getTools(prompt)));
    }

    private String errorOf(CodeSearchTalent talent, McpClientProvider client, String tag) throws Exception {
        setCodeSearchClient(client);
        try {
            return "<no error> " + (tag == null ? talent.codesearch("q", null, null) : talent.codesearch("q", null, tag));
        } catch (Throwable e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private void setCodeSearchClient(McpClientProvider client) throws Exception {
        Field f = CodeSearchTalent.class.getDeclaredField("mcpClient");
        f.setAccessible(true);
        if (codeSearchClient == null) {
            codeSearchClient = (McpClientProvider) f.get(null);
        }
        f.set(null, client);
    }

    private static McpClientProvider mcp(ToolResult result, RuntimeException error) {
        McpClientProperties props = new McpClientProperties();
        props.setChannel(McpChannel.STREAMABLE);
        props.setUrl("http://127.0.0.1:1/mcp");

        return new McpClientProvider(props) {
            @Override
            public ToolResult callTool(String name, Map<String, Object> args) {
                if (error != null) {
                    throw error;
                }
                return result;
            }
        };
    }

    private static WebSearchDriverTalent.SearchDriver searchDriver(String error) {
        return new WebSearchDriverTalent.SearchDriver() {
            @Override
            public String executeRequest(String query, int maxResults, String apiKey) {
                if (error != null) {
                    throw new IllegalStateException(error);
                }
                return "{}";
            }

            @Override
            public List<WebSearchDriverTalent.SearchResult> parseResults(ONode root) {
                return Collections.emptyList();
            }
        };
    }
}
