package demo.ai.mcp.server.talents;

import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.ai.mcp.client.McpTalentClient;

public class OrderManagerTalentClient extends McpTalentClient {
    public OrderManagerTalentClient(McpClientProvider clientProvider) {
        super(clientProvider);
    }
}