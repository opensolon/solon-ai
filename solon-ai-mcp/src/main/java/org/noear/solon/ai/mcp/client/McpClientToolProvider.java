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

import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.ai.mcp.client.properties.McpConnectionProperties;
import org.noear.solon.ai.mcp.client.properties.McpClientProperties;

import java.io.Closeable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mcp 客户端工具提供者
 *
 * @author noear
 * @since 3.1
 */
public class McpClientToolProvider implements ToolProvider, Closeable {
    private final Map<String, McpConnectionToolProvider> toolProviders = new ConcurrentHashMap<>();

    public McpClientToolProvider(McpClientProperties clientProps) {
        for (Map.Entry<String, McpConnectionProperties> entry : clientProps.getConnections().entrySet()) {
            McpConnectionToolProvider clientToolProvider = new McpConnectionToolProvider(clientProps, entry.getValue());
            toolProviders.put(entry.getKey(), clientToolProvider);
        }
    }

    /**
     * 添加连接
     */
    public void addConnection(String name, McpConnectionToolProvider toolProvider) {
        toolProviders.put(name, toolProvider);
    }

    /**
     * 获取连接
     */
    public McpConnectionToolProvider getConnection(String name) {
        return toolProviders.get(name);
    }

    @Override
    public Collection<FunctionTool> getTools() {
        List<FunctionTool> tools = new ArrayList<>();

        for (McpConnectionToolProvider connToolProvider : toolProviders.values()) {
            tools.addAll(connToolProvider.getTools());
        }

        return tools;
    }

    @Override
    public void close() {
        for (McpConnectionToolProvider connToolProvider : toolProviders.values()) {
            connToolProvider.close();
        }
    }
}