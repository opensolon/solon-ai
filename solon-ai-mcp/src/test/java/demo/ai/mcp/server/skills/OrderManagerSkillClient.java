package demo.ai.mcp.server.skills;

import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.ai.mcp.client.McpSkillClient;

public class OrderManagerSkillClient extends McpSkillClient {
    public OrderManagerSkillClient(McpClientProvider clientProvider) {
        super(clientProvider);
    }
}