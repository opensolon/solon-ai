package demo.ai.mcp.art1;

import org.noear.snack.ONode;
import org.noear.solon.ai.annotation.ResourceMapping;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.server.annotation.McpServerEndpoint;
import org.noear.solon.annotation.Param;

@McpServerEndpoint(channel = McpChannel.STDIO)
public class WeatherTools {
    @ToolMapping(description = "获取指定城市的当前天气")
    public String get_weather(@Param String city) {
        return "24度，晴";
    }

    @ResourceMapping(uri = "weather://forecast/{city}")
    public String get_available_cities(String city) {
        return "{city: " + city + ", forecast: 111}";
    }
}
