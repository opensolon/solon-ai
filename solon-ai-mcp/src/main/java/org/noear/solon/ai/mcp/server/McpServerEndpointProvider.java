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

import io.modelcontextprotocol.spec.McpSchema;
import org.noear.solon.Solon;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.server.annotation.McpServerEndpoint;
import org.noear.solon.ai.mcp.server.manager.*;
import org.noear.solon.ai.mcp.server.prompt.FunctionPrompt;
import org.noear.solon.ai.mcp.server.resource.FunctionResource;
import org.noear.solon.ai.mcp.server.prompt.PromptProvider;
import org.noear.solon.ai.mcp.server.resource.ResourceProvider;
import org.noear.solon.core.Props;
import org.noear.solon.core.bean.LifecycleBean;
import org.noear.solon.core.util.ConvertUtil;

import java.time.Duration;
import java.util.*;

/**
 * Mcp 服务端点提供者
 *
 * @author noear
 * @since 3.1
 */
public class McpServerEndpointProvider implements LifecycleBean {
    private final McpServerProperties serverProperties;
    private final McpServerHost serverHost;

    public McpServerEndpointProvider(Properties properties) {
        this(Props.from(properties).bindTo(new McpServerProperties()));
    }

    public McpServerEndpointProvider(McpServerProperties serverProps) {
        if (Utils.isEmpty(serverProps.getChannel())) {
            throw new IllegalArgumentException("The channel is required");
        }

        if (serverProps.getContextPath() == null) {
            if (Solon.app() != null) {
                serverProps.setContextPath(Solon.cfg().serverContextPath()); //@since 2025-08-23
            }
        }

        McpSchema.ServerCapabilities serverCapabilities = McpSchema.ServerCapabilities.builder()
                .tools(true)
                .resources(true, true)
                .prompts(true)
                .logging()
                .build();

        this.serverProperties = serverProps;

        if (McpChannel.STREAMABLE_STATELESS.equals(serverProps.getChannel())) {
            //无状态
            this.serverHost = new StatelessMcpServerHost(serverCapabilities, serverProps);
        } else {
            //有状态
            this.serverHost = new StatefulMcpServerHost(serverCapabilities, serverProps);
        }
    }

    /**
     * 获取服务端（postStart 后有效）
     */
    public McpServerHost getServer() {
        return serverHost;
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
        return serverHost.getMcpEndpoint();
    }

    /**
     * MESSAGE 端点
     *
     * @deprecated 3.5
     */
    @Deprecated
    public String getMessageEndpoint() {
        return serverHost.getMessageEndpoint();
    }

    /**
     * 设置日志级别
     */
    public void setLoggingLevel(McpSchema.LoggingLevel loggingLevel) {
        serverHost.setLoggingLevel(loggingLevel);
    }

    /**
     * 登记资源
     */
    public void addResource(FunctionResource functionResource) {
        serverHost.getResourceRegistry().add(serverProperties, functionResource);
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
        return serverHost.getResourceRegistry().contains(resourceUri);
    }

    /**
     * 移除资源
     */
    public void removeResource(String resourceUri) {
        serverHost.getResourceRegistry().remove(resourceUri);
    }

    /**
     * 移除资源
     */
    public void removeResource(ResourceProvider resourceProvider) {
        for (FunctionResource functionResource : resourceProvider.getResources()) {
            removeResource(functionResource.uri());
        }
    }

    public Collection<FunctionResource> getResources() {
        return serverHost.getResourceRegistry().all();
    }

    /// ////////////////////////

    /**
     * 登记提示语
     */
    public void addPrompt(FunctionPrompt functionPrompt) {
        serverHost.getPromptRegistry().add(serverProperties, functionPrompt);
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
        return serverHost.getPromptRegistry().contains(promptName);
    }

    /**
     * 移除提示语
     */
    public void removePrompt(String promptName) {
        serverHost.getPromptRegistry().remove(promptName);
    }

    /**
     * 移除提示语
     */
    public void removePrompt(PromptProvider promptProvider) {
        for (FunctionPrompt functionPrompt : promptProvider.getPrompts()) {
            removePrompt(functionPrompt.name());
        }
    }

    public Collection<FunctionPrompt> getPrompts() {
        return serverHost.getPromptRegistry().all();
    }

    /// /////////////////////////

    /**
     * 登记工具
     */
    public void addTool(FunctionTool functionTool) {
        serverHost.getToolRegistry().add(serverProperties, functionTool);
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
        return serverHost.getToolRegistry().contains(toolName);
    }

    /**
     * 移除工具
     */
    public void removeTool(String toolName) {
        serverHost.getToolRegistry().remove(toolName);
    }

    /**
     * 移除工具
     */
    public void removeTool(ToolProvider toolProvider) {
        for (FunctionTool functionTool : toolProvider.getTools()) {
            removeTool(functionTool.name());
        }
    }

    /**
     * 获取所有工具
     */
    public Collection<FunctionTool> getTools() {
        return serverHost.getToolRegistry().all();
    }

    /// /////////////////////


    @Override
    public void start() {

    }

    @Override
    public void postStart() {
        serverHost.start();
    }

    /**
     * 暂停（主要用于测试）
     */
    public boolean pause() {
        return serverHost.pause();
    }

    /**
     * 恢复（主要用于测试）
     */
    public boolean resume() {
        return serverHost.resume();
    }

    @Override
    public void stop() {
        serverHost.stop();
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
            String heartbeatInterval = Solon.cfg().getByTmpl(endpointAnno.heartbeatInterval());

            String mcpEndpoint = Solon.cfg().getByTmpl(endpointAnno.mcpEndpoint());
            //@deprecated  3.5 //2025-08-11
            String sseEndpoint = Solon.cfg().getByTmpl(endpointAnno.sseEndpoint());
            String messageEndpoint = Solon.cfg().getByTmpl(endpointAnno.messageEndpoint());


            if (Utils.isEmpty(name)) {
                props.setName(endpointClz.getSimpleName());
            } else {
                props.setName(name);
            }

            props.setVersion(version);
            props.setChannel(channel);
            props.setMcpEndpoint(mcpEndpoint);
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
         * @deprecated 3.5 {@link #mcpEndpoint(String)}
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
         * 上下文路径（主要是 messageEndpoint 输出时使用）
         *
         * @deprecated 3.5
         */
        @Deprecated
        public Builder contextPath(String contextPath) {
            props.setContextPath(contextPath);
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