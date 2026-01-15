package features.ai.flow.manual;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/**
 * @author noear 2025/5/13 created
 */
public class ToolDemo {
    @ToolMapping(description = "获取指定城市的天气情况")
    public String get_weather(@Param(name = "location", description = "根据用户提到的地点推测城市") String location) {
        if (location == null) {
            throw new IllegalStateException("arguments location is null (Assistant recognition failure)");
        }

        return "晴，24度";// + weatherService.get(location);
    }
}
