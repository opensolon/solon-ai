package demo.ai.mcp.server.skills;

import org.noear.solon.Utils;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.Skill;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.mcp.client.McpClientProvider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class OrderManagerSkillClient implements Skill {
    private final McpClientProvider clientProvider;

    public OrderManagerSkillClient(McpClientProvider clientProvider) {
        this.clientProvider = clientProvider;
    }

    @Override
    public String description() {
        return clientProvider.readResourceAsText("description")
                .getContent();
    }

    @Override
    public boolean isSupported(Prompt prompt) {
        return clientProvider.callToolAsText("isSupported", Utils.asMap("prompt", prompt))
                .equals("true");
    }

    @Override
    public Collection<FunctionTool> getTools(Prompt prompt) {
        List<FunctionTool> tools = new ArrayList<>();

        // 4. 权限隔离：只有属性中标记为 ADMIN 的用户，才动态挂载“取消订单”工具
        if ("ADMIN".equals(prompt.attr("user_role"))) {
            clientProvider.getTools().stream()
                    .filter(tool -> "ALL".equals(tool.meta().get("user_role"))
                            || "ADMIN".equals(tool.meta().get("user_role")))
                    .forEach(tool -> tools.add(tool));
        } else {
            clientProvider.getTools().stream()
                    .filter(tool -> "ALL".equals(tool.meta().get("user_role")))
                    .forEach(tool -> tools.add(tool));
        }

        return tools;
    }
}
