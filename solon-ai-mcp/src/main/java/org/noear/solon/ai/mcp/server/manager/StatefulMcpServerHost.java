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
import io.modelcontextprotocol.server.transport.IMcpHttpServerTransport;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.server.transport.WebRxSseServerTransportProvider;
import io.modelcontextprotocol.server.transport.WebRxStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerTransportProviderBase;
import org.noear.solon.Solon;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.server.McpServerProperties;
import org.noear.solon.ai.chat.prompt.FunctionPrompt;
import org.noear.solon.ai.chat.resource.FunctionResource;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.PathUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 有状态服务宿主
 *
 * @author noear
 * @since 3.8.0
 */
public class StatefulMcpServerHost implements McpServerHost {
    private static Logger log = LoggerFactory.getLogger(StatefulMcpServerHost.class);

    private final String mcpEndpoint;
    private final String messageEndpoint;

    private McpSchema.LoggingLevel loggingLevel = McpSchema.LoggingLevel.INFO;

    private final McpPrimitivesRegistry<FunctionPrompt> promptManager;
    private final McpPrimitivesRegistry<FunctionResource> resourceManager;
    private final McpPrimitivesRegistry<FunctionTool> toolManager;

    private final McpServerProperties serverProperties;

    private final McpServerTransportProviderBase mcpTransportProvider;
    private final McpServer.AsyncSpecification mcpServerSpec;
    private McpAsyncServer server;

    public StatefulMcpServerHost(McpSchema.ServerCapabilities serverCapabilities, McpServerProperties serverProps) {
        this.serverProperties = serverProps;

        if (McpChannel.SSE.equals(serverProps.getChannel())) {
            //sse
            if (Utils.isEmpty(serverProps.getSseEndpoint())) {
                this.mcpEndpoint = serverProps.getMcpEndpoint();
            } else {
                this.mcpEndpoint = serverProps.getSseEndpoint();
            }

            //断言
            Assert.notEmpty(this.mcpEndpoint, "MCP sse endpoint is empty");

            if (Utils.isEmpty(serverProps.getMessageEndpoint())) {
                this.messageEndpoint = PathUtil.joinUri(this.mcpEndpoint, "/message"); //兼容 2024 版协议风格
            } else {
                this.messageEndpoint = serverProps.getMessageEndpoint();
            }
        } else if (McpChannel.STREAMABLE.equals(serverProps.getChannel())) {
            //streamable
            if (Utils.isEmpty(serverProps.getMcpEndpoint())) {
                this.mcpEndpoint = serverProps.getSseEndpoint();
            } else {
                this.mcpEndpoint = serverProps.getMcpEndpoint();
            }

            //断言
            Assert.notEmpty(this.mcpEndpoint, "MCP endpoint is empty");

            this.messageEndpoint = this.mcpEndpoint;
        } else {
            this.mcpEndpoint = null;
            this.messageEndpoint = null;
        }

        if (McpChannel.STDIO.equalsIgnoreCase(serverProps.getChannel())) {
            //stdio 通道
            this.mcpTransportProvider = new StdioServerTransportProvider(McpJsonMapper.getDefault());

            mcpServerSpec = McpServer.async((StdioServerTransportProvider) this.mcpTransportProvider)
                    .capabilities(serverCapabilities)
                    .serverInfo(serverProps.getName(), serverProps.getVersion());
        } else {
            //sse 通道
            if (McpChannel.SSE.equals(serverProps.getChannel())) {
                this.mcpTransportProvider = WebRxSseServerTransportProvider.builder()
                        .sseEndpoint(this.mcpEndpoint)
                        .messageEndpoint(this.messageEndpoint)
                        .basePath(serverProps.getContextPath())
                        .keepAliveInterval(serverProps.getHeartbeatInterval())
                        .build();

                mcpServerSpec = McpServer.async((WebRxSseServerTransportProvider) this.mcpTransportProvider)
                        .capabilities(serverCapabilities)
                        .serverInfo(serverProps.getName(), serverProps.getVersion());
            } else {
                this.mcpTransportProvider = WebRxStreamableServerTransportProvider.builder()
                        .messageEndpoint(this.mcpEndpoint)
                        .keepAliveInterval(serverProps.getHeartbeatInterval())
                        .build();

                mcpServerSpec = McpServer.async((WebRxStreamableServerTransportProvider) this.mcpTransportProvider)
                        .capabilities(serverCapabilities)
                        .serverInfo(serverProps.getName(), serverProps.getVersion());
            }
        }

        this.promptManager = new StatefulPromptRegistry(this::getServer, mcpServerSpec);
        this.resourceManager = new StatefulResourceRegistry(this::getServer, mcpServerSpec);
        this.toolManager = new StatefulToolRegistry(this::getServer, mcpServerSpec);
    }

    public void setLoggingLevel(McpSchema.LoggingLevel loggingLevel) {
        this.loggingLevel = loggingLevel;
    }

    public String getMcpEndpoint() {
        return mcpEndpoint;
    }

    public String getMessageEndpoint() {
        return messageEndpoint;
    }

    public McpAsyncServer getServer() {
        return server;
    }

    public McpPrimitivesRegistry<FunctionPrompt> getPromptRegistry() {
        return promptManager;
    }

    public McpPrimitivesRegistry<FunctionResource> getResourceRegistry() {
        return resourceManager;
    }

    public McpPrimitivesRegistry<FunctionTool> getToolRegistry() {
        return toolManager;
    }

    @Override
    public void start() {
        server = mcpServerSpec.build();
        server.loggingNotification(McpSchema.LoggingMessageNotification.builder().level(loggingLevel).build());

        if (McpChannel.STDIO.equalsIgnoreCase(serverProperties.getChannel())) {
            log.info("Mcp-Server started, name={}, version={}, channel={}, toolRegistered={}, resourceRegistered={}, promptRegistered={}",
                    serverProperties.getName(),
                    serverProperties.getVersion(),
                    serverProperties.getChannel(),
                    toolManager.count(),
                    resourceManager.count(),
                    promptManager.count());
        } else if (McpChannel.SSE.equalsIgnoreCase(serverProperties.getChannel())) {
            log.info("Mcp-Server started, name={}, version={}, channel={}, sseEndpoint={}, messageEndpoint={}, toolRegistered={}, resourceRegistered={}, promptRegistered={}",
                    serverProperties.getName(),
                    serverProperties.getVersion(),
                    serverProperties.getChannel(),
                    this.mcpEndpoint,
                    this.messageEndpoint,
                    toolManager.count(),
                    resourceManager.count(),
                    promptManager.count());
        } else {
            log.info("Mcp-Server started, name={}, version={}, channel={}, mcpEndpoint={}, toolRegistered={}, resourceRegistered={}, promptRegistered={}",
                    serverProperties.getName(),
                    serverProperties.getVersion(),
                    serverProperties.getChannel(),
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

    @Override
    public void stop() {
        if (server != null) {
            server.close();
        }
    }

    /**
     * 暂停（主要用于测试）
     */
    @Override
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
    @Override
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
}
