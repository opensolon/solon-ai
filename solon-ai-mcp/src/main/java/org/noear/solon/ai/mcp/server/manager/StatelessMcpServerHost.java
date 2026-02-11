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
import io.modelcontextprotocol.server.McpStatelessAsyncServer;
import io.modelcontextprotocol.server.transport.*;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import org.noear.solon.Solon;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.mcp.server.McpServerProperties;
import org.noear.solon.ai.chat.prompt.FunctionPrompt;
import org.noear.solon.ai.chat.resource.FunctionResource;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 无状态服务宿主
 *
 * @author noear
 * @since 3.8.0
 */
public class StatelessMcpServerHost implements McpServerHost {
    private static Logger log = LoggerFactory.getLogger(StatelessMcpServerHost.class);

    private final String mcpEndpoint;

    private McpSchema.LoggingLevel loggingLevel = McpSchema.LoggingLevel.INFO;

    private final McpPrimitivesRegistry<FunctionPrompt> promptManager;
    private final McpPrimitivesRegistry<FunctionResource> resourceManager;
    private final McpPrimitivesRegistry<FunctionTool> toolManager;

    private final McpServerProperties serverProperties;

    private final McpStatelessServerTransport mcpTransportProvider;
    private final McpServer.StatelessAsyncSpecification mcpServerSpec;
    private McpStatelessAsyncServer server;

    public StatelessMcpServerHost(McpSchema.ServerCapabilities serverCapabilities, McpServerProperties serverProps) {
        this.serverProperties = serverProps;

        //streamable
        if (Utils.isEmpty(serverProps.getMcpEndpoint())) {
            this.mcpEndpoint = serverProps.getSseEndpoint();
        } else {
            this.mcpEndpoint = serverProps.getMcpEndpoint();
        }

        //断言
        Assert.notEmpty(this.mcpEndpoint, "MCP endpoint is empty");

        this.mcpTransportProvider = WebRxStatelessServerTransport.builder()
                .messageEndpoint(this.mcpEndpoint)
                .build();

        mcpServerSpec = McpServer.async(this.mcpTransportProvider)
                .capabilities(serverCapabilities)
                .serverInfo(serverProps.getName(), serverProps.getVersion());

        this.promptManager = new StatelessPromptRegistry(this::getServer, mcpServerSpec);
        this.resourceManager = new StatelessResourceRegistry(this::getServer, mcpServerSpec);
        this.toolManager = new StatelessToolRegistry(this::getServer, mcpServerSpec);
    }

    public void setLoggingLevel(McpSchema.LoggingLevel loggingLevel) {
        this.loggingLevel = loggingLevel;
    }

    public String getMcpEndpoint() {
        return mcpEndpoint;
    }

    public String getMessageEndpoint() {
        return mcpEndpoint;
    }

    public McpStatelessAsyncServer getServer() {
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

        log.info("Mcp-Server started, name={}, version={}, channel={}, mcpEndpoint={}, toolRegistered={}, resourceRegistered={}, promptRegistered={}",
                serverProperties.getName(),
                serverProperties.getVersion(),
                serverProperties.getChannel(),
                this.mcpEndpoint,
                toolManager.count(),
                resourceManager.count(),
                promptManager.count());

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
