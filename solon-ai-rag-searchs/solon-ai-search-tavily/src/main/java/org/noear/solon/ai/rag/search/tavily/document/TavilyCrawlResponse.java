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
 * Tavily Crawl 接口完整响应
 *
 * @author shaoerkuai
 * @since 3.9.5
 * @see <a href="https://docs.tavily.com/documentation/api-reference/introduction">API Reference</a>
 */
public class TavilyCrawlResponse {
    private String baseUrl;
    private List<TavilyCrawlDocument> results;
    private double responseTime;
    private String requestId;

    public TavilyCrawlResponse() {
    }

    // ==================== Getters ====================

    public String getBaseUrl() {
        return baseUrl;
    }

    public List<TavilyCrawlDocument> getResults() {
        return results;
    }

    public double getResponseTime() {
        return responseTime;
    }

    public String getRequestId() {
        return requestId;
    }

    // ==================== Setters ====================

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public void setResults(List<TavilyCrawlDocument> results) {
        this.results = results;
    }

    public void setResponseTime(double responseTime) {
        this.responseTime = responseTime;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    /**
     * 将爬取结果转换为标准 Document 列表
     */
    public List<Document> toDocuments() {
        if (results == null || results.isEmpty()) {
            return Collections.emptyList();
        }

        List<Document> docs = new ArrayList<>(results.size());
        for (TavilyCrawlDocument result : results) {
            docs.add(result.toDocument());
        }
        return docs;
    }
}
