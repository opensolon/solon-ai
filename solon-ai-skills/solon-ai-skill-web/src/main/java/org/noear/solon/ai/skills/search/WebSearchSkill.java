/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.skills.search;

import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.annotation.Param;
import org.noear.solon.lang.Preview;
import org.noear.solon.net.http.HttpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 联网搜索技能：支持多驱动自适应 (Serper, Bing, Baidu)
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class WebSearchSkill extends AbsSkill {
    private static final Logger LOG = LoggerFactory.getLogger(WebSearchSkill.class);

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
            LOG.error("Web search failed [{}]: {}", driver, query, e);
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
            // 注意：AppBuilder 的端点通常带有 /v2/ 前缀
            return HttpUtils.http("https://gw.alinesno.com/api/baidu/appbuilder/v2/ai_search/web_search")
                    .header("Authorization", "Bearer " + k)
                    .header("Content-Type", "application/json")
                    // 百度 AppBuilder 接收的是 messages 数组，且 top_k 在 resource_type_filter 中定义
                    .bodyJson(new ONode()
                            .set("messages", new ONode().add(new ONode().set("role", "user").set("content", q)))
                            .set("resource_type_filter", new ONode().add(new ONode().set("type", "web").set("top_k", n)))
                            .toJson())
                    .post();
        }

        public java.util.List<SearchResult> parseResults(ONode root) {
            // 百度 AppBuilder 的返回通常在 cells 数组或特定的 search_results 结构中
            // 这里建议根据你实际对接的百度云具体产品线（千帆 vs AppBuilder）微调路径
            return root.get("search_results").getArrayUnsafe().stream()
                    .map(i -> new SearchResult(
                            i.get("title").getString(),
                            i.get("url").getString(),
                            i.get("content").getString())) // 百度通常返回 content 或 snippet
                    .collect(java.util.stream.Collectors.toList());
        }
    };
}