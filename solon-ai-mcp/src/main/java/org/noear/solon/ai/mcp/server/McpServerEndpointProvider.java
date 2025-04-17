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
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.WebRxSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.noear.snack.ONode;
import org.noear.solon.Solon;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.core.Props;
import org.noear.solon.core.bean.LifecycleBean;
import org.noear.solon.core.util.PathUtil;
import org.noear.solon.core.util.RunUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Arrays;
import java.util.Properties;

/**
 * Mcp 服务端点提供者
 *
 * @author noear
 * @since 3.1
 */
public class McpServerEndpointProvider implements LifecycleBean {
    private static Logger log = LoggerFactory.getLogger(McpServerEndpointProvider.class);
    private final WebRxSseServerTransportProvider mcpTransportProvider;
    private final McpServer.SyncSpecification mcpServerSpec;
    private final McpServerProperties serverProperties;
    private final String sseEndpoint;
    private final String messageEndpoint;

    public McpServerEndpointProvider(Properties properties) {
        this(Props.from(properties).bindTo(new McpServerProperties()));
    }

    public McpServerEndpointProvider(McpServerProperties serverProperties) {
        this.serverProperties = serverProperties;
        this.sseEndpoint = serverProperties.getSseEndpoint();
        this.messageEndpoint = PathUtil.mergePath(this.sseEndpoint, "message");

        //如果启用了
        this.mcpTransportProvider = WebRxSseServerTransportProvider.builder()
                .messageEndpoint(this.messageEndpoint)
                .sseEndpoint(this.sseEndpoint)
                .objectMapper(new ObjectMapper())
                .build();

        this.mcpServerSpec = McpServer.sync(this.mcpTransportProvider)
                .serverInfo(serverProperties.getName(), serverProperties.getVersion());

    }

    /**
     * 名字
     */
    public String getName() {
        return serverProperties.getName();
    }

    /**
     * 端点
     */
    public String getSseEndpoint() {
        return sseEndpoint;
    }

    /**
     * 获取传输提供者
     */
    public WebRxSseServerTransportProvider getTransport() {
        return mcpTransportProvider;
    }

    /**
     * 登记工具
     */
    public void addTool(FunctionTool functionTool) {
        addToolSpec(functionTool);
    }

    /**
     * 登记工具
     */
    public void addTool(ToolProvider toolProvider) {
        for (FunctionTool functionTool : toolProvider.getTools()) {
            addToolSpec(functionTool);
        }
    }

    /**
     * 移除工具
     */
    public void removeTool(String toolName) {
        if (server != null) {
            server.removeTool(toolName);
        }
    }

    /**
     * 移除工具
     */
    public void removeTool(ToolProvider toolProvider) {
        if (server != null) {
            for (FunctionTool functionTool : toolProvider.getTools()) {
                server.removeTool(functionTool.name());
            }
        }
    }

    /**
     * 通知工具变化
     */
    public void notifyToolsListChanged() {
        if (server != null) {
            server.notifyToolsListChanged();
        }
    }

    private McpSyncServer server;

    @Override
    public void start() throws Throwable {
        mcpTransportProvider.toHttpHandler(Solon.app());
    }


    @Override
    public void postStart() throws Throwable {
        server = mcpServerSpec.build();

        log.info("Mcp-Server started, name={}, version={}, sseEndpoint={}, messageEndpoint={}, toolRegistered={}",
                serverProperties.getName(),
                serverProperties.getVersion(),
                this.sseEndpoint,
                this.messageEndpoint,
                toolCount);

        if (serverProperties.isEnabledSseHeartbeat()) {
            //启用 sse 心跳（保持客户端不断开）
            RunUtil.delayAndRepeat(() -> {
                RunUtil.runAndTry(() -> {
                    mcpTransportProvider.sendHeartbeat();
                });
            }, serverProperties.getSseHeartbeatInterval().toMillis());
        }
    }

    @Override
    public void stop() throws Throwable {
        if (server != null) {
            server.close();
        }
    }

    private int toolCount = 0;

    protected void addToolSpec(FunctionTool functionTool) {
        ONode jsonSchema = buildJsonSchema(functionTool);
        String jsonSchemaStr = jsonSchema.toJson();

        McpServerFeatures.SyncToolSpecification toolSpec = new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool(functionTool.name(), functionTool.description(), jsonSchemaStr),
                (exchange, request) -> {
                    McpSchema.CallToolResult toolResult = null;
                    try {
                        String rst = functionTool.handle(request);
                        toolResult = new McpSchema.CallToolResult(Arrays.asList(new McpSchema.TextContent(rst)), false);
                    } catch (Throwable ex) {
                        toolResult = new McpSchema.CallToolResult(Arrays.asList(new McpSchema.TextContent(ex.getMessage())), true);
                    }

                    return toolResult;
                });

        toolCount++;
        if (server != null) {
            server.addTool(toolSpec);
        } else {
            mcpServerSpec.tools(toolSpec);
        }
    }

    protected ONode buildJsonSchema(FunctionTool functionTool) {
        ONode jsonSchema = new ONode();
        jsonSchema.set("$schema", "http://json-schema.org/draft-07/schema#");
        jsonSchema.setAll(functionTool.inputSchema());

        return jsonSchema;
    }

    /// //////////////////////////////////////////////

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private McpServerProperties props = new McpServerProperties();

        /**
         * 名字
         */
        public Builder name(String name) {
            props.setName(name);
            return this;
        }

        /**
         * 版本号
         */
        public Builder version(String version) {
            props.setVersion(version);
            return this;
        }

        /**
         * SSE 端点
         */
        public Builder sseEndpoint(String sseEndpoint) {
            props.setSseEndpoint(sseEndpoint);
            return this;
        }

        /**
         * 是否启用 SSE 心跳（保活）
         */
        public Builder enabledSseHeartbeat(boolean enabledSseHeartbeat) {
            props.setEnabledSseHeartbeat(enabledSseHeartbeat);
            return this;
        }

        /**
         * SSE 心跳间隔
         */
        public Builder sseHeartbeatInterval(Duration sseHeartbeatInterval) {
            props.setSseHeartbeatInterval(sseHeartbeatInterval);
            return this;
        }

        /**
         * 构建
         */
        public McpServerEndpointProvider build() {
            return new McpServerEndpointProvider(props);
        }
    }
}