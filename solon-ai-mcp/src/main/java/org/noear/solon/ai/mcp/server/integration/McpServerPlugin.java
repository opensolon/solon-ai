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
package org.noear.solon.ai.mcp.server.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.WebRxSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.noear.snack.ONode;
import org.noear.solon.ai.chat.annotation.ToolMapping;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.MethodFunctionTool;
import org.noear.solon.ai.mcp.server.McpServerProperties;
import org.noear.solon.core.*;
import reactor.core.publisher.Mono;

import java.util.Arrays;

/**
 * @author noear
 * @since 3.1
 */
public class McpServerPlugin implements Plugin {
    private WebRxSseServerTransportProvider mcpTransportProvider;
    private McpServer.AsyncSpecification mcpServerSpec;

    @Override
    public void start(AppContext context) throws Throwable {
        McpServerProperties serverProperties = context.cfg().bindTo(McpServerProperties.class);

        if (serverProperties.isEnabled()) {
            //如果启用了
            mcpTransportProvider = WebRxSseServerTransportProvider.builder()
                    .messageEndpoint(serverProperties.getMessageEndpoint())
                    .sseEndpoint(serverProperties.getSseEndpoint())
                    .objectMapper(new ObjectMapper())
                    .build();

            mcpServerSpec = McpServer.async(mcpTransportProvider)
                    .serverInfo(serverProperties.getName(), serverProperties.getVersion());

            context.beanExtractorAdd(ToolMapping.class, (bw, method, anno) -> {
                FunctionTool functionTool = new MethodFunctionTool(bw.raw(), method);
                addToolSpec(mcpServerSpec, functionTool);
            });

            mcpTransportProvider.toHttpHandler(context.app());

            context.lifecycle(new Lifecycle() {
                @Override
                public void start() throws Throwable {

                }

                @Override
                public void postStart() throws Throwable {
                    mcpServerSpec.build();
                }
            });
        }
    }


    private void addToolSpec(McpServer.AsyncSpecification mcpServerSpec, FunctionTool functionTool) {
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