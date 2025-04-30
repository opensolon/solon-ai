package demo.ai.mcp.server;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.annotation.ToolParam;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.server.annotation.McpServerEndpoint;

/**
 * @author noear 2025/4/8 created
 */
@McpServerEndpoint(channel = McpChannel.STDIO)
public class McpServerTool3 {
    //
    // 建议开启编译参数：-parameters （否则，要再配置参数的 name）
    //
    @ToolMapping(description = "查询天气预报")
    public String get_weather(@ToolParam(description = "城市位置") String location) {
        return "晴，14度";
    }
}