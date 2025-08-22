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

import org.noear.snack.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.server.prompt.FunctionPrompt;
import org.noear.solon.ai.mcp.server.prompt.PromptProvider;
import org.noear.solon.ai.mcp.server.resource.FunctionResource;
import org.noear.solon.ai.mcp.server.resource.ResourceProvider;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.ResourceUtil;
import org.noear.solon.net.http.HttpTimeout;

import java.io.Closeable;
import java.io.IOException;
import java.net.URL;
import java.util.*;

/**
 * Mcp 提供者集合（用于对接 mcpServers 配置）
 *
 * @author noear
 * @since 3.3
 */
public class McpProviders implements ToolProvider, ResourceProvider, PromptProvider, Closeable {
    private final Map<String, McpClientProvider> providers;

    public McpProviders(Map<String, McpClientProvider> providers) {
        this.providers = providers;
    }

    /**
     * 获取所有提供者
     */
    public Map<String, McpClientProvider> getProviders() {
        return Collections.unmodifiableMap(providers);
    }

    /**
     * 获取提供者
     *
     * @param key 服务名
     */
    public McpClientProvider getProvider(String key) {
        return providers.get(key);
    }

    /**
     * 数量
     */
    public int size() {
        return providers.size();
    }

    /**
     * 关闭
     */
    @Override
    public void close() throws IOException {
        for (Map.Entry<String, McpClientProvider> entry : providers.entrySet()) {
            entry.getValue().close();
        }
    }

    /**
     * 获取工具
     */
    @Override
    public Collection<FunctionTool> getTools() {
        List<FunctionTool> tools = new ArrayList<>();
        for (Map.Entry<String, McpClientProvider> entry : providers.entrySet()) {
            tools.addAll(entry.getValue().getTools());
        }
        return tools;
    }

    /**
     * 获取提示语
     */
    @Override
    public Collection<FunctionPrompt> getPrompts() {
        List<FunctionPrompt> prompts = new ArrayList<>();
        for (Map.Entry<String, McpClientProvider> entry : providers.entrySet()) {
            prompts.addAll(entry.getValue().getPrompts());
        }
        return prompts;
    }

    /**
     * 获取资源
     */
    @Override
    public Collection<FunctionResource> getResources() {
        List<FunctionResource> resources = new ArrayList<>();
        for (Map.Entry<String, McpClientProvider> entry : providers.entrySet()) {
            resources.addAll(entry.getValue().getResources());
        }
        return resources;
    }


    /// /////////////////


    /**
     * 根据 mcpServers 配置解析出参数
     *
     * @param uri 配置资源地址
     */
    public static Map<String, McpServerParameters> parseMcpServers(String uri) throws IOException {
        Assert.notEmpty(uri, "uri is empty");

        URL res = ResourceUtil.findResource(uri);
        String json = ResourceUtil.getResourceAsString(res);
        ONode jsonDom = ONode.loadStr(json);

        return parseMcpServers(jsonDom);
    }

    /**
     * 根据 mcpServers 配置解析出参数
     *
     * @param configDom 配置文档
     */
    public static Map<String, McpServerParameters> parseMcpServers(ONode configDom) throws IOException {
        Assert.notNull(configDom, "configDom is null");

        ONode mcpServersNode = configDom.getOrNull("mcpServers");
        if (mcpServersNode == null) {
            mcpServersNode = configDom;
        }

        Map<String, McpServerParameters> mcpServers = mcpServersNode
                .toObject(new HashMap<String, McpServerParameters>() {
                }.getClass());

        return mcpServers;
    }

    /**
     * 根据 mcpServers 配置加载客户端
     *
     * @param uri 配置资源地址
     */
    public static McpProviders fromMcpServers(String uri) throws IOException {
        Map<String, McpServerParameters> mcpServers = parseMcpServers(uri);

        return fromMcpServers(mcpServers);
    }

    /**
     * 根据 mcpServers 配置加载客户端
     *
     * @param configDom 配置文档
     */
    public static McpProviders fromMcpServers(ONode configDom) throws IOException {
        Map<String, McpServerParameters> mcpServers = parseMcpServers(configDom);

        return fromMcpServers(mcpServers);
    }

    /**
     * 根据 mcpServers 配置加载客户端
     *
     * @param mcpServers 配置集合
     */
    public static McpProviders fromMcpServers(Map<String, McpServerParameters> mcpServers) throws IOException {
        Map<String, McpClientProvider> mcpClients = new HashMap<>();

        if (Utils.isNotEmpty(mcpServers)) {
            for (Map.Entry<String, McpServerParameters> kv : mcpServers.entrySet()) {
                McpClientProvider mcpClient = fromMcpServer(kv.getValue());

                mcpClients.put(kv.getKey(), mcpClient);
            }
        }

        return new McpProviders(mcpClients);
    }

    /**
     * 根据 serverParameters 配置加载客户端
     *
     * @param serverParameters 配置参数
     */
    public static McpClientProvider fromMcpServer(McpServerParameters serverParameters) throws IOException {
        Assert.notNull(serverParameters, "serverParameters is null");

        String type = Utils.valueOr(serverParameters.getType(), serverParameters.getTransport());

        if (Utils.isEmpty(type)) {
            //兼容没有 type 配置的情况
            if (Utils.isNotEmpty(serverParameters.getUrl())) {
                throw new IllegalArgumentException("The type or transport  is required");
            } else {
                type = McpChannel.STDIO;
            }
        }


        McpClientProvider.Builder builder = McpClientProvider.builder();

        builder.channel(type);

        if (McpChannel.STDIO.equalsIgnoreCase(type)) {
            builder.command(serverParameters.getCommand());
            builder.args(serverParameters.getArgs());
            builder.env(serverParameters.getEnv());
        } else {
            builder.apiUrl(serverParameters.getUrl());
            builder.headerSet(serverParameters.getEnv());
            builder.headerSet(serverParameters.getHeaders());

            if (serverParameters.getTimeout() != null) {
                builder.httpTimeout(HttpTimeout.of((int) serverParameters.getTimeout().getSeconds()));
                builder.requestTimeout(serverParameters.getTimeout());
                builder.initializationTimeout(serverParameters.getTimeout());
            }
        }

        return builder.build();
    }
}