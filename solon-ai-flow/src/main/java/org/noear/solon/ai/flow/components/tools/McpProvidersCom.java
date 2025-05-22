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
package org.noear.solon.ai.flow.components.tools;

import org.noear.snack.ONode;
import org.noear.solon.ai.flow.components.AbsAiComponent;
import org.noear.solon.ai.flow.components.AiPropertyComponent;
import org.noear.solon.ai.flow.components.Attrs;
import org.noear.solon.ai.mcp.client.McpProviders;
import org.noear.solon.annotation.Component;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.FlowException;
import org.noear.solon.flow.Node;

/**
 * MCP 工具组件
 *
 * @author noear
 * @since 3.3
 */
@Component("McpProviders")
public class McpProvidersCom extends AbsAiComponent implements AiPropertyComponent {
    static final String META_MCP_SERVERS = "mcpServers";

    @Override
    protected void doRun(FlowContext context, Node node) throws Throwable {

        McpProviders mcpProviders = (McpProviders) node.attachment;
        if (mcpProviders == null) {
            ONode mcpServersNode = ONode.loadObj(node.getMetas());
            mcpProviders = McpProviders.fromMcpServers(mcpServersNode);
            node.attachment = mcpProviders;
        }

        addProperty(context, Attrs.PROP_MCP_PROVIDERS, mcpProviders);
    }
}