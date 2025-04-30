package demo.ai.mcp.server_resume;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.mcp.server.McpServerEndpointProvider;
import org.noear.solon.ai.mcp.server.annotation.McpServerEndpoint;
import org.noear.solon.annotation.Inject;
import org.noear.solon.annotation.Param;
import org.noear.solon.scheduling.annotation.Scheduled;

/**
 * @author noear 2025/4/8 created
 */
@McpServerEndpoint(name = "mcp-server1")
public class McpServerTool {
    @ToolMapping(description = "查询天气预报")
    public String getWeather(@Param(description = "城市位置") String location) {
        return "晴，14度";
    }

    //注入当前工具对应的端点提供者
    @Inject("mcp-server1")
    private McpServerEndpointProvider serverEndpointProvider;

    //30秒为间隔（暂停或恢复）//或者用 web 控制
    @Scheduled(fixedRate = 30_000)
    public void pauseAndResume() {
        if (serverEndpointProvider.pause() == false) {
            //如果暂停失败，说明之前已经暂停
            serverEndpointProvider.resume();
        }
    }
}