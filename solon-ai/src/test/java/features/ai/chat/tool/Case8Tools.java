package features.ai.chat.tool;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

import java.util.HashMap;

/**
 * @author noear 2025/5/7 created
 */
public class Case8Tools {
    @ToolMapping(description = "查询设备所有参数信息、日用电量、日产液量、生产时间等")
    public HashMap<Object, Object> getOilWellData(
            @Param(description = "设备名称") String name,
            @Param(description = "年") Integer year,
            @Param(description = "月份") Integer month,
            @Param(description = "日") Integer day) {

        return new HashMap<Object, Object>(){{
            put("设备不存在,请尝试查询设备列表", 0);
        }};
    }
}
