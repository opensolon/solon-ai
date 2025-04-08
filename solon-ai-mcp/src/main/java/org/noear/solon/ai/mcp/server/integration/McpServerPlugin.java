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
import org.noear.solon.ai.chat.annotation.FunctionMapping;
import org.noear.solon.ai.chat.function.ChatFunction;
import org.noear.solon.ai.chat.function.ChatFunctionParam;
import org.noear.solon.ai.chat.function.MethodChatFunction;
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

            context.beanExtractorAdd(FunctionMapping.class, (bw, method, anno) -> {
                ChatFunction chatFunction = new MethodChatFunction(bw.raw(), method);
                addToolSpec(mcpServerSpec, chatFunction);
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


    private void addToolSpec(McpServer.AsyncSpecification mcpServerSpec, ChatFunction chatFunction) {
        ONode jsonSchema = buildJsonSchema(chatFunction);
        String jsonSchemaStr = jsonSchema.toJson();

        McpServerFeatures.AsyncToolSpecification toolSpec = new McpServerFeatures.AsyncToolSpecification(
                new McpSchema.Tool(chatFunction.name(), chatFunction.description(), jsonSchemaStr),
                (exchange, request) -> {
                    McpSchema.CallToolResult toolResult = null;
                    try {
                        String rst = chatFunction.handle(request);
                        toolResult = new McpSchema.CallToolResult(Arrays.asList(new McpSchema.TextContent(rst)), false);
                    } catch (Throwable ex) {
                        toolResult = new McpSchema.CallToolResult(Arrays.asList(new McpSchema.TextContent(ex.getMessage())), true);
                    }

                    return Mono.just(toolResult);
                });

        mcpServerSpec.tools(toolSpec);
    }

    protected ONode buildJsonSchema(ChatFunction chatFunction) {
        ONode jsonSchema = new ONode();
        jsonSchema.set("$schema", "http://json-schema.org/draft-07/schema#");
        jsonSchema.set("type", "object");


        ONode n4r = new ONode(jsonSchema.options()).asArray();
        jsonSchema.getOrNew("properties").build(n5 -> {
            for (ChatFunctionParam p1 : chatFunction.params()) {
                n5.getOrNew(p1.name()).build(n6 -> {
                    buildJsonSchemaParamNodeDo(p1, n6);
                });

                if (p1.required()) {
                    n4r.add(p1.name());
                }
            }
        });
        jsonSchema.set("required", n4r);

        return jsonSchema;
    }

    /**
     * 字符串形态
     */
    protected void buildJsonSchemaParamNodeDo(ChatFunctionParam p1, ONode paramNode) {
        String typeStr = p1.type().getSimpleName().toLowerCase();
        if (p1.type().isArray()) {
            paramNode.set("type", "array");
            String typeItem = typeStr.substring(0, typeStr.length() - 2); //int[]

            paramNode.getOrNew("items").set("type", typeItem);
        } else if (p1.type().isEnum()) {
            paramNode.set("type", typeStr);

            paramNode.getOrNew("enum").build(n7 -> {
                for (Object e : p1.type().getEnumConstants()) {
                    n7.add(e.toString());
                }
            });
        } else {
            paramNode.set("type", typeStr);
        }

        paramNode.set("description", p1.description());
    }
}