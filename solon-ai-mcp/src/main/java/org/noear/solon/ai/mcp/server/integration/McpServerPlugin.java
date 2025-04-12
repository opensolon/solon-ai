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
package org.noear.solon.ai.mcp.server.integration;

import org.noear.solon.ai.chat.annotation.ToolMapping;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.MethodFunctionTool;
import org.noear.solon.ai.mcp.server.McpServerLifecycle;
import org.noear.solon.ai.mcp.server.McpServerProperties;
import org.noear.solon.core.*;

/**
 * @author noear
 * @since 3.1
 */
public class McpServerPlugin implements Plugin {
    @Override
    public void start(AppContext context) throws Throwable {
        McpServerProperties serverProperties = context.cfg().bindTo(McpServerProperties.class);

        if (serverProperties.isEnabled()) {
            McpServerLifecycle mcpServerLifecycle = new McpServerLifecycle(context, serverProperties);

            //从组件中提取
            context.beanExtractorAdd(ToolMapping.class, (bw, method, anno) -> {
                FunctionTool functionTool = new MethodFunctionTool(bw.raw(), method);
                mcpServerLifecycle.addTool(functionTool);
            });

            //订阅接口实现
            context.subBeansOfType(FunctionTool.class, functionTool -> {
                mcpServerLifecycle.addTool(functionTool);
            });

            //注册生命周期
            context.lifecycle(mcpServerLifecycle);
        }
    }
}