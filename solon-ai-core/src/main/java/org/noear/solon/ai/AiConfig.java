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
package org.noear.solon.ai;

import org.noear.solon.Utils;
import org.noear.solon.ai.util.ProxyDesc;
import org.noear.solon.net.http.HttpUtils;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ai 接口配置
 *
 * @author noear
 * @since 3.1
 */
public class AiConfig {
    protected String apiUrl;
    protected String apiKey;
    protected String provider;
    protected String model;
    protected final Map<String, String> headers = new LinkedHashMap<>();
    protected Duration timeout = Duration.ofSeconds(60);
    protected ProxyDesc proxy;
    protected Proxy proxyInstance;

    /// ///////////////////

    public String getApiKey() {
        return apiKey;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public String getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public Proxy getProxy() {
        if (proxyInstance == null) {
            if (proxy != null) {
                proxyInstance = new Proxy(proxy.type, new InetSocketAddress(proxy.host, proxy.port));
            }
        }

        return proxyInstance;
    }

    /// ///////////////////

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public void setHeaders(Map<String, String> headers) {
        if (headers != null) {
            this.headers.putAll(headers);
        }
    }

    public void setHeader(String key, String value) {
        headers.put(key, value);
    }

    public void setTimeout(Duration timeout) {
        if (timeout != null) {
            this.timeout = timeout;
        }
    }

    public void setProxy(Proxy proxyInstance) {
        this.proxyInstance = proxyInstance;
        this.proxy = null;
    }

    /**
     * 创建 http 请求
     */
    public HttpUtils createHttpUtils() {
        HttpUtils httpUtils = HttpUtils
                .http(getApiUrl())
                .timeout((int) getTimeout().getSeconds());

        if (getProxy() != null) {
            httpUtils.proxy(getProxy());
        }

        if (Utils.isNotEmpty(getApiKey())) {
            httpUtils.header("Authorization", "Bearer " + getApiKey());
        }

        httpUtils.headers(getHeaders());

        return httpUtils;
    }

    @Override
    public String toString() {
        return "AiConfig{" +
                "apiUrl='" + apiUrl + '\'' +
                ", apiKey='" + apiKey + '\'' +
                ", provider='" + provider + '\'' +
                ", model='" + model + '\'' +
                ", headers=" + headers +
                ", timeout=" + timeout +
                ", proxy=" + getProxy() +
                '}';
    }
}