package lab.ai.a2a.demo.tool_only;

import org.noear.snack.core.utils.StringUtil;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/**
 * @author haiTao.Wang on 2025/8/21.
 */
public class Tools2 {

    @ToolMapping(description = "根据天气推荐旅游景点")
    public String recommendTourist(@Param(description = "天气") String weather) {
        if (StringUtil.isEmpty(weather)) {
            return "请输入天气";
        }

        if (weather.contains("晴")) {
            return "公园、爬山等室外运动";
        }
        return "海洋馆、科技馆等室内运动";
    }

}