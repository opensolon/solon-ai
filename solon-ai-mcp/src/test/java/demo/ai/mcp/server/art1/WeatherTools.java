package demo.ai.mcp.server.art1;

import org.noear.snack.ONode;
import org.noear.solon.ai.annotation.ResourceMapping;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.tool.ToolCallResultJsonConverter;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.server.annotation.McpServerEndpoint;
import org.noear.solon.annotation.Param;
import org.noear.solon.annotation.Produces;
import org.noear.solon.core.util.MimeType;


@McpServerEndpoint(sseEndpoint = "/mcp/WeatherTools/sse")
public class WeatherTools {
    @ToolMapping(description = "获取指定城市的当前天气")
    public String get_weather(@Param String city) {
        return "{city: '" + city + "', temperature:[10,25], condition:['sunny', 'clear', 'hot'], unit:celsius}";
    }

    @Produces(MimeType.APPLICATION_JSON_VALUE)
    @ResourceMapping(uri = "weather://cities", description = "获取所有可用的城市列表")
    public String[] get_available_cities() {
        return new String[]{"Tokyo", "Sydney", "Tokyo"};
    }

    @ResourceMapping(uri = "weather://forecast/{city}", description = "获取指定城市的天气预报资源")
    public String get_forecast(@Param String city) {
        return "{city: " + city + ", forecast: 111}";
    }
}
