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
 * Tavily Search 接口条件（参数）
 *
 * @author shaoerkuai
 * @since 3.9.5
 * @see <a href="https://docs.tavily.com/documentation/api-reference/introduction">API Reference</a>
 */
public class SearchCondition {
    private final String query;
    private String searchDepth;
    private Integer chunksPerSource;
    private int maxResults = 5;
    private String topic;
    private String timeRange;
    private String startDate;
    private String endDate;
    private String includeAnswer;
    private String includeRawContent;
    private boolean includeImages;
    private boolean includeImageDescriptions;
    private boolean includeFavicon;
    private List<String> includeDomains;
    private List<String> excludeDomains;
    private String country;
    private boolean autoParameters;
    private boolean exactMatch;

    public SearchCondition(String query) {
        this.query = query;
    }

    // ==================== Getters ====================

    public String getQuery() {
        return query;
    }

    public String getSearchDepth() {
        return searchDepth;
    }

    public Integer getChunksPerSource() {
        return chunksPerSource;
    }

    public int getMaxResults() {
        return maxResults;
    }

    public String getTopic() {
        return topic;
    }

    public String getTimeRange() {
        return timeRange;
    }

    public String getStartDate() {
        return startDate;
    }

    public String getEndDate() {
        return endDate;
    }

    public String getIncludeAnswer() {
        return includeAnswer;
    }

    public String getIncludeRawContent() {
        return includeRawContent;
    }

    public boolean isIncludeImages() {
        return includeImages;
    }

    public boolean isIncludeImageDescriptions() {
        return includeImageDescriptions;
    }

    public boolean isIncludeFavicon() {
        return includeFavicon;
    }

    public List<String> getIncludeDomains() {
        return includeDomains;
    }

    public List<String> getExcludeDomains() {
        return excludeDomains;
    }

    public String getCountry() {
        return country;
    }

    public boolean isAutoParameters() {
        return autoParameters;
    }

    public boolean isExactMatch() {
        return exactMatch;
    }

    // ==================== Setters (链式) ====================

    /**
     * 配置搜索深度（"basic"、"advanced"、"fast"、"ultra-fast"）
     */
    public SearchCondition searchDepth(String searchDepth) {
        this.searchDepth = searchDepth;
        return this;
    }

    /**
     * 配置每个来源返回的最大内容片段数（1-3，仅 advanced 模式可用）
     */
    public SearchCondition chunksPerSource(int chunksPerSource) {
        this.chunksPerSource = chunksPerSource;
        return this;
    }

    /**
     * 配置最大搜索结果数（0-20，默认 5）
     */
    public SearchCondition maxResults(int maxResults) {
        this.maxResults = maxResults;
        return this;
    }

    /**
     * 配置搜索类别（"general"、"news"、"finance"）
     */
    public SearchCondition topic(String topic) {
        this.topic = topic;
        return this;
    }

    /**
     * 配置时间范围过滤（"day"、"week"、"month"、"year"）
     */
    public SearchCondition timeRange(String timeRange) {
        this.timeRange = timeRange;
        return this;
    }

    /**
     * 配置起始日期（格式 YYYY-MM-DD）
     */
    public SearchCondition startDate(String startDate) {
        this.startDate = startDate;
        return this;
    }

    /**
     * 配置截止日期（格式 YYYY-MM-DD）
     */
    public SearchCondition endDate(String endDate) {
        this.endDate = endDate;
        return this;
    }

    /**
     * 配置是否包含 LLM 生成的答案（"basic" 或 "advanced"）
     */
    public SearchCondition includeAnswer(String includeAnswer) {
        this.includeAnswer = includeAnswer;
        return this;
    }

    /**
     * 配置是否包含原始内容（"markdown" 或 "text"）
     */
    public SearchCondition includeRawContent(String includeRawContent) {
        this.includeRawContent = includeRawContent;
        return this;
    }

    /**
     * 配置是否执行图片搜索
     */
    public SearchCondition includeImages(boolean includeImages) {
        this.includeImages = includeImages;
        return this;
    }

    /**
     * 配置是否为图片添加描述（需 includeImages 为 true）
     */
    public SearchCondition includeImageDescriptions(boolean includeImageDescriptions) {
        this.includeImageDescriptions = includeImageDescriptions;
        return this;
    }

    /**
     * 配置是否包含 favicon URL
     */
    public SearchCondition includeFavicon(boolean includeFavicon) {
        this.includeFavicon = includeFavicon;
        return this;
    }

    /**
     * 配置仅包含指定域名的结果（最多 300 个）
     */
    public SearchCondition includeDomains(List<String> includeDomains) {
        this.includeDomains = includeDomains;
        return this;
    }

    /**
     * 配置排除指定域名的结果（最多 150 个）
     */
    public SearchCondition excludeDomains(List<String> excludeDomains) {
        this.excludeDomains = excludeDomains;
        return this;
    }

    /**
     * 配置优先搜索的国家
     */
    public SearchCondition country(String country) {
        this.country = country;
        return this;
    }

    /**
     * 配置是否启用自动参数优化
     */
    public SearchCondition autoParameters(boolean autoParameters) {
        this.autoParameters = autoParameters;
        return this;
    }

    /**
     * 配置是否仅返回精确匹配结果
     */
    public SearchCondition exactMatch(boolean exactMatch) {
        this.exactMatch = exactMatch;
        return this;
    }
}
