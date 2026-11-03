/*
 * Copyright 2017-2026 noear.org and authors
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

import io.modelcontextprotocol.server.transport.ServerHttpHeaderValidator;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityValidator;
import io.modelcontextprotocol.spec.McpSchema;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.prompt.FunctionPrompt;
import org.noear.solon.ai.chat.resource.FunctionResource;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.server.HttpHeaderValidatorAdapter;
import org.noear.solon.ai.mcp.server.McpServerProperties;
import org.slf4j.Logger;

/**
 * MCP 服务端包装器
 *
 * @author noear
 * @since 3.8.0
 */
public interface McpServerHost {
    /**
     * 是否为“不做任何校验”的校验器
     *
     * <p>只以哨兵常量为准（未配置 / 旧 NOOP / 新 NOOP 经适配），无法从外部判断自实现体是否为空。</p>
     */
    static boolean isNoop(ServerTransportSecurityValidator securityValidator) {
        if (securityValidator == null || securityValidator == ServerTransportSecurityValidator.NOOP) {
            return true;
        }

        if (securityValidator instanceof HttpHeaderValidatorAdapter) {
            return ((HttpHeaderValidatorAdapter) securityValidator).getValidator() == ServerHttpHeaderValidator.NOOP;
        }

        return false;
    }

    /**
     * 启动时提示：web 通道未配置传输安全校验器（stdio 通道无 HTTP 头，不提示）
     *
     * <p>必须在 build 幂等区内调用，避免重复输出。</p>
     */
    static void warnIfNoop(Logger log, McpServerProperties serverProperties, String mcpEndpoint, ServerTransportSecurityValidator securityValidator) {
        if (McpChannel.STDIO.equalsIgnoreCase(serverProperties.getChannel())) {
            return;
        }

        if (isNoop(securityValidator) == false) {
            return;
        }

        log.warn("Mcp-Server has no transport security validator, HTTP headers (Origin/Host) will NOT be validated, name={}, channel={}, endpoint={}. Suggest configuring a ServerHttpHeaderValidator (e.g. DefaultServerTransportSecurityValidator) if this endpoint is exposed to browsers or the Internet",
                serverProperties.getName(),
                serverProperties.getChannel(),
                mcpEndpoint);
    }

    /**
     * 设置日志级别
     */
    void setLoggingLevel(McpSchema.LoggingLevel loggingLevel);

    /**
     * 获取 mcp 端点
     */
    String getMcpEndpoint();

    /**
     * 获取 message 端点（有可能与 mcp 端点相关）
     */
    String getMessageEndpoint();

    /**
     * 获取提示词注册表
     */
    McpPrimitivesRegistry<FunctionPrompt> getPromptRegistry();

    /**
     * 获取资源注册表
     */
    McpPrimitivesRegistry<FunctionResource> getResourceRegistry();

    /**
     * 获取工具注册表
     *
     */
    McpPrimitivesRegistry<FunctionTool> getToolRegistry();

    /**
     * 构建
     */
    Object build();

    /**
     * 开始
     */
    void start();

    /**
     * 停止
     */
    void stop();

    /**
     * 暂停
     */
    boolean pause();

    /**
     * 恢复
     */
    boolean resume();
}