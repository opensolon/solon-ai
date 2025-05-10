package demo.ai.mcp.art1;

import org.noear.snack.ONode;
import org.noear.solon.ai.annotation.ResourceMapping;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.server.annotation.McpServerEndpoint;
import org.noear.solon.annotation.Param;

@McpServerEndpoint(channel = McpChannel.STDIO)
public class WeatherTools {
    ONode weather_data = ONode.loadStr("{\n" +
            "    \"New York\": {\"temp\": range(10, 25), \"conditions\": [\"sunny\", \"cloudy\", \"rainy\"]},\n" +
            "    \"London\": {\"temp\": range(5, 20), \"conditions\": [\"cloudy\", \"rainy\", \"foggy\"]},\n" +
            "    \"Tokyo\": {\"temp\": range(15, 30), \"conditions\": [\"sunny\", \"cloudy\", \"humid\"]},\n" +
            "    \"Sydney\": {\"temp\": range(20, 35), \"conditions\": [\"sunny\", \"clear\", \"hot\"]},\n" +
            "}");

    @ToolMapping(description = "获取指定城市的当前天气")
    public ONode get_weather(@Param String city) {
        if (weather_data.contains(city) == false) {
            return new ONode().set("error", "无法找到城市 " + city + " 的天气数据");
        }

        //ONode tmp = weather_data.get(city);
        //return "24度，晴";

        return null;
    }

    @ResourceMapping(uri = "weather://forecast/{city}")
    public String get_available_cities(String city){
        return "{city: "+city+", forecast: 111}";
    }
}
