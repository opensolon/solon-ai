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
import org.noear.solon.Utils;
import org.noear.solon.ai.image.Image;
import org.noear.solon.ai.mcp.exception.McpException;
import java.io.Closeable;
import java.io.IOException;
import java.util.Map;

/**
 * Mcp 客户端简化版
 *
 * @author noear
 * @since 3.1
 */
public class McpClientSimp implements Closeable {
    private String baseUri;
    private String sseEndpoint;
    private HttpClientSseClientTransport clientTransport;
    private McpSyncClient mcpClient;

    public McpClientSimp(String baseUri, String sseEndpoint) {
        this.baseUri = baseUri;
        this.sseEndpoint = sseEndpoint;

        this.clientTransport = HttpClientSseClientTransport.builder(baseUri)
                .sseEndpoint(sseEndpoint)
                .build();

        this.mcpClient = McpClient.sync(clientTransport)
                .clientInfo(new McpSchema.Implementation("Solon-Mcp-Client", "0.0.1"))
                .build();

        this.mcpClient.initialize();
    }

    public String callToolAsText(String name, Map<String, Object> args) {
        McpSchema.CallToolResult result = callTool(name, args);
        if (Utils.isEmpty(result.content())) {
            return null;
        } else {
            return ((McpSchema.TextContent) result.content().get(0)).text();
        }
    }

    public Image callToolAsImage(String name, Map<String, Object> args) {
        McpSchema.CallToolResult result = callTool(name, args);
        if (Utils.isEmpty(result.content())) {
            return null;
        } else {
            McpSchema.ImageContent imageContent = ((McpSchema.ImageContent) result.content().get(0));
            return Image.ofBase64(imageContent.data(), imageContent.mimeType());
        }
    }

    public McpSchema.CallToolResult callTool(String name, Map<String, Object> args) {
        McpSchema.CallToolRequest callToolRequest = new McpSchema.CallToolRequest(name, args);
        McpSchema.CallToolResult response = mcpClient.callTool(callToolRequest);

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

    @Override
    public void close() throws IOException {
        if (mcpClient != null) {
            mcpClient.close();
        }
    }
}