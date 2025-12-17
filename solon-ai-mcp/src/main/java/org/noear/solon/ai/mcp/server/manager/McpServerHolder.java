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
import org.noear.solon.ai.mcp.server.prompt.FunctionPrompt;
import org.noear.solon.ai.mcp.server.resource.FunctionResource;

/**
 *
 * @author noear
 * @since 3.8.0
 */
public interface McpServerHolder {
    void setLoggingLevel(McpSchema.LoggingLevel loggingLevel);

    String getMcpEndpoint();

    String getMessageEndpoint();

    McpServerManager<FunctionPrompt> getPromptManager();

    McpServerManager<FunctionResource> getResourceManager();

    McpServerManager<FunctionTool> getToolManager();

    void start();

    void stop();

    boolean pause();

    boolean resume();
}