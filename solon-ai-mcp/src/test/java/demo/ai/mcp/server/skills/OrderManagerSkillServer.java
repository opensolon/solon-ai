package demo.ai.mcp.server.skills;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.server.McpSkillServer;
import org.noear.solon.ai.mcp.server.annotation.McpServerEndpoint;


@McpServerEndpoint(channel = McpChannel.STREAMABLE_STATELESS, mcpEndpoint = "/skill/order")
public class OrderManagerSkillServer extends McpSkillServer {
    @Override
    public String description() {
        return "订单处理技能";
    }

    @Override
    public boolean isSupported(Prompt prompt) {
        // 1. 语义检查：用户当前意图是否与“订单”相关（逆序获取最新意图）
        boolean isOrderTask = prompt.getUserContent().contains("订单");

        // 2. 环境检查：必须持有合法的租户 ID 属性才能激活
        boolean hasTenant = prompt.attr("tenant_id") != null;

        return isOrderTask && hasTenant;
    }

    @Override
    public String getInstruction(Prompt prompt) {
        String tenantName = prompt.attrOrDefault("tenant_name", "未知租户");
        return "你现在是[" + tenantName + "]的订单主管。请只处理该租户下的订单数据，禁止跨租户查询。";
    }

    @ToolMapping(description = "订单查询", meta = "{user_role:'ALL'}")
    public String OrderQueryTool() {
        return null;
    }

    @ToolMapping(description = "订单取消", meta = "{user_role:'ADMIN'}")
    public String OrderCancelTool() {
        return null;
    }
}