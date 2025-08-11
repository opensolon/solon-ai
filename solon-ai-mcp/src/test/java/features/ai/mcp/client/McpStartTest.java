package features.ai.mcp.client;

import demo.ai.mcp.server.McpServerApp;
import demo.ai.mcp.server.McpServerTool2;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.server.McpServerEndpointProvider;
import org.noear.solon.ai.mcp.server.prompt.MethodPromptProvider;
import org.noear.solon.ai.mcp.server.resource.MethodResourceProvider;
import org.noear.solon.test.SolonTest;

/**
 * @author noear 2025/5/2 created
 */
@SolonTest(McpServerApp.class)
public class McpStartTest {
    @Test
    public void case1() {
        McpServerEndpointProvider endpointProvider = McpServerEndpointProvider.builder()
                .channel(McpChannel.STDIO)
                .build();

        McpServerTool2 mcpServerTool2 = new McpServerTool2();

        endpointProvider.addTool(new MethodToolProvider(mcpServerTool2));
        endpointProvider.addResource(new MethodResourceProvider(mcpServerTool2));
        endpointProvider.addPrompt(new MethodPromptProvider(mcpServerTool2));

        endpointProvider.postStart();
    }
}
