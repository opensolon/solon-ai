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
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.*;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerTransportProviderBase;
import org.noear.solon.Solon;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.server.annotation.McpServerEndpoint;
import org.noear.solon.ai.mcp.server.manager.PromptMcpServerManager;
import org.noear.solon.ai.mcp.server.manager.ResourceMcpServerManager;
import org.noear.solon.ai.mcp.server.manager.ToolMcpServerManager;
import org.noear.solon.ai.mcp.server.prompt.FunctionPrompt;
import org.noear.solon.ai.mcp.server.resource.FunctionResource;
import org.noear.solon.ai.mcp.server.prompt.PromptProvider;
import org.noear.solon.ai.mcp.server.resource.ResourceProvider;
import org.noear.solon.core.Props;
import org.noear.solon.core.bean.LifecycleBean;
import org.noear.solon.core.util.ConvertUtil;
import org.noear.solon.lang.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;

/**
 * Mcp 服务端点提供者
 *
 * @author noear
 * @since 3.1
 */
public class McpServerEndpointProvider implements LifecycleBean {
    private static Logger log = LoggerFactory.getLogger(McpServerEndpointProvider.class);
    private final McpServerTransportProviderBase mcpTransportProvider;
    private final McpServer.SyncSpecification mcpServerSpec;
    private final McpServerProperties serverProperties;

    private final PromptMcpServerManager promptManager = new PromptMcpServerManager();
    private final ResourceMcpServerManager resourceManager = new ResourceMcpServerManager();
    private final ToolMcpServerManager toolManager = new ToolMcpServerManager();

    private final String mcpEndpoint;
    private final String messageEndpoint;

    private McpSchema.LoggingLevel loggingLevel = McpSchema.LoggingLevel.INFO;
    private McpSyncServer server;

    public McpServerEndpointProvider(Properties properties) {
        this(Props.from(properties).bindTo(new McpServerProperties()));
    }

    public McpServerEndpointProvider(McpServerProperties serverProperties) {
        this.serverProperties = serverProperties;

        if (McpChannel.SSE.equals(serverProperties.getChannel())) {
            //sse
            this.mcpEndpoint = serverProperties.getSseEndpoint();

            if (Utils.isEmpty(serverProperties.getMessageEndpoint())) {
                this.messageEndpoint = this.mcpEndpoint;
            } else {
                this.messageEndpoint = serverProperties.getMessageEndpoint();
            }
        } else {
            //streamable
            this.mcpEndpoint = serverProperties.getMcpEndpoint();
            this.messageEndpoint = this.mcpEndpoint;
        }


        McpSchema.ServerCapabilities serverCapabilities = McpSchema.ServerCapabilities.builder()
                .tools(true)
                .resources(true, true)
                .prompts(true)
                .logging()
                .build();

        if (McpChannel.STDIO.equalsIgnoreCase(serverProperties.getChannel())) {
            //stdio 通道
            this.mcpTransportProvider = new StdioServerTransportProvider();

            mcpServerSpec = McpServer.sync((StdioServerTransportProvider) this.mcpTransportProvider)
                    .capabilities(serverCapabilities)
                    .serverInfo(serverProperties.getName(), serverProperties.getVersion());
        } else {
            //sse 通道
            if (McpChannel.SSE.equals(serverProperties.getChannel())) {
                this.mcpTransportProvider = WebRxSseServerTransportProvider.builder()
                        .sseEndpoint(this.mcpEndpoint)
                        .messageEndpoint(this.messageEndpoint)
                        .keepAliveInterval(serverProperties.getHeartbeatInterval())
                        .objectMapper(new ObjectMapper())
                        .build();

                mcpServerSpec = McpServer.sync((WebRxSseServerTransportProvider) this.mcpTransportProvider)
                        .capabilities(serverCapabilities)
                        .serverInfo(serverProperties.getName(), serverProperties.getVersion());
            } else {
                this.mcpTransportProvider = WebRxStreamableServerTransportProvider.builder()
                        .mcpEndpoint(this.mcpEndpoint)
                        .keepAliveInterval(serverProperties.getHeartbeatInterval())
                        .objectMapper(new ObjectMapper())
                        .build();

                mcpServerSpec = McpServer.sync((WebRxStreamableServerTransportProvider) this.mcpTransportProvider)
                        .capabilities(serverCapabilities)
                        .serverInfo(serverProperties.getName(), serverProperties.getVersion());
            }
        }
    }

    /**
     * 获取服务端（postStart 后有效）
     */
    public @Nullable McpSyncServer getServer() {
        return server;
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
     * MCP 端点
     */
    public String getMcpEndpoint() {
        return mcpEndpoint;
    }

    /**
     * MESSAGE 端点
     */
    public String getMessageEndpoint() {
        return messageEndpoint;
    }

    /**
     * 设置日志级别
     */
    public void setLoggingLevel(McpSchema.LoggingLevel loggingLevel) {
        if (loggingLevel != null) {
            this.loggingLevel = loggingLevel;
        }
    }

    /**
     * 登记资源
     */
    public void addResource(FunctionResource functionResource) {
        resourceManager.add(server, mcpServerSpec, serverProperties, functionResource);
    }

    /**
     * 登记资源
     */
    public void addResource(ResourceProvider resourceProvider) {
        for (FunctionResource functionResource : resourceProvider.getResources()) {
            addResource(functionResource);
        }
    }

    /**
     * 是否存在资源
     */
    public boolean hasResource(String resourceUri) {
        return resourceManager.contains(resourceUri);
    }

    /**
     * 移除资源
     */
    public void removeResource(String resourceUri) {
        resourceManager.remove(server, resourceUri);
    }

    /**
     * 移除资源
     */
    public void removeResource(ResourceProvider resourceProvider) {
        if (server != null) {
            for (FunctionResource functionResource : resourceProvider.getResources()) {
                removeResource(functionResource.uri());
            }
        }
    }

    public Collection<FunctionResource> getResources() {
        return resourceManager.all();
    }

    /// ////////////////////////

    /**
     * 登记提示语
     */
    public void addPrompt(FunctionPrompt functionPrompt) {
        promptManager.add(server, mcpServerSpec, serverProperties, functionPrompt);
    }

    /**
     * 登记提示语
     */
    public void addPrompt(PromptProvider promptProvider) {
        for (FunctionPrompt functionPrompt : promptProvider.getPrompts()) {
            addPrompt(functionPrompt);
        }
    }

    /**
     * 是否存在提示语
     */
    public boolean hasPrompt(String promptName) {
        return promptManager.contains(promptName);
    }

    /**
     * 移除提示语
     */
    public void removePrompt(String promptName) {
        promptManager.remove(server, promptName);
    }

    /**
     * 移除提示语
     */
    public void removePrompt(PromptProvider promptProvider) {
        if (server != null) {
            for (FunctionPrompt functionPrompt : promptProvider.getPrompts()) {
                removePrompt(functionPrompt.name());
            }
        }
    }

    public Collection<FunctionPrompt> getPrompts() {
        return promptManager.all();
    }

    /// /////////////////////////

    /**
     * 登记工具
     */
    public void addTool(FunctionTool functionTool) {
        toolManager.add(server, mcpServerSpec, serverProperties, functionTool);
    }

    /**
     * 登记工具
     */
    public void addTool(ToolProvider toolProvider) {
        for (FunctionTool functionTool : toolProvider.getTools()) {
            addTool(functionTool);
        }
    }

    /***
     * 是否存在工具
     * */
    public boolean hasTool(String toolName) {
        return toolManager.contains(toolName);
    }

    /**
     * 移除工具
     */
    public void removeTool(String toolName) {
        toolManager.remove(server, toolName);
    }

    /**
     * 移除工具
     */
    public void removeTool(ToolProvider toolProvider) {
        if (server != null) {
            for (FunctionTool functionTool : toolProvider.getTools()) {
                removeTool(functionTool.name());
            }
        }
    }

    /**
     * 获取所有工具
     */
    public Collection<FunctionTool> getTools() {
        return toolManager.all();
    }

    /// /////////////////////


    @Override
    public void start() {

    }

    @Override
    public void postStart() {
        server = mcpServerSpec.build();
        server.loggingNotification(McpSchema.LoggingMessageNotification.builder().level(loggingLevel).build());

        if (McpChannel.STDIO.equalsIgnoreCase(serverProperties.getChannel())) {
            log.info("Mcp-Server started, name={}, version={}, channel={}, toolRegistered={}, resourceRegistered={}, promptRegistered={}",
                    serverProperties.getName(),
                    serverProperties.getVersion(),
                    McpChannel.STDIO,
                    toolManager.count(),
                    resourceManager.count(),
                    promptManager.count());
        } else {
            log.info("Mcp-Server started, name={}, version={}, channel={}, mcpEndpoint={}, toolRegistered={}, resourceRegistered={}, promptRegistered={}",
                    serverProperties.getName(),
                    serverProperties.getVersion(),
                    McpChannel.SSE,
                    this.mcpEndpoint,
                    toolManager.count(),
                    resourceManager.count(),
                    promptManager.count());
        }

        //如果是 web 类的
        if (mcpTransportProvider instanceof IMcpHttpServerTransport) {
            IMcpHttpServerTransport tmp = (IMcpHttpServerTransport) mcpTransportProvider;
            tmp.toHttpHandler(Solon.app());
        }
    }

    /**
     * 暂停（主要用于测试）
     */
    public boolean pause() {
        if (mcpTransportProvider instanceof IMcpHttpServerTransport) {
            IMcpHttpServerTransport tmp = (IMcpHttpServerTransport) mcpTransportProvider;

            //如果有注册
            if (Utils.isNotEmpty(Solon.app().router().getBy(tmp.getMcpEndpoint()))) {
                Solon.app().router().remove(tmp.getMcpEndpoint());
                return true;
            }
        }

        return false;
    }

    /**
     * 恢复（主要用于测试）
     */
    public boolean resume() {
        if (mcpTransportProvider instanceof IMcpHttpServerTransport) {
            IMcpHttpServerTransport tmp = (IMcpHttpServerTransport) mcpTransportProvider;

            //如果没有注册
            if (Utils.isEmpty(Solon.app().router().getBy(tmp.getMcpEndpoint()))) {
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
            String messageEndpoint = Solon.cfg().getByTmpl(endpointAnno.messageEndpoint());
            String heartbeatInterval = Solon.cfg().getByTmpl(endpointAnno.heartbeatInterval());


            if (Utils.isEmpty(name)) {
                props.setName(endpointClz.getSimpleName());
            } else {
                props.setName(name);
            }

            props.setVersion(version);
            props.setChannel(channel);
            props.setSseEndpoint(sseEndpoint);
            props.setMessageEndpoint(messageEndpoint);
            props.setEnableOutputSchema(endpointAnno.enableOutputSchema());

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
         * MCP 端点
         */
        public Builder mcpEndpoint(String mcpEndpoint) {
            props.setMcpEndpoint(mcpEndpoint);
            return this;
        }

        /**
         * SSE 端点
         *
         * @deprecated 3.5
         */
        @Deprecated
        public Builder sseEndpoint(String sseEndpoint) {
            props.setSseEndpoint(sseEndpoint);
            return this;
        }

        /**
         * Message 端点
         *
         * @deprecated 3.5
         */
        @Deprecated
        public Builder messageEndpoint(String messageEndpoint) {
            props.setMessageEndpoint(messageEndpoint);
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
         * 启用输出架构
         */
        public Builder enableOutputSchema(boolean enableOutputSchema) {
            props.setEnableOutputSchema(enableOutputSchema);
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