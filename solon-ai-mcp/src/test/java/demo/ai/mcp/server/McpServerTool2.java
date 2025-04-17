package demo.ai.mcp.server;

import org.noear.solon.ai.chat.annotation.ToolMapping;
import org.noear.solon.ai.chat.annotation.ToolParam;
import org.noear.solon.ai.mcp.server.annotation.McpServerEndpoint;
import org.noear.solon.annotation.Component;

/**
 * @author noear 2025/4/8 created
 */
@McpServerEndpoint(sseEndpoint = "/demo2/sse", name = "McpServerTool2")
public class McpServerTool2 {
    //
    // 建议开启编译参数：-parameters （否则，要再配置参数的 name）
    //
    @ToolMapping(description = "查询天气预报")
    public String get_weather(@ToolParam(description = "城市位置") String location) {
        return "晴，14度";
    }
}