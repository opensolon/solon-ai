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
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.server.transport.WebRxSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import org.noear.snack.ONode;
import org.noear.solon.Solon;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.exception.McpException;
import org.noear.solon.ai.mcp.server.annotation.McpServerEndpoint;
import org.noear.solon.ai.mcp.tool.FunctionPrompt;
import org.noear.solon.ai.mcp.tool.FunctionResource;
import org.noear.solon.core.Props;
import org.noear.solon.core.bean.LifecycleBean;
import org.noear.solon.core.util.ConvertUtil;
import org.noear.solon.core.util.PathUtil;
import org.noear.solon.core.util.RunUtil;
import org.noear.solon.lang.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mcp 服务端点提供者
 *
 * @author noear
 * @since 3.1
 */
public class McpServerEndpointProvider implements LifecycleBean {
    private static Logger log = LoggerFactory.getLogger(McpServerEndpointProvider.class);
    private final McpServerTransportProvider mcpTransportProvider;
    private final McpServer.SyncSpecification mcpServerSpec;
    private final McpServerProperties serverProperties;
    private final String sseEndpoint;
    private final String messageEndpoint;
    private final Map<String, FunctionTool> toolsMap = new ConcurrentHashMap<>();

    public McpServerEndpointProvider(Properties properties) {
        this(Props.from(properties).bindTo(new McpServerProperties()));
    }

    public McpServerEndpointProvider(McpServerProperties serverProperties) {
        this.serverProperties = serverProperties;
        this.sseEndpoint = serverProperties.getSseEndpoint();
        this.messageEndpoint = PathUtil.mergePath(this.sseEndpoint, "message");

        if (McpChannel.STDIO.equalsIgnoreCase(serverProperties.getChannel())) {
            //stdio 通道
            this.mcpTransportProvider = new StdioServerTransportProvider();
        } else {
            //sse 通道
            this.mcpTransportProvider = WebRxSseServerTransportProvider.builder()
                    .messageEndpoint(this.messageEndpoint)
                    .sseEndpoint(this.sseEndpoint)
                    .objectMapper(new ObjectMapper())
                    .build();
        }

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
     * 版本
     */
    public String getVersion() {
        return serverProperties.getVersion();
    }

    /**
     * 通道
     */
    public String getChannel() {
        return serverProperties.getChannel();
    }

    /**
     * 端点
     */
    public String getSseEndpoint() {
        return sseEndpoint;
    }

    /**
     * 登记资源
     */
    public void addResource(FunctionResource functionResource) {
        McpServerFeatures.SyncResourceSpecification toolSpec = new McpServerFeatures.SyncResourceSpecification(
                new McpSchema.Resource(functionResource.uri(), functionResource.name(), functionResource.description(), functionResource.mimeType(), null),
                (exchange, request) -> {
                    try {
                        String text = functionResource.handle();
                        return new McpSchema.ReadResourceResult(Arrays.asList(new McpSchema.TextResourceContents(request.getUri(), text, functionResource.mimeType())));
                    } catch (Throwable ex) {
                        throw new McpException(ex.getMessage(), ex);
                    }
                });
    }

    /**
     * 登记资源
     */
    public void addPrompt(FunctionPrompt functionPrompt) {
//        McpServerFeatures.SyncPromptSpecification toolSpec = new McpServerFeatures.SyncPromptSpecification(
//                new McpSchema.Prompt(functionResource.uri(), functionResource.name(), functionResource.description(), functionResource.mimeType(), null),
//                (exchange, request) -> {
//                    try {
//                        String text = functionResource.handle();
//                        return new McpSchema.ReadResourceResult(Arrays.asList(new McpSchema.TextResourceContents(request.getUri(), text, functionResource.mimeType())));
//                    } catch (Throwable ex) {
//                        throw new McpException(ex.getMessage(), ex);
//                    }
//                });
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
     * 获取所有工具
     */
    public Collection<FunctionTool> getTools() {
        return toolsMap.values();
    }

    /**
     * 通知工具变化
     */
    public void notifyToolsListChanged() {
        if (server != null) {
            server.notifyToolsListChanged();
        }
    }

    /**
     * 通知资源变化
     */
    public void notifyResourcesListChanged() {
        if (server != null) {
            server.notifyResourcesListChanged();
        }
    }

    private McpSyncServer server;

    /**
     * 获取内部处理服务（postStart 后有效）
     */
    public @Nullable McpSyncServer getServer() {
        return server;
    }

    @Override
    public void start() {

    }

    @Override
    public void postStart() {
        server = mcpServerSpec.build();

        if (McpChannel.STDIO.equalsIgnoreCase(serverProperties.getChannel())) {
            log.info("Mcp-Server started, name={}, version={}, channel={}, toolRegistered={}",
                    serverProperties.getName(),
                    serverProperties.getVersion(),
                    McpChannel.STDIO,
                    toolCount);
        } else {
            log.info("Mcp-Server started, name={}, version={}, channel={}, sseEndpoint={}, messageEndpoint={}, toolRegistered={}",
                    serverProperties.getName(),
                    serverProperties.getVersion(),
                    McpChannel.SSE,
                    this.sseEndpoint,
                    this.messageEndpoint,
                    toolCount);
        }

        //如果是 web 类的
        if (mcpTransportProvider instanceof WebRxSseServerTransportProvider) {
            WebRxSseServerTransportProvider tmp = (WebRxSseServerTransportProvider) mcpTransportProvider;
            tmp.toHttpHandler(Solon.app());

            if (serverProperties.getHeartbeatInterval() != null
                    && serverProperties.getHeartbeatInterval().getSeconds() > 0) {
                //启用 sse 心跳（保持客户端不断开）
                RunUtil.delayAndRepeat(() -> {
                    RunUtil.runAndTry(() -> {
                        tmp.sendHeartbeat();
                    });
                }, serverProperties.getHeartbeatInterval().toMillis());
            }
        }
    }

    /**
     * 暂停（主要用于测试）
     */
    public boolean pause() {
        if (mcpTransportProvider instanceof WebRxSseServerTransportProvider) {
            WebRxSseServerTransportProvider tmp = (WebRxSseServerTransportProvider) mcpTransportProvider;

            //如果有注册
            if (Utils.isNotEmpty(Solon.app().router().getBy(tmp.getSseEndpoint()))) {
                Solon.app().router().remove(tmp.getSseEndpoint());
                return true;
            }
        }

        return false;
    }

    /**
     * 恢复（主要用于测试）
     */
    public boolean resume() {
        if (mcpTransportProvider instanceof WebRxSseServerTransportProvider) {
            WebRxSseServerTransportProvider tmp = (WebRxSseServerTransportProvider) mcpTransportProvider;

            //如果没有注册
            if (Utils.isEmpty(Solon.app().router().getBy(tmp.getSseEndpoint()))) {
                tmp.toHttpHandler(Solon.app());
                return true;
            }
        }

        return false;
    }

    @Override
    public void stop() {
        if (server != null) {
            server.close();
        }
    }

    private int toolCount = 0;

    protected void addToolSpec(FunctionTool functionTool) {
        //内部登记
        toolsMap.put(functionTool.name(), functionTool);

        //mcp 登记
        ONode jsonSchema = buildJsonSchema(functionTool);
        String jsonSchemaStr = jsonSchema.toJson();

        McpServerFeatures.SyncToolSpecification toolSpec = new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool(functionTool.name(), functionTool.description(), jsonSchemaStr),
                (exchange, request) -> {
                    try {
                        String rst = functionTool.handle(request);
                        return new McpSchema.CallToolResult(Arrays.asList(new McpSchema.TextContent(rst)), false);
                    } catch (Throwable ex) {
                        throw new McpException(ex.getMessage(), ex);
                    }
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
        jsonSchema.setAll(ONode.loadStr(functionTool.inputSchema()));

        return jsonSchema;
    }

    /// //////////////////////////////////////////////

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private McpServerProperties props = new McpServerProperties();

        public Builder from(Class<?> endpointClz, McpServerEndpoint endpointAnno) {
            //支持${配置}
            String name = Solon.cfg().getByTmpl(endpointAnno.name());
            String version = Solon.cfg().getByTmpl(endpointAnno.version());
            String channel = Solon.cfg().getByTmpl(endpointAnno.channel());
            String sseEndpoint = Solon.cfg().getByTmpl(endpointAnno.sseEndpoint());
            String heartbeatInterval = Solon.cfg().getByTmpl(endpointAnno.heartbeatInterval());


            if (Utils.isEmpty(name)) {
                props.setName(endpointClz.getSimpleName());
            } else {
                props.setName(name);
            }

            props.setVersion(version);
            props.setChannel(channel);
            props.setSseEndpoint(sseEndpoint);

            if (Utils.isEmpty(heartbeatInterval)) {
                props.setHeartbeatInterval(null); //表示不启用
            } else {
                props.setHeartbeatInterval(ConvertUtil.durationOf(heartbeatInterval));
            }

            return this;
        }

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
         * 通道
         */
        public Builder channel(String channel) {
            props.setChannel(channel);
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
         * SSE 心跳间隔
         */
        public Builder heartbeatInterval(Duration sseHeartbeatInterval) {
            props.setHeartbeatInterval(sseHeartbeatInterval);
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