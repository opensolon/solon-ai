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

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.mcp.exception.McpException;
import org.noear.solon.ai.mcp.server.McpServerContext;
import org.noear.solon.ai.mcp.server.McpServerProperties;
import org.noear.solon.core.handle.ContextHolder;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具服务端管理
 *
 * @author noear
 * @since 3.2
 */
public class ToolMcpServerManager implements McpServerManager<FunctionTool> {
    private final Map<String, FunctionTool> toolsMap = new ConcurrentHashMap<>();

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
    public void remove(McpSyncServer server, String toolName) {
        if (server != null) {
            server.removeTool(toolName);
            toolsMap.remove(toolName);
        }
    }

    @Override
    public void add(McpSyncServer server, McpServer.SyncSpecification mcpServerSpec, McpServerProperties mcpServerProps, FunctionTool functionTool) {
        try {
            //内部登记
            toolsMap.put(functionTool.name(), functionTool);

            // 构建 inputSchema JSON 字符串
            String inSchemaJson = buildJsonSchema(functionTool).toJson();

            McpSchema.ToolAnnotations toolAnnotations = new McpSchema.ToolAnnotations();
            toolAnnotations.setReturnDirect(functionTool.returnDirect());

            McpSchema.Tool.Builder toolBuilder = McpSchema.Tool.builder()
                    .name(functionTool.name()).title(functionTool.title()).description(functionTool.description())
                    .annotations(toolAnnotations)
                    .inputSchema(inSchemaJson);

            if(mcpServerProps.isEnableOutputSchema() && Utils.isNotEmpty(functionTool.outputSchema())){
                toolBuilder.outputSchema(functionTool.outputSchema());
            }

            // 注册实际调用逻辑
            McpServerFeatures.SyncToolSpecification toolSpec = new McpServerFeatures.SyncToolSpecification(
                    toolBuilder.build(),
                    (exchange, request) -> {
                        try {
                            ContextHolder.currentSet(new McpServerContext(exchange));

                            String rst = functionTool.handle(request);

                            if(mcpServerProps.isEnableOutputSchema() && Utils.isNotEmpty(functionTool.outputSchema())){
                                Map<String,Object> map = ONode.deserialize(rst, Map.class);
                                return new McpSchema.CallToolResult(Arrays.asList(new McpSchema.TextContent(rst)), false, map);
                            } else {
                                return new McpSchema.CallToolResult(Arrays.asList(new McpSchema.TextContent(rst)), false);
                            }
                        } catch (Throwable ex) {
                            ex = Utils.throwableUnwrap(ex);
                            return new McpSchema.CallToolResult(Arrays.asList(new McpSchema.TextContent(ex.getMessage())), true);
                        } finally {
                            ContextHolder.currentRemove();
                        }
                    });

            if (server != null) {
                server.addTool(toolSpec);
            } else {
                mcpServerSpec.tools(toolSpec);
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