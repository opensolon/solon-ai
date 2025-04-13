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
package org.noear.solon.ai.mcp.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.WebRxSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.noear.snack.ONode;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.core.AppContext;
import org.noear.solon.core.Lifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Arrays;

/**
 * Mcp 服务端提供者
 *
 * @author noear
 * @since 3.1
 */
public class McpServerProvider implements Lifecycle {
    private static Logger log = LoggerFactory.getLogger(McpServerProvider.class);
    private final AppContext appContext;
    private final WebRxSseServerTransportProvider mcpTransportProvider;
    private final McpServer.AsyncSpecification mcpServerSpec;
    private final McpServerProperties serverProperties;

    public McpServerProvider(AppContext appContext, McpServerProperties serverProperties) {
        this.appContext = appContext;
        this.serverProperties = serverProperties;

        //如果启用了
        this.mcpTransportProvider = WebRxSseServerTransportProvider.builder()
                .messageEndpoint(serverProperties.getMessageEndpoint())
                .sseEndpoint(serverProperties.getSseEndpoint())
                .objectMapper(new ObjectMapper())
                .build();

        this.mcpServerSpec = McpServer.async(this.mcpTransportProvider)
                .serverInfo(serverProperties.getName(), serverProperties.getVersion());
    }

    /**
     * 登记工具
     */
    public void addTool(FunctionTool functionTool) {
        addToolSpec(mcpServerSpec, functionTool);
    }

    /**
     * 登记工具
     */
    public void addTool(ToolProvider toolProvider) {
        for (FunctionTool functionTool : toolProvider.getTools()) {
            addToolSpec(mcpServerSpec, functionTool);
        }
    }

    @Override
    public void start() throws Throwable {
        mcpTransportProvider.toHttpHandler(appContext.app());
    }

    private McpAsyncServer server;

    @Override
    public void postStart() throws Throwable {
        server = mcpServerSpec.build();

        log.info("Mcp-Server started, name={}, version={}, sseEndpoint={}",
                serverProperties.getName(),
                serverProperties.getVersion(),
                serverProperties.getSseEndpoint());
    }

    @Override
    public void stop() throws Throwable {
        if (server != null) {
            server.close();
        }
    }


    protected void addToolSpec(McpServer.AsyncSpecification mcpServerSpec, FunctionTool functionTool) {
        ONode jsonSchema = buildJsonSchema(functionTool);
        String jsonSchemaStr = jsonSchema.toJson();

        McpServerFeatures.AsyncToolSpecification toolSpec = new McpServerFeatures.AsyncToolSpecification(
                new McpSchema.Tool(functionTool.name(), functionTool.description(), jsonSchemaStr),
                (exchange, request) -> {
                    McpSchema.CallToolResult toolResult = null;
                    try {
                        String rst = functionTool.handle(request);
                        toolResult = new McpSchema.CallToolResult(Arrays.asList(new McpSchema.TextContent(rst)), false);
                    } catch (Throwable ex) {
                        toolResult = new McpSchema.CallToolResult(Arrays.asList(new McpSchema.TextContent(ex.getMessage())), true);
                    }

                    return Mono.just(toolResult);
                });

        mcpServerSpec.tools(toolSpec);
    }

    protected ONode buildJsonSchema(FunctionTool functionTool) {
        ONode jsonSchema = new ONode();
        jsonSchema.set("$schema", "http://json-schema.org/draft-07/schema#");
        jsonSchema.setAll(functionTool.inputSchema());

        return jsonSchema;
    }
}