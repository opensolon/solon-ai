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
package org.noear.solon.ai.mcp.server.manager;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.mcp.exception.McpException;
import org.noear.solon.ai.mcp.server.McpServerContext;
import org.noear.solon.ai.mcp.server.McpServerProperties;
import org.noear.solon.core.handle.Context;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 有状态工具注册表
 *
 * @author noear
 * @since 3.2
 */
public class StatefulToolRegistry implements McpPrimitivesRegistry<FunctionTool> {
    private final Map<String, FunctionTool> toolsMap = new ConcurrentHashMap<>();

    private final Supplier<McpAsyncServer> serverSupplier;
    private final McpServer.AsyncSpecification mcpServerSpec;

    public StatefulToolRegistry(Supplier<McpAsyncServer> serverSupplier, McpServer.AsyncSpecification mcpServerSpec) {
        this.serverSupplier = serverSupplier;
        this.mcpServerSpec = mcpServerSpec;
    }

    @Override
    public int count() {
        return toolsMap.size();
    }

    @Override
    public Collection<FunctionTool> all() {
        return toolsMap.values();
    }

    @Override
    public boolean contains(String toolName) {
        return toolsMap.containsKey(toolName);
    }

    @Override
    public void remove(String toolName) {
        if (serverSupplier.get() != null) {
            serverSupplier.get().removeTool(toolName).block();
            toolsMap.remove(toolName);
        }
    }

    @Override
    public void add(McpServerProperties mcpServerProps, FunctionTool functionTool) {
        try {
            //内部登记
            toolsMap.put(functionTool.name(), functionTool);

            // 构建 inputSchema JSON 字符串
            String inSchemaJson = buildJsonSchema(functionTool).toJson();

            McpSchema.ToolAnnotations toolAnnotations = McpSchema.ToolAnnotations.builder()
                    .returnDirect(functionTool.returnDirect())
                    .build();


            McpSchema.Tool.Builder toolBuilder = McpSchema.Tool.builder()
                    .name(functionTool.name()).title(functionTool.title()).description(functionTool.description())
                    .annotations(toolAnnotations)
                    .inputSchema(McpJsonMapper.getDefault(), inSchemaJson);

            if (mcpServerProps.isEnableOutputSchema() && Utils.isNotEmpty(functionTool.outputSchema())) {
                toolBuilder.outputSchema(McpJsonMapper.getDefault(), functionTool.outputSchema());
            }

            // 注册实际调用逻辑
            McpServerFeatures.AsyncToolSpecification toolSpec = new McpServerFeatures.AsyncToolSpecification(
                    toolBuilder.build(),
                    (exchange, request) -> {
                        return Mono.create(sink -> {
                            Context.currentWith(new McpServerContext(exchange, exchange.transportContext()), () -> {
                                functionTool.handleAsync(request).whenComplete((rst, err) -> {
                                    final McpSchema.CallToolResult result;

                                    if (err != null) {
                                        err = Utils.throwableUnwrap(err);
                                        result = new McpSchema.CallToolResult(Arrays.asList(new McpSchema.TextContent(err.getMessage())), true);
                                    } else {

                                        if (mcpServerProps.isEnableOutputSchema() && Utils.isNotEmpty(functionTool.outputSchema())) {
                                            Map<String, Object> map = ONode.deserialize(rst, Map.class);
                                            result = new McpSchema.CallToolResult(Arrays.asList(new McpSchema.TextContent(rst)), false, map);
                                        } else {
                                            result = new McpSchema.CallToolResult(Arrays.asList(new McpSchema.TextContent(rst)), false);
                                        }
                                    }

                                    sink.success(result);
                                });
                            });
                        });
                    });

            if (serverSupplier.get() != null) {
                serverSupplier.get().addTool(toolSpec).block();
            } else {
                mcpServerSpec.tools(toolSpec).build();
            }
        } catch (Throwable ex) {
            throw new McpException("Tool add failed, tool: " + functionTool.name(), ex);
        }
    }

    protected ONode buildJsonSchema(FunctionTool functionTool) {
        ONode jsonSchema = new ONode();
        jsonSchema.set("$schema", "http://json-schema.org/draft-07/schema#");
        jsonSchema.setAll(ONode.ofJson(functionTool.inputSchema()).getObject());

        return jsonSchema;
    }
}