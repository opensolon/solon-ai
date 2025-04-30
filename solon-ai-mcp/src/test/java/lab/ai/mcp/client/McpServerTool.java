package lab.ai.mcp.client;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.server.annotation.McpServerEndpoint;
import org.noear.solon.annotation.Param;

@McpServerEndpoint(channel = McpChannel.STDIO) //表示使用 stdio
public class McpServerTool {
    @ToolMapping(description = "查询天气预报")
    public String get_weather(@Param(description = "城市位置") String location) {
        return "晴，14度";
    }
}