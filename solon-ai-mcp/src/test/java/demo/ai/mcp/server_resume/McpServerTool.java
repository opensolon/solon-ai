package demo.ai.mcp.server_resume;

import org.noear.solon.ai.chat.annotation.ToolMapping;
import org.noear.solon.ai.chat.annotation.ToolParam;
import org.noear.solon.ai.mcp.server.McpServerEndpointProvider;
import org.noear.solon.ai.mcp.server.annotation.McpServerEndpoint;
import org.noear.solon.annotation.Inject;
import org.noear.solon.scheduling.annotation.Scheduled;

/**
 * @author noear 2025/4/8 created
 */
@McpServerEndpoint(name = "mcp-server1")
public class McpServerTool {
    @ToolMapping(description = "查询天气预报")
    public String getWeather(@ToolParam(description = "城市位置") String location) {
        return "晴，14度";
    }

    @Inject("mcp-server1")
    private McpServerEndpointProvider serverEndpointProvider;

    @Scheduled(fixedRate = 30_000)
    public void pauseAndResume() {
        if (serverEndpointProvider.pause() == false) {
            //如果要不能赞停说明，之前已经停了
            serverEndpointProvider.resume();
        }
    }
}