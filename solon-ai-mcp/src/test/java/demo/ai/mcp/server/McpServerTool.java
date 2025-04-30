package demo.ai.mcp.server;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.annotation.ToolParam;
import org.noear.solon.annotation.Component;

/**
 * @author noear 2025/4/8 created
 */
@Component
public class McpServerTool {
    //
    // 建议开启编译参数：-parameters （否则，要再配置参数的 name）
    //
    @ToolMapping(description = "查询天气预报")
    public String getWeather(@ToolParam(description = "城市位置") String location) {
        return "晴，14度";
    }
}