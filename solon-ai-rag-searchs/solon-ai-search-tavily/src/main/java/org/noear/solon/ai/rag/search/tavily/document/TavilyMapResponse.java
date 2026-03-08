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

import java.util.List;

/**
 * Tavily Map 接口完整响应
 *
 * @author shaoerkuai
 * @since 3.9.5
 * @see <a href="https://docs.tavily.com/documentation/api-reference/introduction">API Reference</a>
 */
public class TavilyMapResponse {
    private String baseUrl;
    private List<String> results;
    private double responseTime;
    private String requestId;

    public TavilyMapResponse() {
    }

    // ==================== Getters ====================

    /**
     * 获取被映射的基础 URL
     */
    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * 获取映射发现的 URL 列表
     */
    public List<String> getResults() {
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

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public void setResults(List<String> results) {
        this.results = results;
    }

    public void setResponseTime(double responseTime) {
        this.responseTime = responseTime;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }
}
