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

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.client.transport.WebRxSseClientTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.noear.snack.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.RefererFunctionTool;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.ai.image.Image;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.exception.McpException;
import org.noear.solon.core.Props;
import org.noear.solon.core.util.RunUtil;
import org.noear.solon.net.http.HttpTimeout;
import org.noear.solon.net.http.HttpUtilsBuilder;

import java.io.Closeable;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Mcp 连接工具提供者
 *
 * @author noear
 * @since 3.1
 */
public class McpClientToolProvider implements ToolProvider, Closeable {
    private final ReentrantLock LOCKER = new ReentrantLock();

    private final AtomicBoolean isClosed = new AtomicBoolean(false);
    private final McpClientProperties clientProps;
    private ScheduledExecutorService heartbeatExecutor;
    private McpSyncClient client;

    /**
     * 用于支持注入
     */
    public McpClientToolProvider(Properties clientProps) {
        this(Props.from(clientProps).bindTo(new McpClientProperties()));
    }

    /**
     * 用于简单构建
     */
    public McpClientToolProvider(String apiUrl) {
        this(new McpClientProperties(apiUrl));
    }

    public McpClientToolProvider(McpClientProperties clientProps) {
        if (clientProps.getHeartbeatInterval() != null) {
            if (clientProps.getHeartbeatInterval().getSeconds() < 10L) {
                throw new IllegalArgumentException("HeartbeatInterval cannot be less than 10s!");
            }
        }

        if (McpChannel.STDIO.equals(clientProps.getChannel())) {
            //stdio 通道
            if (clientProps.getServerParameters() == null) {
                throw new IllegalArgumentException("ServerParameters is null!");
            }
        } else {
            //sse 通道
            if (Utils.isEmpty(clientProps.getApiUrl())) {
                throw new IllegalArgumentException("ApiUrl is empty!");
            }
        }

        this.clientProps = clientProps;

        //开始心跳
        this.heartbeatHandle();
    }

    /**
     * 构建同步客户端
     */
    private McpSyncClient buildClient() {
        McpClientTransport clientTransport;

        if (McpChannel.STDIO.equals(clientProps.getChannel())) {
            //stdio 通道
            clientTransport = new StdioClientTransport(ServerParameters.builder(clientProps.getServerParameters().getCommand())
                    .args(clientProps.getServerParameters().getArgs())
                    .env(clientProps.getServerParameters().getEnv())
                    .build());
        } else {
            //sse 通道
            URI url = URI.create(clientProps.getApiUrl());
            String baseUri = url.getScheme() + "://" + url.getAuthority();
            String sseEndpoint = null;
            if (Utils.isEmpty(url.getRawQuery())) {
                sseEndpoint = url.getRawPath();
            } else {
                sseEndpoint = url.getRawPath() + "?" + url.getRawQuery();
            }


            if (Utils.isEmpty(sseEndpoint)) {
                throw new IllegalArgumentException("SseEndpoint is empty!");
            }

            //超时
            HttpTimeout httpTimeout = clientProps.getHttpTimeout();

            HttpUtilsBuilder webBuilder = new HttpUtilsBuilder();
            webBuilder.baseUri(baseUri);

            if (Utils.isNotEmpty(clientProps.getApiKey())) {
                webBuilder.headerSet("Authorization", "Bearer " + clientProps.getApiKey());
            }

            clientProps.getHeaders().forEach((k, v) -> {
                webBuilder.headerSet(k, v);
            });

            if (httpTimeout != null) {
                webBuilder.timeout(httpTimeout);
            }

            clientTransport = WebRxSseClientTransport.builder(webBuilder)
                    .sseEndpoint(sseEndpoint)
                    .build();
        }

        return McpClient.sync(clientTransport)
                .clientInfo(new McpSchema.Implementation(clientProps.getName(), clientProps.getVersion()))
                .requestTimeout(clientProps.getRequestTimeout())
                .initializationTimeout(clientProps.getInitializationTimeout())
                .build();
    }


    /**
     * 获取客户端
     */
    public McpSyncClient getClient() {
        LOCKER.lock();

        try {
            if (isClosed.get()) {
                //如果已关闭
                throw new IllegalStateException("The current status has been closed.");
            }

            if (client == null) {
                client = buildClient();
            }

            if (client.isInitialized() == false) {
                client.initialize();
            }

            return client;
        } finally {
            LOCKER.unlock();
        }
    }

    private void heartbeatHandle() {
        if (heartbeatExecutor == null) {
            heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
        }

        heartbeatHandleDo();
    }

    /**
     * 心跳处理
     */
    private void heartbeatHandleDo() {
        if (heartbeatExecutor == null) {
            return;
        }

        if (clientProps.getHeartbeatInterval() == null) {
            return;
        }

        heartbeatExecutor.schedule(() -> {
            if (Thread.currentThread().isInterrupted()) {
                //如果中断
                return;
            }

            if (isClosed.get() == false) {
                //如果未关闭，再次心跳
                RunUtil.runAndTry(() -> {
                    try {
                        getClient().ping();
                    } catch (Throwable ex) {
                        //如果失败，重置（下次会尝试重连）
                        this.reset();
                    }
                });

                heartbeatHandleDo();
            }
        }, this.clientProps.getHeartbeatInterval().toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * 关闭
     */
    @Override
    public void close() {
        LOCKER.lock();
        try {
            if (isClosed.get() == false) {
                //如果未关闭
                isClosed.set(true);

                if (heartbeatExecutor != null) {
                    heartbeatExecutor.shutdownNow();
                    heartbeatExecutor = null;
                }

                this.reset();
            }
        } finally {
            LOCKER.unlock();
        }
    }

    /**
     * 重新打开
     */
    public void reopen() {
        LOCKER.lock();
        try {
            if (isClosed.get()) {
                //如果已关闭
                isClosed.set(false);
                getClient();
                heartbeatHandle();
            }
        } finally {
            LOCKER.unlock();
        }
    }

    /**
     * 重置
     */
    private void reset() {
        LOCKER.lock();
        try {
            if (client != null) {
                client.close();
                client = null;
            }
        } finally {
            LOCKER.unlock();
        }
    }

    /// /////////////////////////////

    /**
     * 调用工具并转为文本
     *
     * @param name 工具名
     * @param args 调用参数
     */
    public String callToolAsText(String name, Map<String, Object> args) {
        McpSchema.CallToolResult result = callTool(name, args);
        if (Utils.isEmpty(result.getContent())) {
            return null;
        } else {
            return ((McpSchema.TextContent) result.getContent().get(0)).getText();
        }
    }

    /**
     * 调用工具并转为图像
     *
     * @param name 工具名
     * @param args 调用参数
     */
    public Image callToolAsImage(String name, Map<String, Object> args) {
        McpSchema.CallToolResult result = callTool(name, args);
        if (Utils.isEmpty(result.getContent())) {
            return null;
        } else {
            McpSchema.ImageContent imageContent = ((McpSchema.ImageContent) result.getContent().get(0));
            return Image.ofBase64(imageContent.getData(), imageContent.getMimeType());
        }
    }

    /**
     * 调用工具
     *
     * @param name 工具名
     * @param args 调用参数
     */
    protected McpSchema.CallToolResult callTool(String name, Map<String, Object> args) {
        try {
            McpSchema.CallToolRequest callToolRequest = new McpSchema.CallToolRequest(name, args);
            McpSchema.CallToolResult response = getClient().callTool(callToolRequest);

            if (response.getIsError() == null || response.getIsError() == false) {
                return response;
            } else {
                if (Utils.isEmpty(response.getContent())) {
                    throw new McpException("Call Toll Failed");
                } else {
                    throw new McpException(response.getContent().get(0).toString());
                }
            }
        } catch (RuntimeException ex) {
            this.reset();
            throw ex;
        }
    }

    /// ///////////
    /**
     * 获取函数工具（可用于模型绑定）
     */
    @Override
    public Collection<FunctionTool> getTools() {
        return getTools(null);
    }

    /**
     * 获取函数工具（可用于模型绑定）
     *
     * @param cursor 游标
     */
    public Collection<FunctionTool> getTools(String cursor) {
        List<FunctionTool> toolList = new ArrayList<>();

        McpSchema.ListToolsResult result = null;
        if (cursor == null) {
            result = getClient().listTools();
        } else {
            result = getClient().listTools(cursor);
        }

        for (McpSchema.Tool tool : result.getTools()) {
            String name = tool.getName();
            String description = tool.getDescription();
            ONode parametersNode = ONode.load(tool.getInputSchema());

            RefererFunctionTool functionRefer = new RefererFunctionTool(
                    name,
                    description,
                    parametersNode,
                    args -> callToolAsText(name, args));

            toolList.add(functionRefer);
        }

        return toolList;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private McpClientProperties props = new McpClientProperties();

        public Builder name(String name) {
            props.setName(name);
            return this;
        }

        public Builder version(String version) {
            props.setVersion(version);
            return this;
        }

        public Builder channel(String channel) {
            props.setChannel(channel);
            return this;
        }

        public Builder apiUrl(String apiUrl) {
            props.setApiUrl(apiUrl);
            return this;
        }

        public Builder apiKey(String apiKey) {
            props.setApiKey(apiKey);
            return this;
        }

        public Builder header(String name, String value) {
            props.getHeaders().put(name, value);
            return this;
        }

        public Builder httpTimeout(HttpTimeout httpTimeout) {
            props.setHttpTimeout(httpTimeout);
            return this;
        }

        public Builder requestTimeout(Duration requestTimeout) {
            props.setRequestTimeout(requestTimeout);
            return this;
        }

        public Builder initializationTimeout(Duration initializationTimeout) {
            props.setInitializationTimeout(initializationTimeout);
            return this;
        }

        public Builder heartbeatInterval(Duration heartbeatInterval) {
            props.setHeartbeatInterval(heartbeatInterval);
            return this;
        }

        /**
         * 服务端参数（用于 stdio）
         */
        public Builder serverParameters(McpServerParameters serverParameters) {
            props.setServerParameters(serverParameters);
            return this;
        }

        public McpClientToolProvider build() {
            return new McpClientToolProvider(props);
        }
    }
}