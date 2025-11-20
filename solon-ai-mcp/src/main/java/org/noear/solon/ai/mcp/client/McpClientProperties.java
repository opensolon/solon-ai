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

import io.modelcontextprotocol.spec.McpSchema;
import org.noear.solon.ai.util.ProxyDesc;
import org.noear.solon.net.http.HttpSslSupplier;
import org.noear.solon.net.http.HttpTimeout;
import org.noear.solon.net.http.HttpUtilsFactory;
import org.noear.solon.net.http.impl.jdk.JdkHttpUtilsFactory;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;

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
     * 通道（传输方式）
     */
    private String channel;

    /**
     * http 接口完整地址
     *
     * @deprecated 3.5 {@link #url}
     */
    @Deprecated
    private String apiUrl;

    /**
     * http 接口完整地址
     */
    private String url;

    /**
     * http 接口密钥
     *
     * @deprecated 3.7 {@link #headers}
     */
    @Deprecated
    private String apiKey;

    /**
     * http 请求头信息
     */
    private final Map<String, String> headers = new LinkedHashMap<>();

    /**
     * 超时
     */
    private Duration timeout = Duration.ofSeconds(30); // Default timeout

    /**
     * http 超时（默认随 timeout）
     */
    private HttpTimeout httpTimeout;

    /**
     * http 代理简单描述
     */
    protected ProxyDesc httpProxy;

    /**
     * http 代理实例
     */
    private Proxy httpProxyInstance;

    /**
     * http ssl 提供者
     */
    private HttpSslSupplier httpSsl;

    /**
     * http 工厂
     */
    private HttpUtilsFactory  httpFactory = JdkHttpUtilsFactory.getInstance();

    /**
     * mcp 请求超时（默认随 timeout）
     */
    private Duration requestTimeout;

    /**
     * mcp 初始化超时（默认随 timeout）
     */
    private Duration initializationTimeout;


    /**
     * mcp 心跳间隔（辅助自动重连）
     */
    private Duration heartbeatInterval = Duration.ofSeconds(15);

    /**
     * mcp 缓存秒数
     */
    private int cacheSeconds = 30; // Default timeout

    /**
     * 服务端参数（用于 stdio）
     *
     * @deprecated 3.5
     */
    @Deprecated
    private McpServerParameters serverParameters;


    /// ////////////
    /**
     * stdio 命令
     */
    private String command;
    /**
     * stdio 参数
     */
    private List<String> args = new ArrayList<>();
    /**
     * stdio 命令环境变量
     */
    private Map<String, String> env = new HashMap<>();


    /// ///////////////////////


    private transient Function<List<McpSchema.Tool>, Mono<Void>> toolsChangeConsumer;
    private transient Function<List<McpSchema.Resource>, Mono<Void>> resourcesChangeConsumer;
    private transient Function<List<McpSchema.ResourceContents>, Mono<Void>> resourcesUpdateConsumer;
    private transient Function<List<McpSchema.Prompt>, Mono<Void>> promptsChangeConsumer;


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

    /**
     * @deprecated 3.5 {@link #getUrl()}
     */
    @Deprecated
    public String getApiUrl() {
        return apiUrl;
    }

    /**
     * @deprecated 3.5 {@link #setUrl(String)}
     */
    @Deprecated
    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public String getUrl() {
        if (apiUrl != null) {
            return apiUrl;
        }

        return url;
    }

    public void setUrl(String url) {
        this.apiUrl = null;
        this.url = url;
    }


    /**
     * @deprecated 3.5 {@link #getHeaders()}
     */
    @Deprecated
    public String getApiKey() {
        return apiKey;
    }

    /**
     * @deprecated 3.5 {@link #setHeaders(Map)}
     */
    @Deprecated
    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers.putAll(headers);
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
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

    public void setHttpSsl(HttpSslSupplier httpSslSupplier) {
        this.httpSsl = httpSslSupplier;
    }

    public HttpSslSupplier getHttpSsl() {
        return httpSsl;
    }

    public void setHttpFactory(HttpUtilsFactory httpFactory) {
        this.httpFactory = httpFactory;
    }

    public HttpUtilsFactory getHttpFactory() {
        return httpFactory;
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

    /**
     * @deprecated 3.5 {@link #getCommand()}
     */
    @Deprecated
    public McpServerParameters getServerParameters() {
        return serverParameters;
    }

    /**
     * @deprecated 3.5 {@link #setCommand(String)}
     */
    @Deprecated
    public void setServerParameters(McpServerParameters serverParameters) {
        this.serverParameters = serverParameters;
    }


    /// ////////////

    public String getCommand() {
        if (serverParameters != null) {
            return serverParameters.getCommand();
        }

        return command;
    }

    public void setCommand(String command) {
        this.serverParameters = null;
        this.command = command;
    }

    public List<String> getArgs() {
        if (serverParameters != null) {
            return serverParameters.getArgs();
        }

        return args;
    }

    public void setArgs(List<String> args) {
        this.serverParameters = null;

        this.args = args;
    }

    public Map<String, String> getEnv() {
        if (serverParameters != null) {
            return serverParameters.getEnv();
        }

        return env;
    }

    public void setEnv(Map<String, String> env) {
        this.serverParameters = null;

        this.env = env;
    }

    public void setToolsChangeConsumer(Function<List<McpSchema.Tool>, Mono<Void>> toolsChangeConsumer) {
        this.toolsChangeConsumer = toolsChangeConsumer;
    }

    public Function<List<McpSchema.Tool>, Mono<Void>> getToolsChangeConsumer() {
        return toolsChangeConsumer;
    }

    public void setResourcesChangeConsumer(Function<List<McpSchema.Resource>, Mono<Void>> resourcesChangeConsumer) {
        this.resourcesChangeConsumer = resourcesChangeConsumer;
    }

    public Function<List<McpSchema.Resource>, Mono<Void>> getResourcesChangeConsumer() {
        return resourcesChangeConsumer;
    }

    public void setResourcesUpdateConsumer(Function<List<McpSchema.ResourceContents>, Mono<Void>> resourcesUpdateConsumer) {
        this.resourcesUpdateConsumer = resourcesUpdateConsumer;
    }

    public Function<List<McpSchema.ResourceContents>, Mono<Void>> getResourcesUpdateConsumer() {
        return resourcesUpdateConsumer;
    }

    public void setPromptsChangeConsumer(Function<List<McpSchema.Prompt>, Mono<Void>> promptsChangeConsumer) {
        this.promptsChangeConsumer = promptsChangeConsumer;
    }

    public Function<List<McpSchema.Prompt>, Mono<Void>> getPromptsChangeConsumer() {
        return promptsChangeConsumer;
    }

    /// ///////////////////////

    /**
     * 预备
     */
    public void prepare() {
        if (timeout == null) {
            timeout = Duration.ofSeconds(30);
        }

        if (httpTimeout == null) {
            httpTimeout = HttpTimeout.of((int) timeout.getSeconds());
        }

        if (initializationTimeout == null) {
            initializationTimeout = timeout;
        }

        if (requestTimeout == null) {
            requestTimeout = timeout;
        }
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
                ", timeout=" + timeout +
                ", httpTimeout=" + httpTimeout + //默认随 timeout
                ", httpProxy=" + getHttpProxy() +
                ", requestTimeout=" + requestTimeout + //默认随 timeout
                ", initializationTimeout=" + initializationTimeout + //默认随 timeout
                ", heartbeatInterval=" + heartbeatInterval +
                ", command='" + command + '\'' +
                ", args=" + args +
                ", env=" + env +
                '}';
    }
}