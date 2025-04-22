package lab.ai.mcp.client;

import org.noear.solon.ai.chat.annotation.ToolMapping;
import org.noear.solon.ai.chat.annotation.ToolParam;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.server.annotation.McpServerEndpoint;

@McpServerEndpoint(channel = McpChannel.STDIO) //表示使用 stdio
public class McpServerTool {
    @ToolMapping(description = "查询天气预报")
    public String get_weather(@ToolParam(description = "城市位置") String location) {
        return "晴，14度";
    }
}