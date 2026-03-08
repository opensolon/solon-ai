/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.rag.search.tavily;

import org.noear.snack4.ONode;
import org.noear.solon.ai.rag.search.tavily.condition.CrawlCondition;
import org.noear.solon.ai.rag.search.tavily.condition.ExtractCondition;
import org.noear.solon.ai.rag.search.tavily.condition.MapCondition;
import org.noear.solon.ai.rag.search.tavily.condition.SearchCondition;
import org.noear.solon.ai.rag.search.tavily.document.*;
import org.noear.solon.net.http.HttpUtils;
import org.noear.solon.net.http.impl.HttpSslSupplierAny;

import java.io.IOException;
import java.net.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Tavily API 客户端
 * @author shaoerkuai
 * @since 3.9.5
 * @see <a href="https://docs.tavily.com/documentation/api-reference/introduction">API Reference</a>
 */
public class TavilyClient {
    static final String DEFAULT_API_BASE = "https://api.tavily.com";

    private final String apiBase;
    private final String apiKey;
    private final Duration timeout;
    private final Proxy proxy;

    TavilyClient(String apiBase, String apiKey, Duration timeout, Proxy proxy) {
        this.apiBase = apiBase != null ? stripTrailingSlash(apiBase) : DEFAULT_API_BASE;
        this.apiKey = apiKey;
        this.timeout = timeout != null ? timeout : Duration.ofSeconds(60);
        this.proxy = proxy;
    }

    // ==================== Search ====================

    /**
     * 搜索（完整参数）
     *
     * @param condition 搜索条件
     * @return 完整搜索响应
     */
    public TavilySearchResponse search(SearchCondition condition) throws IOException {
        ONode reqNode = new ONode();
        reqNode.set("query", condition.getQuery());
        reqNode.set("max_results", condition.getMaxResults());

        setIfNotNull(reqNode, "search_depth", condition.getSearchDepth());
        setIfNotNull(reqNode, "topic", condition.getTopic());
        setIfNotNull(reqNode, "time_range", condition.getTimeRange());
        setIfNotNull(reqNode, "start_date", condition.getStartDate());
        setIfNotNull(reqNode, "end_date", condition.getEndDate());
        setIfNotNull(reqNode, "include_answer", condition.getIncludeAnswer());
        setIfNotNull(reqNode, "include_raw_content", condition.getIncludeRawContent());
        setIfNotNull(reqNode, "country", condition.getCountry());

        if (condition.getChunksPerSource() != null) {
            reqNode.set("chunks_per_source", condition.getChunksPerSource());
        }
        if (condition.isIncludeImages()) {
            reqNode.set("include_images", true);
        }
        if (condition.isIncludeImageDescriptions()) {
            reqNode.set("include_image_descriptions", true);
        }
        if (condition.isIncludeFavicon()) {
            reqNode.set("include_favicon", true);
        }
        if (condition.isAutoParameters()) {
            reqNode.set("auto_parameters", true);
        }
        if (condition.isExactMatch()) {
            reqNode.set("exact_match", true);
        }

        setStringArray(reqNode, "include_domains", condition.getIncludeDomains());
        setStringArray(reqNode, "exclude_domains", condition.getExcludeDomains());

        String respJson = createHttpUtils("/search")
                .bodyOfJson(reqNode.toJson())
                .post();

        return parseSearchResponse(ONode.ofJson(respJson));
    }

    // ==================== Extract ====================

    /**
     * 提取指定 URL 的内容
     *
     * @param condition 提取条件
     * @return 完整提取响应
     */
    public TavilyExtractResponse extract(ExtractCondition condition) throws IOException {
        ONode reqNode = new ONode();
        reqNode.set("urls", ONode.ofBean(condition.getUrls()));

        setIfNotNull(reqNode, "query", condition.getQuery());
        setIfNotNull(reqNode, "extract_depth", condition.getExtractDepth());
        setIfNotNull(reqNode, "format", condition.getFormat());

        if (condition.getChunksPerSource() != null) {
            reqNode.set("chunks_per_source", condition.getChunksPerSource());
        }
        if (condition.getTimeout() != null) {
            reqNode.set("timeout", condition.getTimeout());
        }
        if (condition.isIncludeImages()) {
            reqNode.set("include_images", true);
        }
        if (condition.isIncludeFavicon()) {
            reqNode.set("include_favicon", true);
        }

        String respJson = createHttpUtils("/extract")
                .bodyOfJson(reqNode.toJson())
                .post();

        return parseExtractResponse(ONode.ofJson(respJson));
    }

    // ==================== Crawl ====================

    /**
     * 从根 URL 爬取网站内容
     *
     * @param condition 爬取条件
     * @return 完整爬取响应
     */
    public TavilyCrawlResponse crawl(CrawlCondition condition) throws IOException {
        ONode reqNode = new ONode();
        reqNode.set("url", condition.getUrl());
        reqNode.set("max_depth", condition.getMaxDepth());
        reqNode.set("max_breadth", condition.getMaxBreadth());
        reqNode.set("limit", condition.getLimit());
        reqNode.set("allow_external", condition.isAllowExternal());

        setIfNotNull(reqNode, "instructions", condition.getInstructions());
        setIfNotNull(reqNode, "extract_depth", condition.getExtractDepth());
        setIfNotNull(reqNode, "format", condition.getFormat());

        if (condition.getChunksPerSource() != null) {
            reqNode.set("chunks_per_source", condition.getChunksPerSource());
        }
        if (condition.getTimeout() != null) {
            reqNode.set("timeout", condition.getTimeout());
        }
        if (condition.isIncludeImages()) {
            reqNode.set("include_images", true);
        }
        if (condition.isIncludeFavicon()) {
            reqNode.set("include_favicon", true);
        }

        setStringArray(reqNode, "select_paths", condition.getSelectPaths());
        setStringArray(reqNode, "select_domains", condition.getSelectDomains());
        setStringArray(reqNode, "exclude_paths", condition.getExcludePaths());
        setStringArray(reqNode, "exclude_domains", condition.getExcludeDomains());

        String respJson = createHttpUtils("/crawl")
                .bodyOfJson(reqNode.toJson())
                .post();

        return parseCrawlResponse(ONode.ofJson(respJson));
    }

    // ==================== Map ====================

    /**
     * 映射网站结构，返回发现的 URL 列表
     *
     * @param condition 映射条件
     * @return 完整映射响应
     */
    public TavilyMapResponse map(MapCondition condition) throws IOException {
        ONode reqNode = new ONode();
        reqNode.set("url", condition.getUrl());
        reqNode.set("max_depth", condition.getMaxDepth());
        reqNode.set("max_breadth", condition.getMaxBreadth());
        reqNode.set("limit", condition.getLimit());
        reqNode.set("allow_external", condition.isAllowExternal());

        setIfNotNull(reqNode, "instructions", condition.getInstructions());

        if (condition.getTimeout() != null) {
            reqNode.set("timeout", condition.getTimeout());
        }

        setStringArray(reqNode, "select_paths", condition.getSelectPaths());
        setStringArray(reqNode, "select_domains", condition.getSelectDomains());
        setStringArray(reqNode, "exclude_paths", condition.getExcludePaths());
        setStringArray(reqNode, "exclude_domains", condition.getExcludeDomains());

        String respJson = createHttpUtils("/map")
                .bodyOfJson(reqNode.toJson())
                .post();

        return parseMapResponse(ONode.ofJson(respJson));
    }

    // ==================== 响应解析 ====================

    private TavilySearchResponse parseSearchResponse(ONode respNode) {
        TavilySearchResponse response = new TavilySearchResponse();
        response.setQuery(respNode.get("query").getString());
        response.setAnswer(respNode.get("answer").getString());
        response.setResponseTime(respNode.get("response_time").getDouble());
        response.setRequestId(respNode.get("request_id").getString());

        // 解析图片
        List<TavilyImage> images = new ArrayList<>();
        ONode imagesNode = respNode.get("images");
        if (imagesNode.isArray()) {
            for (ONode n : imagesNode.getArray()) {
                TavilyImage image = new TavilyImage();
                if (n.isObject()) {
                    image.setUrl(n.get("url").getString());
                    image.setDescription(n.get("description").getString());
                } else {
                    image.setUrl(n.getString());
                }
                images.add(image);
            }
        }
        response.setImages(images);

        // 解析搜索结果
        List<TavilySearchDocument> results = new ArrayList<>();
        ONode resultsNode = respNode.get("results");
        if (resultsNode.isArray()) {
            for (ONode n : resultsNode.getArray()) {
                TavilySearchDocument doc = new TavilySearchDocument();
                doc.setTitle(n.get("title").getString());
                doc.setUrl(n.get("url").getString());
                doc.setContent(n.get("content").getString());
                doc.setScore(n.get("score").getDouble());
                doc.setRawContent(n.get("raw_content").getString());
                doc.setFavicon(n.get("favicon").getString());
                results.add(doc);
            }
        }
        response.setResults(results);

        return response;
    }

    private TavilyExtractResponse parseExtractResponse(ONode respNode) {
        TavilyExtractResponse response = new TavilyExtractResponse();
        response.setResponseTime(respNode.get("response_time").getDouble());
        response.setRequestId(respNode.get("request_id").getString());

        // 解析成功结果
        List<TavilyExtractDocument> results = new ArrayList<>();
        ONode resultsNode = respNode.get("results");
        if (resultsNode.isArray()) {
            for (ONode n : resultsNode.getArray()) {
                TavilyExtractDocument doc = new TavilyExtractDocument();
                doc.setUrl(n.get("url").getString());
                doc.setRawContent(n.get("raw_content").getString());
                doc.setFavicon(n.get("favicon").getString());

                List<String> images = new ArrayList<>();
                ONode imagesNode = n.get("images");
                if (imagesNode.isArray()) {
                    for (ONode img : imagesNode.getArray()) {
                        images.add(img.getString());
                    }
                }
                doc.setImages(images);
                results.add(doc);
            }
        }
        response.setResults(results);

        // 解析失败结果
        List<TavilyFailedResult> failedResults = new ArrayList<>();
        ONode failedNode = respNode.get("failed_results");
        if (failedNode.isArray()) {
            for (ONode n : failedNode.getArray()) {
                failedResults.add(new TavilyFailedResult(
                        n.get("url").getString(),
                        n.get("error").getString()));
            }
        }
        response.setFailedResults(failedResults);

        return response;
    }

    private TavilyCrawlResponse parseCrawlResponse(ONode respNode) {
        TavilyCrawlResponse response = new TavilyCrawlResponse();
        response.setBaseUrl(respNode.get("base_url").getString());
        response.setResponseTime(respNode.get("response_time").getDouble());
        response.setRequestId(respNode.get("request_id").getString());

        List<TavilyCrawlDocument> results = new ArrayList<>();
        ONode resultsNode = respNode.get("results");
        if (resultsNode.isArray()) {
            for (ONode n : resultsNode.getArray()) {
                TavilyCrawlDocument doc = new TavilyCrawlDocument();
                doc.setUrl(n.get("url").getString());
                doc.setRawContent(n.get("raw_content").getString());
                doc.setFavicon(n.get("favicon").getString());
                results.add(doc);
            }
        }
        response.setResults(results);

        return response;
    }

    private TavilyMapResponse parseMapResponse(ONode respNode) {
        TavilyMapResponse response = new TavilyMapResponse();
        response.setBaseUrl(respNode.get("base_url").getString());
        response.setResponseTime(respNode.get("response_time").getDouble());
        response.setRequestId(respNode.get("request_id").getString());

        List<String> urls = new ArrayList<>();
        ONode resultsNode = respNode.get("results");
        if (resultsNode.isArray()) {
            for (ONode n : resultsNode.getArray()) {
                urls.add(n.getString());
            }
        }
        response.setResults(urls);

        return response;
    }

    // ==================== 内部工具 ====================

    private HttpUtils createHttpUtils(String path) {
        HttpUtils httpUtils = HttpUtils.http(apiBase + path)
                .ssl(HttpSslSupplierAny.getInstance())
                .timeout((int) timeout.getSeconds())
                .header("Authorization", "Bearer " + apiKey);

        if (proxy != null) {
            httpUtils.proxy(proxy);
        }

        return httpUtils;
    }

    private void setIfNotNull(ONode node, String key, String value) {
        if (value != null) {
            node.set(key, value);
        }
    }

    private void setStringArray(ONode node, String key, List<String> values) {
        if (values != null && !values.isEmpty()) {
            node.set(key, ONode.ofBean(values));
        }
    }

    private static String stripTrailingSlash(String url) {
        if (url != null && url.endsWith("/")) {
            return url.substring(0, url.length() - 1);
        }
        return url;
    }
}
