package demo.ai.mcp.server_resume;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.annotation.ToolParam;
import org.noear.solon.ai.mcp.server.annotation.McpServerEndpoint;

/**
 * @author noear 2025/4/8 created
 */
@McpServerEndpoint(sseEndpoint = "/case2/sse")
public class McpServerTool2 {
    @ToolMapping(description = "查询天气预报")
    public String getWeather(@ToolParam(description = "城市位置") String location) {
        return "晴，14度";
    }
}