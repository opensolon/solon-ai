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

import io.modelcontextprotocol.spec.McpSchema;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.mcp.primitives.prompt.FunctionPrompt;
import org.noear.solon.ai.mcp.primitives.resource.FunctionResource;

/**
 * MCP 服务端包装器
 *
 * @author noear
 * @since 3.8.0
 */
public interface McpServerHost {
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