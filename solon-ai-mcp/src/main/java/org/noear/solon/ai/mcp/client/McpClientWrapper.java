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
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.noear.snack.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.function.ChatFunction;
import org.noear.solon.ai.chat.function.ChatFunctionDecl;
import org.noear.solon.ai.chat.function.ToolSchemaUtil;
import org.noear.solon.ai.image.Image;
import org.noear.solon.ai.mcp.exception.McpException;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Mcp 客户端简化版
 *
 * @author noear
 * @since 3.1
 */
public class McpClientWrapper implements Closeable {
    private McpSyncClient real;

    public McpClientWrapper(String baseUri, String sseEndpoint) {
        HttpClientSseClientTransport mcpClientTransport = HttpClientSseClientTransport.builder(baseUri)
                .sseEndpoint(sseEndpoint)
                .build();

        this.real = McpClient.sync(mcpClientTransport)
                .clientInfo(new McpSchema.Implementation("Solon-Mcp-Client", "0.0.1"))
                .build();
        this.real.initialize();
    }

    /**
     * 调用工具并转为文本
     *
     * @param name 工具名
     * @param args 调用参数
     */
    public String callToolAsText(String name, Map<String, Object> args) {
        McpSchema.CallToolResult result = callTool(name, args);
        if (Utils.isEmpty(result.content())) {
            return null;
        } else {
            return ((McpSchema.TextContent) result.content().get(0)).text();
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
        if (Utils.isEmpty(result.content())) {
            return null;
        } else {
            McpSchema.ImageContent imageContent = ((McpSchema.ImageContent) result.content().get(0));
            return Image.ofBase64(imageContent.data(), imageContent.mimeType());
        }
    }

    /**
     * 调用工具
     *
     * @param name 工具名
     * @param args 调用参数
     */
    public McpSchema.CallToolResult callTool(String name, Map<String, Object> args) {
        McpSchema.CallToolRequest callToolRequest = new McpSchema.CallToolRequest(name, args);
        McpSchema.CallToolResult response = real.callTool(callToolRequest);

        if (response.isError() == null || response.isError() == false) {
            return response;
        } else {
            if (Utils.isEmpty(response.content())) {
                throw new McpException("Call Toll Failed");
            } else {
                throw new McpException(response.content().get(0).toString());
            }
        }
    }

    private Collection<ChatFunction> functions;

    /**
     * 转为聊天函数（用于模型绑定）
     */
    public Collection<ChatFunction> toFunctions() {
        return toFunctions(true);
    }

    /**
     * 转为聊天函数（用于模型绑定）
     */
    public Collection<ChatFunction> toFunctions(boolean cached) {
        if (cached) {
            if (functions != null) {
                return functions;
            }
        }

        functions = buildFunctions();
        return functions;
    }

    protected Collection<ChatFunction> buildFunctions() {
        List<ChatFunction> functionList = new ArrayList<>();

        McpSchema.ListToolsResult result = real.listTools();
        for (McpSchema.Tool tool : result.tools()) {
            ChatFunctionDecl functionDecl = new ChatFunctionDecl(tool.name());
            functionDecl.description(tool.description());

            ONode parametersNode = ONode.load(tool.inputSchema());

            ToolSchemaUtil.parseToolParametersNode(functionDecl, parametersNode);

            functionDecl.handle((arg) -> {
                return callToolAsText(functionDecl.name(), arg);
            });

            functionList.add(functionDecl);
        }

        return functionList;
    }

    @Override
    public void close() throws IOException {
        if (real != null) {
            real.close();
        }
    }
}