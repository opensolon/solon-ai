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
package org.noear.solon.ai.rag.search.tavily.document;

import org.noear.solon.ai.rag.Document;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tavily Search 接口完整响应
 *
 * @author shaoerkuai
 * @since 3.9.5
 * @see <a href="https://docs.tavily.com/documentation/api-reference/introduction">API Reference</a>
 */
public class TavilySearchResponse {
    private String query;
    private String answer;
    private List<TavilyImage> images;
    private List<TavilySearchDocument> results;
    private double responseTime;
    private String requestId;

    public TavilySearchResponse() {
    }

    // ==================== Getters ====================

    /**
     * 获取搜索查询
     */
    public String getQuery() {
        return query;
    }

    /**
     * 获取 LLM 生成的答案（需请求时设置 includeAnswer）
     */
    public String getAnswer() {
        return answer;
    }

    /**
     * 获取搜索相关图片列表
     */
    public List<TavilyImage> getImages() {
        return images;
    }

    /**
     * 获取搜索结果列表
     */
    public List<TavilySearchDocument> getResults() {
        return results;
    }

    /**
     * 获取响应耗时（秒）
     */
    public double getResponseTime() {
        return responseTime;
    }

    /**
     * 获取请求 ID
     */
    public String getRequestId() {
        return requestId;
    }

    // ==================== Setters ====================

    public void setQuery(String query) {
        this.query = query;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public void setImages(List<TavilyImage> images) {
        this.images = images;
    }

    public void setResults(List<TavilySearchDocument> results) {
        this.results = results;
    }

    public void setResponseTime(double responseTime) {
        this.responseTime = responseTime;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    /**
     * 将搜索结果转换为标准 Document 列表
     */
    public List<Document> toDocuments() {
        if (results == null || results.isEmpty()) {
            return Collections.emptyList();
        }

        List<Document> docs = new ArrayList<>(results.size());
        for (TavilySearchDocument result : results) {
            docs.add(result.toDocument());
        }
        return docs;
    }
}
