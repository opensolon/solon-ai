package demo.ai.mcp.server.skills;

import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.ai.mcp.client.McpTalentClient;

public class OrderManagerSkillClient extends McpTalentClient {
    public OrderManagerSkillClient(McpClientProvider clientProvider) {
        super(clientProvider);
    }
}