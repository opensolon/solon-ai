package demo.ai.mcp.server;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.server.annotation.McpServerEndpoint;
import org.noear.solon.annotation.Body;
import org.noear.solon.core.handle.Context;

/**
 * @author noear 2025/4/8 created
 */
@McpServerEndpoint(channel = McpChannel.STREAMABLE, mcpEndpoint = "/demo8/sse")
public class McpServerTool8Body {
    @ToolMapping(description = "查询天气预报", returnDirect = true)
    public String getWeather(@Body WeaterDto dto, Context ctx) {
        System.out.println("------------: sessionId: " + ctx.sessionId());

        ctx.realIp();

        return "晴，15度";
    }
}