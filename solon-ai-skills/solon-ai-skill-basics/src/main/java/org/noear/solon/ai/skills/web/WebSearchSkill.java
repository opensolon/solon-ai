package org.noear.solon.ai.skills.web;

import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.annotation.Param;
import org.noear.solon.net.http.HttpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 联网搜索技能：支持多驱动自适应 (Serper, Bing, Baidu)
 *
 * @author noear
 */
public class WebSearchSkill extends AbsSkill {
    private static final Logger log = LoggerFactory.getLogger(WebSearchSkill.class);

    private final String apiKey;
    private final SearchDriver driver;
    private int maxResults = 5;

    public WebSearchSkill(SearchDriver driver, String apiKey) {
        this.driver = driver;
        this.apiKey = apiKey;
    }

    public WebSearchSkill maxResults(int maxResults) {
        this.maxResults = maxResults;
        return this;
    }

    @Override
    public String name() { return "web_search"; }

    @Override
    public String description() { return "联网搜索专家：提供实时新闻、技术文档和资讯检索。"; }

    @ToolMapping(name = "search", description = "联网搜索，返回标题、链接和摘要")
    public String search(@Param("query") String query) {
        try {
            // 1. 发起请求
            String json = driver.executeRequest(query, maxResults, apiKey);
            ONode resNode = ONode.ofJson(json);
            StringBuilder sb = new StringBuilder();

            // 2. 解析结果（使用各驱动定义的提取规则）
            driver.parseResults(resNode).forEach(item -> {
                sb.append("### ").append(item.title).append("\n")
                        .append("- Link: ").append(item.link).append("\n")
                        .append("- Snippet: ").append(item.snippet).append("\n\n");
            });

            return sb.length() == 0 ? "未找到相关搜索结果。" : sb.toString();
        } catch (Exception e) {
            log.error("Web search failed [{}]: {}", driver, query, e);
            return "搜索服务暂时不可用: " + e.getMessage();
        }
    }

    // --- 驱动接口定义 ---
    public interface SearchDriver {
        String executeRequest(String query, int maxResults, String apiKey) throws Exception;
        java.util.List<SearchResult> parseResults(ONode root);
    }

    public static class SearchResult {
        public String title, link, snippet;
        public SearchResult(String t, String l, String s) { title=t; link=l; snippet=s; }
    }

    // --- 内置驱动实现：Serper (Google) ---
    public static final SearchDriver SERPER = new SearchDriver() {
        public String executeRequest(String q, int n, String k) throws Exception {
            return HttpUtils.http("https://google.serper.dev/search")
                    .header("X-API-KEY", k).header("Content-Type", "application/json")
                    .bodyJson(new ONode().set("q", q).set("num", n).toJson()).post();
        }
        public java.util.List<SearchResult> parseResults(ONode root) {
            return root.get("organic").getArrayUnsafe().stream()
                    .map(i -> new SearchResult(i.get("title").getString(), i.get("link").getString(), i.get("snippet").getString()))
                    .collect(java.util.stream.Collectors.toList());
        }
    };

    // --- 内置驱动实现：Bing ---
    public static final SearchDriver BING = new SearchDriver() {
        public String executeRequest(String q, int n, String k) throws Exception {
            return HttpUtils.http("https://api.bing.microsoft.com/v7.0/search")
                    .header("Ocp-Apim-Subscription-Key", k)
                    .data("q", q)
                    .data("count", String.valueOf(n))
                    .get();
        }
        public java.util.List<SearchResult> parseResults(ONode root) {
            return root.get("webPages").get("value").getArrayUnsafe().stream()
                    .map(i -> new SearchResult(i.get("name").getString(), i.get("url").getString(), i.get("snippet").getString()))
                    .collect(java.util.stream.Collectors.toList());
        }
    };

    // --- 内置驱动实现：Baidu (百度智能云版) ---
    public static final SearchDriver BAIDU = new SearchDriver() {
        public String executeRequest(String q, int n, String k) throws Exception {
            // 百度 API 通常需要 access_token 或特殊的 Authorization 签名
            // 这里演示标准的 RESTful POST 结构
            return HttpUtils.http("https://api.baidu.com/rpc/2.0/cloud_search/v1/web")
                    .header("Authorization", "Bearer " + k)
                    .header("Content-Type", "application/json")
                    .bodyJson(new ONode().set("query", q).set("max_results", n).toJson())
                    .post();
        }
        public java.util.List<SearchResult> parseResults(ONode root) {
            // 百度返回通常在 data.results 数组下
            return root.get("data").get("results").getArrayUnsafe().stream()
                    .map(i -> new SearchResult(
                            i.get("title").getString(),
                            i.get("url").getString(),
                            i.get("content").getString())) // 百度摘要字段通常叫 content
                    .collect(java.util.stream.Collectors.toList());
        }
    };
}