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
package org.noear.solon.ai.rag.search.tavily.condition;

import java.util.List;

/**
 * Tavily Crawl 接口条件
 *
 * @author shaoerkuai
 * @since 3.9.5
 * @see <a href="https://docs.tavily.com/documentation/api-reference/introduction">API Reference</a>
 */
public class CrawlCondition {
    private final String url;
    private String instructions;
    private Integer chunksPerSource;
    private int maxDepth = 1;
    private int maxBreadth = 20;
    private int limit = 50;
    private List<String> selectPaths;
    private List<String> selectDomains;
    private List<String> excludePaths;
    private List<String> excludeDomains;
    private boolean allowExternal = true;
    private boolean includeImages;
    private boolean includeFavicon;
    private String extractDepth;
    private String format;
    private Integer timeout;

    public CrawlCondition(String url) {
        this.url = url;
    }

    // ==================== Getters ====================

    public String getUrl() {
        return url;
    }

    public String getInstructions() {
        return instructions;
    }

    public Integer getChunksPerSource() {
        return chunksPerSource;
    }

    public int getMaxDepth() {
        return maxDepth;
    }

    public int getMaxBreadth() {
        return maxBreadth;
    }

    public int getLimit() {
        return limit;
    }

    public List<String> getSelectPaths() {
        return selectPaths;
    }

    public List<String> getSelectDomains() {
        return selectDomains;
    }

    public List<String> getExcludePaths() {
        return excludePaths;
    }

    public List<String> getExcludeDomains() {
        return excludeDomains;
    }

    public boolean isAllowExternal() {
        return allowExternal;
    }

    public boolean isIncludeImages() {
        return includeImages;
    }

    public boolean isIncludeFavicon() {
        return includeFavicon;
    }

    public String getExtractDepth() {
        return extractDepth;
    }

    public String getFormat() {
        return format;
    }

    public Integer getTimeout() {
        return timeout;
    }

    // ==================== Setters (链式) ====================

    /**
     * 配置爬虫的自然语言指令
     */
    public CrawlCondition instructions(String instructions) {
        this.instructions = instructions;
        return this;
    }

    /**
     * 配置每个来源返回的最大片段数（1-5，仅当提供 instructions 时可用）
     */
    public CrawlCondition chunksPerSource(int chunksPerSource) {
        this.chunksPerSource = chunksPerSource;
        return this;
    }

    /**
     * 配置最大爬取深度（范围 1-5，默认 1）
     */
    public CrawlCondition maxDepth(int maxDepth) {
        this.maxDepth = maxDepth;
        return this;
    }

    /**
     * 配置每层最大链接数（范围 1-500，默认 20）
     */
    public CrawlCondition maxBreadth(int maxBreadth) {
        this.maxBreadth = maxBreadth;
        return this;
    }

    /**
     * 配置处理链接总数限制（默认 50）
     */
    public CrawlCondition limit(int limit) {
        this.limit = limit;
        return this;
    }

    /**
     * 配置路径选择正则模式（如 "/docs/.*"）
     */
    public CrawlCondition selectPaths(List<String> selectPaths) {
        this.selectPaths = selectPaths;
        return this;
    }

    /**
     * 配置域名选择正则模式
     */
    public CrawlCondition selectDomains(List<String> selectDomains) {
        this.selectDomains = selectDomains;
        return this;
    }

    /**
     * 配置路径排除正则模式
     */
    public CrawlCondition excludePaths(List<String> excludePaths) {
        this.excludePaths = excludePaths;
        return this;
    }

    /**
     * 配置域名排除正则模式
     */
    public CrawlCondition excludeDomains(List<String> excludeDomains) {
        this.excludeDomains = excludeDomains;
        return this;
    }

    /**
     * 配置是否允许外部链接（默认 true）
     */
    public CrawlCondition allowExternal(boolean allowExternal) {
        this.allowExternal = allowExternal;
        return this;
    }

    /**
     * 配置是否包含图片
     */
    public CrawlCondition includeImages(boolean includeImages) {
        this.includeImages = includeImages;
        return this;
    }

    /**
     * 配置是否包含 favicon
     */
    public CrawlCondition includeFavicon(boolean includeFavicon) {
        this.includeFavicon = includeFavicon;
        return this;
    }

    /**
     * 配置提取深度（"basic" 或 "advanced"）
     */
    public CrawlCondition extractDepth(String extractDepth) {
        this.extractDepth = extractDepth;
        return this;
    }

    /**
     * 配置输出格式（"markdown" 或 "text"）
     */
    public CrawlCondition format(String format) {
        this.format = format;
        return this;
    }

    /**
     * 配置超时时间（秒，范围 10-150，默认 150）
     */
    public CrawlCondition timeout(int timeout) {
        this.timeout = timeout;
        return this;
    }
}
