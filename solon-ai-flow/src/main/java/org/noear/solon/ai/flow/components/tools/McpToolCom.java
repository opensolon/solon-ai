package org.noear.solon.ai.flow.components.tools;

import org.noear.snack.ONode;
import org.noear.solon.ai.flow.components.AbsAiComponent;
import org.noear.solon.ai.flow.components.AiPropertyComponent;
import org.noear.solon.ai.flow.components.Attrs;
import org.noear.solon.ai.mcp.client.McpClientProperties;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.annotation.Component;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;

/**
 * MCP 工具组件
 *
 * @author noear
 * @since 3.1
 */
@Component("McpTool")
public class McpToolCom extends AbsAiComponent implements AiPropertyComponent {
    static final String META_MCP_CONFIG = "mcpConfig";

    @Override
    protected void doRun(FlowContext context, Node node) throws Throwable {
        McpClientProvider clientProvider = (McpClientProvider) node.attachment;
        if (clientProvider == null) {
            McpClientProperties clientProperties = ONode.load(node.getMeta(META_MCP_CONFIG))
                    .toObject(McpClientProperties.class);

            clientProvider = new McpClientProvider(clientProperties);
            node.attachment = clientProvider;
        }

        addProperty(context, Attrs.PROP_TOOLS, clientProvider);
    }
}