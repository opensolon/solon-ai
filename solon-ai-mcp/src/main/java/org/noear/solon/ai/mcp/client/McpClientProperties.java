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
package org.noear.solon.ai.mcp.client;

import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.util.ProxyDesc;
import org.noear.solon.net.http.HttpTimeout;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mcp 客户端属性
 *
 * @author noear
 * @since 3.1
 */
public class McpClientProperties {
    /**
     * 客户端名称
     */
    private String name = "Solon-Ai-Mcp-Client";

    /**
     * 客户端版本号
     */
    private String version = "1.0.0";

    /**
     * 通道
     */
    private String channel = McpChannel.SSE;

    /**
     * 接口完整地址
     */
    private String apiUrl;

    /**
     * 接口密钥
     */
    private String apiKey;

    /**
     * 请求头信息
     */
    private final Map<String, String> headers = new LinkedHashMap<>();

    /**
     * http 超时
     */
    private HttpTimeout httpTimeout = HttpTimeout.of(10, 60, 60);

    /**
     * http 代理简单描述
     */
    protected ProxyDesc httpProxy;

    /**
     * http 代理实例
     */
    private Proxy httpProxyInstance;

    /**
     * 请求超时
     */
    private Duration requestTimeout = Duration.ofSeconds(20); // Default timeout

    /**
     * 初始化超时
     */
    private Duration initializationTimeout = Duration.ofSeconds(20);

    /**
     * 心跳间隔（辅助自动重连）
     */
    private Duration heartbeatInterval = Duration.ofSeconds(15);

    /**
     * 缓存秒数
     */
    private int cacheSeconds = 30; // Default timeout

    /**
     * 服务端参数（用于 stdio）
     */
    private McpServerParameters serverParameters;


    public McpClientProperties() {
        //用于序列化
    }

    /**
     * @param apiUrl 接口地址
     */
    public McpClientProperties(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    /// ///////////////////

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers.putAll(headers);
    }


    public HttpTimeout getHttpTimeout() {
        return httpTimeout;
    }

    public void setHttpTimeout(HttpTimeout httpTimeout) {
        this.httpTimeout = httpTimeout;
    }

    public Proxy getHttpProxy() {
        if (httpProxyInstance == null) {
            if (httpProxy != null) {
                httpProxyInstance = new Proxy(httpProxy.type, new InetSocketAddress(httpProxy.host, httpProxy.port));
            }
        }

        return httpProxyInstance;
    }

    public void setHttpProxy(Proxy httpProxy) {
        this.httpProxyInstance = httpProxy;
        this.httpProxy = null;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public Duration getInitializationTimeout() {
        return initializationTimeout;
    }

    public void setInitializationTimeout(Duration initializationTimeout) {
        this.initializationTimeout = initializationTimeout;
    }

    public Duration getHeartbeatInterval() {
        return heartbeatInterval;
    }

    public void setHeartbeatInterval(Duration heartbeatInterval) {
        this.heartbeatInterval = heartbeatInterval;
    }


    public int getCacheSeconds() {
        return cacheSeconds;
    }

    public void setCacheSeconds(int cacheSeconds) {
        this.cacheSeconds = cacheSeconds;
    }

    public McpServerParameters getServerParameters() {
        return serverParameters;
    }

    public void setServerParameters(McpServerParameters serverParameters) {
        this.serverParameters = serverParameters;
    }

    @Override
    public String toString() {
        return "McpClientProperties{" +
                "name='" + name + '\'' +
                ", version='" + version + '\'' +
                ", channel='" + channel + '\'' +
                ", apiUrl='" + apiUrl + '\'' +
                ", apiKey='" + apiKey + '\'' +
                ", headers=" + headers +
                ", httpTimeout=" + httpTimeout +
                ", httpProxy=" + getHttpProxy() +
                ", requestTimeout=" + requestTimeout +
                ", initializationTimeout=" + initializationTimeout +
                ", heartbeatInterval=" + heartbeatInterval +
                ", serverParameters=" + serverParameters +
                '}';
    }
}