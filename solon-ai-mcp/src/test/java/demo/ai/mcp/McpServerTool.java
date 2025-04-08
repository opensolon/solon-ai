package demo.ai.mcp;

import org.noear.solon.ai.chat.annotation.FunctionMapping;
import org.noear.solon.ai.chat.annotation.FunctionParam;
import org.noear.solon.annotation.Component;

/**
 * @author noear 2025/4/8 created
 */
@Component
public class McpServerTool {
    //
    // 建议开启编译参数：-parameters （否则，要再配置参数的 name）
    //
    @FunctionMapping(description = "查询天气预报")
    public String getWeather(@FunctionParam(description = "城市位置") String location) {
        return "晴，14度";
    }
}