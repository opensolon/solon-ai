package demo.ai.mcp.server;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.server.annotation.McpServerEndpoint;
import org.noear.solon.annotation.Param;
import org.noear.solon.auth.annotation.AuthRoles;

/**
 * @author noear 2025/7/1 created
 */
@McpServerEndpoint(channel = McpChannel.STREAMABLE, sseEndpoint = "/auth2/sse")
public class McpServerAuth2 {
    @AuthRoles("1")
    @ToolMapping(description = "查询天气预报")
    public String getWeather(@Param(description = "城市位置") String location) {
        return "晴，14度";
    }
}
