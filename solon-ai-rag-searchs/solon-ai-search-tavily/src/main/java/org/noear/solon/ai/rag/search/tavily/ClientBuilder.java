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

import java.net.Proxy;
import java.time.Duration;

/**
 * Tavily 客户端构建器
 *
 * @author shaoerkuai
 * @since 3.9.5
 */
public class ClientBuilder {
    private String apiBase;
    private final String apiKey;
    private Duration timeout;
    private Proxy proxy;

    public ClientBuilder(String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalArgumentException("apiKey is required");
        }
        this.apiKey = apiKey;
    }

    /**
     * 创建构建器
     *
     * @param apiKey API 密钥（必传）
     */
    public static ClientBuilder of(String apiKey) {
        return new ClientBuilder(apiKey);
    }

    /**
     * 配置 API 端点（默认 https://api.tavily.com）
     */
    public ClientBuilder apiBase(String apiBase) {
        this.apiBase = apiBase;
        return this;
    }

    /**
     * 配置请求超时（默认 60 秒）
     */
    public ClientBuilder timeout(Duration timeout) {
        this.timeout = timeout;
        return this;
    }

    /**
     * 配置代理
     */
    public ClientBuilder proxy(Proxy proxy) {
        this.proxy = proxy;
        return this;
    }

    /**
     * 构建 TavilyClient 实例
     */
    public TavilyClient build() {
        return new TavilyClient(apiBase, apiKey, timeout, proxy);
    }
}
