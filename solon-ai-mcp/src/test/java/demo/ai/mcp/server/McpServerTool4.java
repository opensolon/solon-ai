package demo.ai.mcp.server;

import org.noear.solon.ai.chat.annotation.ToolMapping;
import org.noear.solon.ai.chat.annotation.ToolParam;
import org.noear.solon.ai.mcp.server.annotation.McpServerEndpoint;

/**
 * @author noear 2025/4/8 created
 */
@McpServerEndpoint(sseEndpoint = "/demo4/sse")
public class McpServerTool4 {
    @ToolMapping(description = "查询城市降雨量")
    public String get_rainfall(@ToolParam(name = "location", description = "城市位置") String location) {
        if (location == null) {
            throw new IllegalStateException("arguments location is null (Assistant recognition failure)");
        }

        return "555毫米";
    }
}