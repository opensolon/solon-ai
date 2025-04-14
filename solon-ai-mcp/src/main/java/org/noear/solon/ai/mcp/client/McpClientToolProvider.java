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
import io.modelcontextprotocol.client.transport.WebRxSseClientTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.noear.snack.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.RefererFunctionTool;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.ai.image.Image;
import org.noear.solon.ai.mcp.exception.McpException;
import org.noear.solon.core.Props;
import org.noear.solon.net.http.HttpTimeout;
import org.noear.solon.net.http.HttpUtilsBuilder;

import java.io.Closeable;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mcp 连接工具提供者
 *
 * @author noear
 * @since 3.1
 */
public class McpClientToolProvider implements ToolProvider, Closeable {
    private final McpSyncClient client;

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
        if (Utils.isEmpty(clientProps.getApiUrl())) {
            throw new IllegalArgumentException("ApiUrl is empty!");
        }

        URI url = URI.create(clientProps.getApiUrl());
        String baseUri = url.getScheme() + "://" + url.getAuthority();
        String sseEndpoint = url.getPath();

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

        McpClientTransport clientTransport = WebRxSseClientTransport.builder(webBuilder)
                .sseEndpoint(sseEndpoint)
                .build();

        this.client = McpClient.sync(clientTransport)
                .clientInfo(new McpSchema.Implementation(clientProps.getName(), clientProps.getVersion()))
                .requestTimeout(clientProps.getRequestTimeout())
                .initializationTimeout(clientProps.getInitializationTimeout())
                .build();
    }

    /**
     * 初始化
     */
    protected AtomicBoolean initialized = new AtomicBoolean(false);

    protected McpSyncClient getClient() {
        if (initialized.compareAndSet(false, true)) {
            client.initialize();
        }
        return client;
    }

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
    }

    /// ///////////

    protected Map<String, FunctionTool> toolsCached;

    protected Map<String, FunctionTool> getToolsCached() {
        if (toolsCached == null) {
            Utils.locker().lock();
            try {
                if (toolsCached == null) {
                    toolsCached = new LinkedHashMap<>();

                    McpSchema.ListToolsResult result = getClient().listTools();
                    for (McpSchema.Tool tool : result.getTools()) {
                        String name = tool.getName();
                        String description = tool.getDescription();
                        ONode parametersNode = ONode.load(tool.getInputSchema());

                        RefererFunctionTool functionRefer = new RefererFunctionTool(
                                name,
                                description,
                                parametersNode,
                                args -> callToolAsText(name, args));

                        toolsCached.put(functionRefer.name(), functionRefer);
                    }
                }
            } finally {
                Utils.locker().unlock();
            }
        }

        return toolsCached;
    }

    /**
     * 获取工具
     */
    public FunctionTool getTool(String name) {
        return getToolsCached().get(name);
    }

    /**
     * 转为聊天函数（用于模型绑定）
     */
    @Override
    public Collection<FunctionTool> getTools() {
        return getToolsCached().values();
    }

    @Override
    public void close() {
        if (client != null) {
            client.close();
        }
    }
}