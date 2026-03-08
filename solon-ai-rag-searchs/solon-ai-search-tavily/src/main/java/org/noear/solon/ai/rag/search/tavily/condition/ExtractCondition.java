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

import java.util.Arrays;
import java.util.List;

/**
 * Tavily Extract 接口条件
 *
 * @author shaoerkuai
 * @since 3.9.5
 * @see <a href="https://docs.tavily.com/documentation/api-reference/introduction">API Reference</a>
 */
public class ExtractCondition {
    private final List<String> urls;
    private String query;
    private Integer chunksPerSource;
    private String extractDepth;
    private boolean includeImages;
    private boolean includeFavicon;
    private String format;
    private Integer timeout;

    public ExtractCondition(String... urls) {
        this.urls = Arrays.asList(urls);
    }

    public ExtractCondition(List<String> urls) {
        this.urls = urls;
    }

    // ==================== Getters ====================

    public List<String> getUrls() {
        return urls;
    }

    public String getQuery() {
        return query;
    }

    public Integer getChunksPerSource() {
        return chunksPerSource;
    }

    public String getExtractDepth() {
        return extractDepth;
    }

    public boolean isIncludeImages() {
        return includeImages;
    }

    public boolean isIncludeFavicon() {
        return includeFavicon;
    }

    public String getFormat() {
        return format;
    }

    public Integer getTimeout() {
        return timeout;
    }

    // ==================== Setters (链式) ====================

    /**
     * 配置查询（用于对提取内容片段按相关性重排序）
     */
    public ExtractCondition query(String query) {
        this.query = query;
        return this;
    }

    /**
     * 配置每个来源返回的最大片段数（1-5，仅当提供 query 时可用）
     */
    public ExtractCondition chunksPerSource(int chunksPerSource) {
        this.chunksPerSource = chunksPerSource;
        return this;
    }

    /**
     * 配置提取深度（"basic" 或 "advanced"）
     */
    public ExtractCondition extractDepth(String extractDepth) {
        this.extractDepth = extractDepth;
        return this;
    }

    /**
     * 配置是否包含图片
     */
    public ExtractCondition includeImages(boolean includeImages) {
        this.includeImages = includeImages;
        return this;
    }

    /**
     * 配置是否包含 favicon
     */
    public ExtractCondition includeFavicon(boolean includeFavicon) {
        this.includeFavicon = includeFavicon;
        return this;
    }

    /**
     * 配置输出格式（"markdown" 或 "text"）
     */
    public ExtractCondition format(String format) {
        this.format = format;
        return this;
    }

    /**
     * 配置超时时间（秒，范围 1-60）
     */
    public ExtractCondition timeout(int timeout) {
        this.timeout = timeout;
        return this;
    }
}
