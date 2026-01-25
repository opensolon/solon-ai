package demo.ai.mcp.server.skills;

import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.ai.mcp.client.McpSkillClient;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class OrderManagerSkillClient extends McpSkillClient {
    public OrderManagerSkillClient(McpClientProvider clientProvider) {
        super(clientProvider);
    }

    @Override
    public Collection<FunctionTool> getTools(Prompt prompt) {
        List<FunctionTool> tools = new ArrayList<>();

        // 4. 权限隔离：只有属性中标记为 ADMIN 的用户，才动态挂载“取消订单”工具
        if ("ADMIN".equals(prompt.attr("user_role"))) {
            getToolsStream()
                    .filter(tool -> "ALL".equals(tool.meta().get("user_role"))
                            || "ADMIN".equals(tool.meta().get("user_role")))
                    .forEach(tool -> tools.add(tool));
        } else {
            getToolsStream()
                    .filter(tool -> "ALL".equals(tool.meta().get("user_role")))
                    .forEach(tool -> tools.add(tool));
        }

        return tools;
    }
}