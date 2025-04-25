package features.ai.chat;

import org.noear.solon.ai.chat.annotation.ToolMapping;
import org.noear.solon.ai.chat.annotation.ToolParam;

/**
 * @author noear 2025/4/25 created
 */
public class ReturnTools {
    @ToolMapping(description = "获取指定城市的天气情况", returnDirect = true)
    public String get_weather(@ToolParam(name = "location", description = "根据用户提到的地点推测城市") String location) {
        if (location == null) {
            throw new IllegalStateException("arguments location is null (Assistant recognition failure)");
        }

        return "晴，24度";
    }

    @ToolMapping(description = "查询城市降雨量", returnDirect = true)
    public String get_rainfall(@ToolParam(name = "location", description = "城市位置") String location) {
        if (location == null) {
            throw new IllegalStateException("arguments location is null (Assistant recognition failure)");
        }

        return "555毫米";
    }
}
