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
 * Tavily Extract 接口完整响应
 *
 * @author shaoerkuai
 * @since 3.9.5
 * @see <a href="https://docs.tavily.com/documentation/api-reference/introduction">API Reference</a>
 */
public class TavilyExtractResponse {
    private List<TavilyExtractDocument> results;
    private List<TavilyFailedResult> failedResults;
    private double responseTime;
    private String requestId;

    public TavilyExtractResponse() {
    }

    // ==================== Getters ====================

    public List<TavilyExtractDocument> getResults() {
        return results;
    }

    public List<TavilyFailedResult> getFailedResults() {
        return failedResults;
    }

    public double getResponseTime() {
        return responseTime;
    }

    public String getRequestId() {
        return requestId;
    }

    // ==================== Setters ====================

    public void setResults(List<TavilyExtractDocument> results) {
        this.results = results;
    }

    public void setFailedResults(List<TavilyFailedResult> failedResults) {
        this.failedResults = failedResults;
    }

    public void setResponseTime(double responseTime) {
        this.responseTime = responseTime;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    /**
     * 将提取结果转换为标准 Document 列表
     */
    public List<Document> toDocuments() {
        if (results == null || results.isEmpty()) {
            return Collections.emptyList();
        }

        List<Document> docs = new ArrayList<>(results.size());
        for (TavilyExtractDocument result : results) {
            docs.add(result.toDocument());
        }
        return docs;
    }
}
